import Flutter
import UIKit
import AVFoundation
import VideoToolbox
import CoreVideo
import Accelerate

@objc public class BbRtmpPlugin: NSObject, FlutterPlugin {
    private var channel: FlutterMethodChannel?
    private var textureRegistry: FlutterTextureRegistry?
    private var frameEventChannel: FlutterEventChannel?
    private var frameEventSink: FlutterEventSink?
    private var statusEventChannel: FlutterEventChannel?
    private var statusEventSink: FlutterEventSink?
    
    private var videoEncoder: VideoEncoder?
    private var audioEncoder: AudioEncoder?
    private var rtmpStreamer: RtmpStreamer?
    private var bitrateController: BitrateController?
    
    private var rtmpUrl: String = ""
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0
    
    // Camera capture
    private var captureSession: AVCaptureSession?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var currentCamera: AVCaptureDevice?
    private var videoInput: AVCaptureDeviceInput?
    
    // Texture for preview
    private var previewTexture: PreviewTexture?
    private var isPortrait: Bool = false
    private var isFront: Bool = true
    private var scaleMode: String = "fit"
    
    // FBO Rendering
    private var ciContext: CIContext?
    private var fboBufferPool: CVPixelBufferPool?
    private var poolWidth: Int = 0
    private var poolHeight: Int = 0
    
    // Frame callback
    private var enableFrameCallback: Bool = false
    private var frameCounter: Int64 = 0
    private var frameSkip: Int = 0 // 跳帧数，0 表示不跳帧（每帧都回调），默认 0
    private let fboReadWarmupMs: Int64 = 2000 // 延迟 2 秒后再发送，避免相机未稳定时读到全黑
    private var fboLoopStartTime: Int64 = 0 // FBO 循环开始时间（毫秒）
    
    // 保持 buffer 引用，防止被重用，确保地址稳定（零拷贝）
    // 使用固定大小的环形池，控制内存占用
    private var activeBuffers: [CVPixelBuffer] = [] // 活跃的 buffer 引用（环形池）
    private let maxActiveBuffers: Int = 4 // 最多保持 4 个 buffer 引用（环形池，固定大小）
    private let bufferQueueLock = NSLock()
    private var bufferTimestamps: [CVPixelBuffer: Int64] = [:] // buffer 时间戳，用于超时清理
    private let bufferMaxAgeMs: Int64 = 3000 // buffer 最大存活时间 3 秒（超时自动清理）
    
    // Background handling
    private var isInBackground: Bool = false
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    
    // Helper methods for StatusStreamHandler to access private properties
    func setFrameEventSink(_ sink: FlutterEventSink?) {
        frameEventSink = sink
        if sink != nil {
            enableFrameCallback = true
            frameCounter = 0
            fboLoopStartTime = 0
        } else {
            enableFrameCallback = false
            frameCounter = 0
            fboLoopStartTime = 0
        }
    }
    
    func setStatusEventSink(_ sink: FlutterEventSink?) {
        statusEventSink = sink
    }
    
    @objc public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.bb.rtmp/plugin", binaryMessenger: registrar.messenger())
        let instance = BbRtmpPlugin()
        instance.channel = channel
        instance.textureRegistry = registrar.textures()
        registrar.addMethodCallDelegate(instance, channel: channel)
        
        // Register EventChannel for frame data
        instance.frameEventChannel = FlutterEventChannel(name: "com.bb.rtmp/frames", binaryMessenger: registrar.messenger())
        instance.frameEventChannel?.setStreamHandler(StatusStreamHandler(plugin: instance, isFrameChannel: true))
        
        // Register EventChannel for streaming status
        instance.statusEventChannel = FlutterEventChannel(name: "com.bb.rtmp/status", binaryMessenger: registrar.messenger())
        instance.statusEventChannel?.setStreamHandler(StatusStreamHandler(plugin: instance, isFrameChannel: false))
        
        // Ignore SIGPIPE to prevent app from being killed when writing to a broken socket
        signal(SIGPIPE, SIG_IGN)
        
        // Register for lifecycle notifications
        NotificationCenter.default.addObserver(instance, selector: #selector(instance.appDidEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(instance, selector: #selector(instance.appWillEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "initializePreview":
            initializePreview(call: call, result: result)
        case "initialize":
            initialize(call: call, result: result)
        case "startStreaming":
            startStreaming(call: call, result: result)
        case "stopStreaming":
            stopStreaming(result: result)
        case "release":
            release()
            result(nil)
        case "switchCamera":
            switchCamera(result: result)
        case "changeResolution":
            changeResolution(call: call, result: result)
        case "setBitrate":
            setBitrate(call: call, result: result)
        case "getStatus":
            getStatus(result: result)
        case "enableFrameCallback":
            enableFrameCallback(call: call, result: result)
        case "releasePixelBufferHandle":
            releasePixelBufferHandle(call: call, result: result)
        case "getZoomRange":
            getZoomRange(result: result)
        case "setZoom":
            setZoom(call: call, result: result)
        case "stopPreview":
            stopPreview()
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    /**
     * 初始化预览（不包含推流）
     * 类似于 Android 的实现，将参数包装后调用 initialize 方法
     */
    private func initializePreview(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        // 创建一个包含预览参数的 Map，然后用 FlutterMethodCall 构造函数
        let previewArgs: [String: Any] = [
            "rtmpUrl": "",  // 空 URL，跳过 RTMP 初始化
            "width": args["width"] as? Int ?? 1920,
            "height": args["height"] as? Int ?? 1080,
            "bitrate": 2_000_000,  // 默认码率（预览时不重要）
            "fps": args["fps"] as? Int ?? 30,
            "enableAudio": false,  // 预览时不需要音频
            "isPortrait": args["isPortrait"] as? Bool ?? false,
            "initialCameraFacing": args["initialCameraFacing"] as? String ?? "front"
        ]
        let previewCall = FlutterMethodCall(methodName: "initialize", arguments: previewArgs)
        
        initialize(call: previewCall, result: result)
    }
    
    private func initialize(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        let rtmpUrl = args["rtmpUrl"] as? String ?? ""
        let width = args["width"] as? Int ?? 1920
        let height = args["height"] as? Int ?? 1080
        let bitrate = args["bitrate"] as? Int ?? 2_000_000
        let fps = args["fps"] as? Int ?? 30
        let enableAudio = args["enableAudio"] as? Bool ?? true
        let isPortrait = args["isPortrait"] as? Bool ?? false
        let initialCameraFacing = args["initialCameraFacing"] as? String ?? "front"
        let scaleMode = args["scaleMode"] as? String ?? "fit"
        
        self.rtmpUrl = rtmpUrl
        // Force landscape dimensions for FBO to match Android "Always Landscape FBO"
        self.streamWidth = max(width, height)
        self.streamHeight = min(width, height)
        
        self.isPortrait = isPortrait
        self.isFront = initialCameraFacing == "front"
        self.scaleMode = scaleMode
        
        // Initialize CIContext
        let metalDevice = MTLCreateSystemDefaultDevice()
        self.ciContext = CIContext(mtlDevice: metalDevice!)
        
        // Request camera and microphone permissions
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                result(FlutterError(code: "CAMERA_PERMISSION_DENIED", message: "Camera permission denied", details: nil))
                return
            }
            
            if enableAudio {
                AVCaptureDevice.requestAccess(for: .audio) { audioGranted in
                    if audioGranted {
                        self.setupCapture(bitrate: bitrate, fps: fps, enableAudio: true, isFront: initialCameraFacing == "front", result: result)
                    } else {
                        result(FlutterError(code: "MICROPHONE_PERMISSION_DENIED", message: "Microphone permission denied", details: nil))
                    }
                }
            } else {
                self.setupCapture(bitrate: bitrate, fps: fps, enableAudio: false, isFront: initialCameraFacing == "front", result: result)
            }
        }
    }
    
    private func setupCapture(bitrate: Int, fps: Int, enableAudio: Bool, isFront: Bool, result: @escaping FlutterResult) {
        // Initialize video encoder with FORCED LANDSCAPE dimensions
        videoEncoder = VideoEncoder()
        guard let videoEncoder = videoEncoder, videoEncoder.initialize(width: self.streamWidth, height: self.streamHeight, bitrate: bitrate, fps: fps) else {
            result(FlutterError(code: "ENCODER_INIT_FAILED", message: "Failed to initialize video encoder", details: nil))
            return
        }
        
        // Initialize audio encoder
        if enableAudio {
            audioEncoder = AudioEncoder()
            if !(audioEncoder?.initialize() ?? false) {
                print("[BbRtmpPlugin] Failed to initialize audio encoder")
                audioEncoder = nil
            }
        }
        
        // Initialize RTMP streamer only if URL is not empty
        if !rtmpUrl.isEmpty {
            rtmpStreamer = RtmpStreamer()
            // 设置状态回调
            rtmpStreamer?.setStatusCallback { [weak self] status, error in
                self?.notifyStreamingStatus(status: status, error: error)
            }
            guard let rtmpStreamer = rtmpStreamer, rtmpStreamer.initialize(url: rtmpUrl, videoEncoder: videoEncoder, audioEncoder: audioEncoder) else {
                result(FlutterError(code: "RTMP_INIT_FAILED", message: "Failed to initialize RTMP", details: nil))
                return
            }
            
            // Set metadata with FORCED LANDSCAPE dimensions
            let audioSampleRate = audioEncoder?.getSampleRate() ?? 44100
            let audioChannels = audioEncoder?.getChannelCount() ?? 1
            rtmpStreamer.setMetadata(width: self.streamWidth, height: self.streamHeight, videoBitrate: bitrate, fps: fps, audioSampleRate: audioSampleRate, audioChannels: audioChannels)
            
            // Initialize bitrate controller
            bitrateController = BitrateController(videoEncoder: videoEncoder, rtmpStreamer: rtmpStreamer)
            bitrateController?.initialize(initialBitrate: bitrate, width: self.streamWidth, height: self.streamHeight)
        } else {
            // Empty URL: preview mode, set encoder callback later in startStreaming
            // Note: VideoEncoder will save SPS/PPS and notify when callback is set
        }
        
        // Setup camera
        setupCamera(isFront: isFront, width: self.streamWidth, height: self.streamHeight, fps: fps) { [weak self] success in
            guard let self = self else { return }
            if success {
                // Create texture for preview
                self.previewTexture = PreviewTexture(registry: self.textureRegistry!)
                result(self.previewTexture?.textureId ?? -1)
            } else {
                result(FlutterError(code: "CAMERA_SETUP_FAILED", message: "Failed to setup camera", details: nil))
            }
        }
    }
    
    private func applyConnectionSettings(output: AVCaptureVideoDataOutput) {
        guard let connection = output.connection(with: .video) else { return }
        
        if connection.isVideoOrientationSupported {
            // 使用 portraitUpsideDown 和 landscapeRight 来修正预览方向
            // ciimage 与 cvpixelbuffer 的坐标系 不同, 这里翻转一下
            if isPortrait {
                connection.videoOrientation = .portraitUpsideDown
            } else {
                connection.videoOrientation = .landscapeRight
            }
        }
        
        if connection.isVideoMirroringSupported {
            connection.automaticallyAdjustsVideoMirroring = false
            connection.isVideoMirrored = isFront
        }
    }
    
    private func setupCamera(isFront: Bool, width: Int, height: Int, fps: Int, completion: @escaping (Bool) -> Void) {
        let session = AVCaptureSession()
        session.sessionPreset = .high
        
        // Get camera device
        let deviceType: AVCaptureDevice.DeviceType = .builtInWideAngleCamera
        let position: AVCaptureDevice.Position = isFront ? .front : .back
        
        guard let camera = AVCaptureDevice.default(deviceType, for: .video, position: position) else {
            print("[BbRtmpPlugin] Camera not found")
            completion(false)
            return
        }
        
        currentCamera = camera
        
        // Configure camera
        do {
            try camera.lockForConfiguration()
            
            // Set frame rate
            let frameDuration = CMTime(value: 1, timescale: Int32(fps))
            camera.activeVideoMinFrameDuration = frameDuration
            camera.activeVideoMaxFrameDuration = frameDuration
            
            camera.unlockForConfiguration()
        } catch {
            print("[BbRtmpPlugin] Failed to configure camera: \(error)")
        }
        
        // Add video input
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            if session.canAddInput(input) {
                session.addInput(input)
                videoInput = input
            }
        } catch {
            print("[BbRtmpPlugin] Failed to add video input: \(error)")
            completion(false)
            return
        }
        
        // Add video output
        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        output.alwaysDiscardsLateVideoFrames = true
        
        let queue = DispatchQueue(label: "com.bb.rtmp.video")
        output.setSampleBufferDelegate(self, queue: queue)
        
        if session.canAddOutput(output) {
            session.addOutput(output)
            videoOutput = output
            
            // Set orientation and mirroring
            applyConnectionSettings(output: output)
        }
        
        captureSession = session
        
        // Start session
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
            
            // Re-apply orientation settings AFTER session starts for reliability
            DispatchQueue.main.async {
                if let output = self.videoOutput {
                    self.applyConnectionSettings(output: output)
                }
                completion(true)
            }
        }
    }
    
    private func startStreaming(call: FlutterMethodCall, result: @escaping FlutterResult) {
        // Get RTMP URL from arguments if provided
        var newRtmpUrl = rtmpUrl
        var enableAudio = true
        if let args = call.arguments as? [String: Any] {
            if let url = args["rtmpUrl"] as? String, !url.isEmpty {
                newRtmpUrl = url
                self.rtmpUrl = url
            }
            enableAudio = args["enableAudio"] as? Bool ?? true
        }
        
        // Check if RTMP URL is provided
        if newRtmpUrl.isEmpty {
            result(FlutterError(code: "NO_RTMP_URL", message: "RTMP URL not provided", details: nil))
            return
        }
        
        guard let videoEncoder = videoEncoder else {
            result(FlutterError(code: "NOT_INITIALIZED", message: "Video encoder not initialized", details: nil))
            return
        }
        
        // 立即返回，不等待连接完成
        result(nil)
        
        // 发送连接中状态
        notifyStreamingStatus(status: "connecting", error: nil)
        
        // 在后台线程中初始化 RTMP 连接
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            
            // Check if RTMP streamer needs to be initialized
            if self.rtmpStreamer == nil {
                // 初始化音频编码器（如果需要且未初始化）
                if enableAudio && self.audioEncoder == nil {
                    self.audioEncoder = AudioEncoder()
                    if !(self.audioEncoder?.initialize() ?? false) {
                        print("[BbRtmpPlugin] Failed to initialize audio encoder")
                        self.audioEncoder = nil
                    }
                }
                
                // Initialize RTMP streamer
                self.rtmpStreamer = RtmpStreamer()
                // 设置状态回调
                self.rtmpStreamer?.setStatusCallback { [weak self] status, error in
                    self?.notifyStreamingStatus(status: status, error: error)
                }
                guard let rtmpStreamer = self.rtmpStreamer, rtmpStreamer.initialize(url: newRtmpUrl, videoEncoder: videoEncoder, audioEncoder: self.audioEncoder) else {
                    self.notifyStreamingStatus(status: "failed", error: "Failed to initialize RTMP")
                    self.rtmpStreamer = nil
                    return
                }
                
                // Set metadata
                let audioSampleRate = self.audioEncoder?.getSampleRate() ?? 44100
                let audioChannels = self.audioEncoder?.getChannelCount() ?? 1
                let bitrate = self.bitrateController?.getCurrentBitrate() ?? 2_000_000
                rtmpStreamer.setMetadata(width: self.streamWidth, height: self.streamHeight, videoBitrate: bitrate, fps: 30, audioSampleRate: audioSampleRate, audioChannels: audioChannels)
                
                // Initialize bitrate controller
                self.bitrateController = BitrateController(videoEncoder: videoEncoder, rtmpStreamer: rtmpStreamer)
                self.bitrateController?.initialize(initialBitrate: bitrate, width: self.streamWidth, height: self.streamHeight)
            }
            
            self.rtmpStreamer?.start()
            self.bitrateController?.start()
            _ = self.audioEncoder?.start()
            
            // 发送连接成功状态
            self.notifyStreamingStatus(status: "connected", error: nil)
        }
    }
    
    /**
     * 通知推流状态变化
     */
    private func notifyStreamingStatus(status: String, error: String?) {
        guard let sink = statusEventSink else { return }
        // EventChannel 必须在主线程发送
        DispatchQueue.main.async { [weak self] in
            guard let self = self, let sink = self.statusEventSink else { return }
            var statusMap: [String: Any] = ["status": status]
            if let error = error {
                statusMap["error"] = error
            }
            sink(statusMap)
        }
    }
    
    private func stopStreaming(result: @escaping FlutterResult) {
        rtmpStreamer?.stop()
        bitrateController?.stop()
        audioEncoder?.stop()
        notifyStreamingStatus(status: "stopped", error: nil)
        result(nil)
    }
    
    /**
     * 停止预览并销毁 Texture
     */
    private func stopPreview() {
        print("[BbRtmpPlugin] stopPreview")
        captureSession?.stopRunning()
        // 不要把 captureSession 设为 nil，防止 release 时重复操作或导致其它问题
        // 但为了彻底停止，我们可以移除 input 和 output
        if let session = captureSession {
            session.beginConfiguration()
            if let input = videoInput {
                session.removeInput(input)
                videoInput = nil
            }
            if let output = videoOutput {
                session.removeOutput(output)
                videoOutput = nil
            }
            session.commitConfiguration()
        }
        captureSession = nil
        
        audioEncoder?.stop()
        
        previewTexture?.release()
        previewTexture = nil
    }
    
    private func switchCamera(result: @escaping FlutterResult) {
        guard let session = captureSession, let currentInput = videoInput else {
            result(FlutterError(code: "NOT_INITIALIZED", message: "Not initialized", details: nil))
            return
        }
        
        let currentPosition = currentCamera?.position ?? .back
        let newPosition: AVCaptureDevice.Position = currentPosition == .front ? .back : .front
        
        guard let newCamera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: newPosition) else {
            result(FlutterError(code: "CAMERA_NOT_FOUND", message: "Camera not found", details: nil))
            return
        }
        
        do {
            let newInput = try AVCaptureDeviceInput(device: newCamera)
            
            session.beginConfiguration()
            session.removeInput(currentInput)
            
            if session.canAddInput(newInput) {
                session.addInput(newInput)
                videoInput = newInput
                currentCamera = newCamera
                self.isFront = newPosition == .front
                
                // Re-apply settings for new camera
                if let output = self.videoOutput {
                    self.applyConnectionSettings(output: output)
                }
            }
            
            session.commitConfiguration()
            result(nil)
        } catch {
            result(FlutterError(code: "SWITCH_CAMERA_FAILED", message: "Failed to switch camera: \(error)", details: nil))
        }
    }
    
    private func changeResolution(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        let width = args["width"] as? Int ?? 1920
        let height = args["height"] as? Int ?? 1080
        
        // TODO: Implement resolution change
        // This requires stopping and restarting the encoder and capture session
        
        result(FlutterError(code: "NOT_IMPLEMENTED", message: "Resolution change not yet implemented", details: nil))
    }
    
    private func setBitrate(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        let bitrate = args["bitrate"] as? Int ?? 2_000_000
        bitrateController?.setBitrate(bitrate)
        result(nil)
    }
    
    private func getStatus(result: @escaping FlutterResult) {
        let isStreaming = rtmpStreamer?.isStreamingActive() ?? false
        let currentBitrate = bitrateController?.getCurrentBitrate() ?? 0
        
        let status: [String: Any] = [
            "isStreaming": isStreaming,
            "currentBitrate": currentBitrate,
            "fps": 30.0,
            "width": streamWidth,
            "height": streamHeight,
            "previewWidth": streamWidth,
            "previewHeight": streamHeight,
            "cameraId": currentCamera?.uniqueID ?? ""
        ]
        
        result(status)
    }
    
    private func release() {
        captureSession?.stopRunning()
        captureSession = nil
        
        bitrateController?.release()
        rtmpStreamer?.release()
        videoEncoder?.release()
        audioEncoder?.release()
        
        previewTexture?.release()
        previewTexture = nil
        
        ciContext = nil
        fboBufferPool = nil
        
        // 清理活跃的 buffer 引用
        bufferQueueLock.lock()
        for buffer in activeBuffers {
            CVPixelBufferUnlockBaseAddress(buffer, CVPixelBufferLockFlags.readOnly)
            // Swift 会自动释放引用（当从数组中移除时）
        }
        activeBuffers.removeAll()
        bufferTimestamps.removeAll()
        bufferQueueLock.unlock()
        
        bitrateController = nil
        rtmpStreamer = nil
        videoEncoder = nil
        audioEncoder = nil
    }
    
    // MARK: - Background Handling
    
    @objc private func appDidEnterBackground() {
        print("[BbRtmpPlugin] App entered background")
        isInBackground = true
        
        if rtmpStreamer?.isStreamingActive() == true {
            beginBackgroundTask()
            // Start sending frozen video frames to keep server/player happy
            rtmpStreamer?.startHeartbeat()
        }
    }
    
    @objc private func appWillEnterForeground() {
        print("[BbRtmpPlugin] App will enter foreground")
        isInBackground = false
        endBackgroundTask()
        
        // Stop background heartbeat
        rtmpStreamer?.stopHeartbeat()
        
        // Force a keyframe to recover video quickly
        if rtmpStreamer?.isStreamingActive() == true {
            videoEncoder?.requestKeyFrame()
        }
    }
    
    private func beginBackgroundTask() {
        guard backgroundTaskID == .invalid else { return }
        
        backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "BbRtmpStreaming") { [weak self] in
            print("[BbRtmpPlugin] Background task expired")
            self?.endBackgroundTask()
        }
        
        print("[BbRtmpPlugin] Background task started: \(backgroundTaskID)")
    }
    
    private func endBackgroundTask() {
        if backgroundTaskID != .invalid {
            print("[BbRtmpPlugin] Ending background task: \(backgroundTaskID)")
            UIApplication.shared.endBackgroundTask(backgroundTaskID)
            backgroundTaskID = .invalid
        }
    }
    
    // MARK: - Frame Callback Methods
    
    private func enableFrameCallback(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        let enable = args["enable"] as? Bool ?? false
        let skipFrame = args["skipFrame"] as? Int ?? 0
        enableFrameCallback = enable
        frameSkip = max(0, skipFrame) // 确保 >= 0
        result(enable)
    }
    
    private func releasePixelBufferHandle(call: FlutterMethodCall, result: @escaping FlutterResult) {
        // iOS 上使用环形 buffer 池，buffer 会自动清理（FIFO + 超时）
        // 但为了兼容 Android API，可以手动清理最旧的 buffer
        bufferQueueLock.lock()
        if !activeBuffers.isEmpty {
            let oldBuffer = activeBuffers.removeFirst()
            CVPixelBufferUnlockBaseAddress(oldBuffer, CVPixelBufferLockFlags.readOnly)
            bufferTimestamps.removeValue(forKey: oldBuffer)
            // Swift 会自动释放引用（当从数组中移除时）
        }
        bufferQueueLock.unlock()
        result(true)
    }
    
    // MARK: - Zoom Control
    
    private func getZoomRange(result: @escaping FlutterResult) {
        guard let camera = currentCamera else {
            result(FlutterError(code: "NOT_INITIALIZED", message: "相机未初始化", details: nil))
            return
        }
        
        let minZoom = CGFloat(1.0)
        let maxZoom = camera.activeFormat.videoMaxZoomFactor
        let currentZoom = camera.videoZoomFactor
        
        let zoomRange: [String: Any] = [
            "minZoom": minZoom,
            "maxZoom": maxZoom,
            "currentZoom": currentZoom
        ]
        
        result(zoomRange)
    }
    
    private func setZoom(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        guard let zoom = args["zoom"] as? Double else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "zoom 参数无效", details: nil))
            return
        }
        
        guard let camera = currentCamera else {
            result(FlutterError(code: "NOT_INITIALIZED", message: "相机未初始化", details: nil))
            return
        }
        
        let minZoom = CGFloat(1.0)
        let maxZoom = camera.activeFormat.videoMaxZoomFactor
        let zoomValue = CGFloat(zoom).clamped(to: minZoom...maxZoom)
        
        do {
            try camera.lockForConfiguration()
            camera.videoZoomFactor = zoomValue
            camera.unlockForConfiguration()
            
            print("[BbRtmpPlugin] Zoom set to: \(zoomValue) (range: \(minZoom) - \(maxZoom))")
            result(true)
        } catch {
            print("[BbRtmpPlugin] Failed to set zoom: \(error)")
            result(FlutterError(code: "SET_ZOOM_FAILED", message: "设置 zoom 失败: \(error.localizedDescription)", details: nil))
        }
    }
    
    /// 清理过期的 buffer（超时自动清理，防止内存泄漏）
    private func cleanupExpiredBuffers() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        bufferQueueLock.lock()
        let expiredBuffers = activeBuffers.filter { buffer in
            if let timestamp = bufferTimestamps[buffer], now - timestamp > bufferMaxAgeMs {
                return true
            }
            return false
        }
        for buffer in expiredBuffers {
            CVPixelBufferUnlockBaseAddress(buffer, CVPixelBufferLockFlags.readOnly)
            if let index = activeBuffers.firstIndex(where: { $0 === buffer }) {
                activeBuffers.remove(at: index)
            }
            bufferTimestamps.removeValue(forKey: buffer)
        }
        bufferQueueLock.unlock()
    }
}

// MARK: - FlutterStreamHandler

// MARK: - FlutterStreamHandler for frame EventChannel

extension BbRtmpPlugin: FlutterStreamHandler {
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        // 这个方法只处理 frame EventChannel
        frameEventSink = events
        enableFrameCallback = true
        frameCounter = 0
        fboLoopStartTime = 0 // 重置开始时间
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        frameEventSink = nil
        enableFrameCallback = false
        frameCounter = 0
        fboLoopStartTime = 0
        return nil
    }
}

// MARK: - AVCaptureVideoDataOutputSampleBufferDelegate

extension BbRtmpPlugin: AVCaptureVideoDataOutputSampleBufferDelegate {
    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        
        // Get presentation time
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let presentationTimeUs = Int64(CMTimeGetSeconds(presentationTime) * 1_000_000)
        
        // 1. Skip everything video-related in background to avoid crashes (GPU/Encoder restricted)
        if isInBackground {
            return
        }
        
        // 2. Render into FBO (GPU work)
        guard let targetBuffer = renderToFbo(pixelBuffer) else { return }
        
        // 3. Send FBO RGBA data if callback is enabled
        // 优化：只在需要时处理，减少性能开销
        if enableFrameCallback && frameEventSink != nil {
            // 初始化开始时间（第一次调用时）
            if fboLoopStartTime == 0 {
                fboLoopStartTime = Int64(Date().timeIntervalSince1970 * 1000)
            }
            
            frameCounter += 1
            let currentTime = Int64(Date().timeIntervalSince1970 * 1000)
            let elapsedMs = currentTime - fboLoopStartTime
            let pastWarmup = elapsedMs >= fboReadWarmupMs
            
            // 跳帧：根据 skipFrame 参数决定（0 表示不跳帧，每帧都回调）
            // skipFrame = 0: 每帧都回调
            // skipFrame = n: 每隔 n 帧回调一次（第 1, n+2, 2n+3... 帧）
            let shouldReadForYolo = pastWarmup && (frameSkip == 0 || ((frameCounter - 1) % Int64(frameSkip + 1) == 0))
            
            if shouldReadForYolo {
                // 异步发送，不阻塞当前线程
                sendFboRgbaData(pixelBuffer: targetBuffer)
            }
        }
        
        // 4. Encode frame (Hardware Encoder work)
        videoEncoder?.encodeFrame(pixelBuffer: targetBuffer, presentationTimeUs: presentationTimeUs)
        
        // 5. Update preview texture
        DispatchQueue.main.async { [weak self] in
            self?.previewTexture?.updateBuffer(targetBuffer)
        }
    }
    
    private func sendFboRgbaData(pixelBuffer: CVPixelBuffer) {
        // 零拷贝实现：直接使用传入的 buffer 地址
        // 由于使用了 IOSurface，地址在 buffer 生命周期内稳定
        // 通过保持 buffer 引用（环形池），防止被重用
        
        // 1. 清理过期的 buffer（超时自动清理）
        cleanupExpiredBuffers()
        
        // 2. 保持 buffer 引用（防止被重用）
        // Swift 中 CVPixelBuffer 是自动内存管理的，只需保持引用即可
        bufferQueueLock.lock()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        bufferTimestamps[pixelBuffer] = now
        
        // 环形池：如果已满，移除最旧的 buffer（FIFO）
        if activeBuffers.count >= maxActiveBuffers {
            let oldBuffer = activeBuffers.removeFirst()
            // 解锁并清理最旧的 buffer
            CVPixelBufferUnlockBaseAddress(oldBuffer, CVPixelBufferLockFlags.readOnly)
            bufferTimestamps.removeValue(forKey: oldBuffer)
        }
        activeBuffers.append(pixelBuffer)
        bufferQueueLock.unlock()
        
        // 2. 锁定 buffer 并获取地址
        let lockStatus = CVPixelBufferLockBaseAddress(pixelBuffer, CVPixelBufferLockFlags.readOnly)
        guard lockStatus == kCVReturnSuccess else {
            print("[BbRtmpPlugin] 锁定 CVPixelBuffer 失败: \(lockStatus)")
            bufferQueueLock.lock()
            if let index = activeBuffers.firstIndex(where: { $0 === pixelBuffer }) {
                activeBuffers.remove(at: index)
            }
            bufferTimestamps.removeValue(forKey: pixelBuffer)
            bufferQueueLock.unlock()
            return
        }
        
        // 3. 获取地址（IOSurface 地址在 buffer 生命周期内稳定）
        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else {
            CVPixelBufferUnlockBaseAddress(pixelBuffer, CVPixelBufferLockFlags.readOnly)
            bufferQueueLock.lock()
            if let index = activeBuffers.firstIndex(where: { $0 === pixelBuffer }) {
                activeBuffers.remove(at: index)
            }
            bufferTimestamps.removeValue(forKey: pixelBuffer)
            bufferQueueLock.unlock()
            return
        }
        
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        
        // 4. 转换地址（零拷贝：直接使用 IOSurface 地址）
        let address = UInt64(bitPattern: Int64(Int(bitPattern: baseAddress)))
        
        // 5. 发送数据（保持 buffer 锁定和引用，直到不再需要）
        // 注意：EventChannel 必须在主线程发送，否则会导致数据丢失或崩溃
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                // self 已释放，清理 buffer
                CVPixelBufferUnlockBaseAddress(pixelBuffer, CVPixelBufferLockFlags.readOnly)
                return
            }
            
            guard let sink = self.frameEventSink else {
                // 如果发送失败，清理 buffer
                CVPixelBufferUnlockBaseAddress(pixelBuffer, CVPixelBufferLockFlags.readOnly)
                self.bufferQueueLock.lock()
                if let index = self.activeBuffers.firstIndex(where: { $0 === pixelBuffer }) {
                    self.activeBuffers.remove(at: index)
                }
                self.bufferTimestamps.removeValue(forKey: pixelBuffer)
                self.bufferQueueLock.unlock()
                return
            }
            
            // 在主线程发送数据（Flutter 要求）
            let data: [String: Any] = [
                "type": "fbo_rgba",
                "address": address,
                "width": width,
                "height": height,
                "stride": bytesPerRow
            ]
            
            sink(data)
            
            // 注意：保持 buffer 锁定和引用（通过 activeBuffers 数组）
            // buffer 会在队列满时自动清理（FIFO + 超时），或通过 releasePixelBufferHandle 手动释放
            // 由于使用了 IOSurface，地址在 buffer 生命周期内保持稳定（零拷贝）
            // Swift 会自动管理内存，当 activeBuffers 移除引用时，buffer 会被自动释放
        }
    }
    
    private func renderToFbo(_ pixelBuffer: CVPixelBuffer) -> CVPixelBuffer? {
        guard let context = ciContext else { return nil }
        
        let targetWidth = CGFloat(streamWidth)
        let targetHeight = CGFloat(streamHeight)
        
        // Create pool if needed
        if fboBufferPool == nil || poolWidth != streamWidth || poolHeight != streamHeight {
            let attributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: streamWidth,
                kCVPixelBufferHeightKey as String: streamHeight,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:]
            ]
            CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &fboBufferPool)
            poolWidth = streamWidth
            poolHeight = streamHeight
        }
        
        var outputBuffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, fboBufferPool!, &outputBuffer)
        guard let outBuffer = outputBuffer else { return nil }
        
        var ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        
        // 1. Hardware already handled orientation. Reset origin to (0,0).
        ciImage = ciImage.transformed(by: CGAffineTransform(translationX: -ciImage.extent.origin.x, y: -ciImage.extent.origin.y))
        
        // 2. Scale and Center
        let contentWidth = ciImage.extent.width
        let contentHeight = ciImage.extent.height
        
        let scaleX = targetWidth / contentWidth
        let scaleY = targetHeight / contentHeight
        
        // Scale Mode: FIT (min) or COVER (max)
        let scale = (scaleMode == "cover") ? max(scaleX, scaleY) : min(scaleX, scaleY)
        
        let finalWidth = contentWidth * scale
        let finalHeight = contentHeight * scale
        
        let tx = (targetWidth - finalWidth) / 2
        let ty = (targetHeight - finalHeight) / 2
        
        ciImage = ciImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale).translatedBy(x: tx / scale, y: ty / scale))
        
        // 3. Composite over black background
        let blackBg = CIImage(color: CIColor.black).cropped(to: CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight))
        let finalImage = ciImage.composited(over: blackBg)
        
        // 4. Render to buffer
        context.render(finalImage, to: outBuffer, bounds: CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight), colorSpace: CGColorSpaceCreateDeviceRGB())
        
        return outBuffer
    }
}

// MARK: - StatusStreamHandler for status EventChannel

private class StatusStreamHandler: NSObject, FlutterStreamHandler {
    weak var plugin: BbRtmpPlugin?
    let isFrameChannel: Bool
    
    init(plugin: BbRtmpPlugin, isFrameChannel: Bool) {
        self.plugin = plugin
        self.isFrameChannel = isFrameChannel
    }
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        if isFrameChannel {
            // Frame channel - set event sink and enable callback
            plugin?.setFrameEventSink(events)
        } else {
            // Status channel
            plugin?.setStatusEventSink(events)
        }
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        if isFrameChannel {
            plugin?.setFrameEventSink(nil)
        } else {
            plugin?.setStatusEventSink(nil)
        }
        return nil
    }
}

// MARK: - CGFloat Extension for Clamping

extension CGFloat {
    func clamped(to range: ClosedRange<CGFloat>) -> CGFloat {
        return Swift.max(range.lowerBound, Swift.min(range.upperBound, self))
    }
}
