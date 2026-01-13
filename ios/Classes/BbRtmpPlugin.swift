import Flutter
import UIKit
import AVFoundation
import VideoToolbox
import CoreVideo
import Accelerate

public class BbRtmpPlugin: NSObject, FlutterPlugin {
    private var channel: FlutterMethodChannel?
    private var textureRegistry: FlutterTextureRegistry?
    
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
    
    // Background handling
    private var isInBackground: Bool = false
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.bb.rtmp/plugin", binaryMessenger: registrar.messenger())
        let instance = BbRtmpPlugin()
        instance.channel = channel
        instance.textureRegistry = registrar.textures()
        registrar.addMethodCallDelegate(instance, channel: channel)
        
        // Ignore SIGPIPE to prevent app from being killed when writing to a broken socket
        signal(SIGPIPE, SIG_IGN)
        
        // Register for lifecycle notifications
        NotificationCenter.default.addObserver(instance, selector: #selector(instance.appDidEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(instance, selector: #selector(instance.appWillEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "initialize":
            initialize(call: call, result: result)
        case "startStreaming":
            startStreaming(result: result)
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
        default:
            result(FlutterMethodNotImplemented)
        }
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
        
        // Initialize RTMP streamer
        rtmpStreamer = RtmpStreamer()
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
            connection.videoOrientation = isPortrait ? .portrait : .landscapeLeft
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
    
    private func startStreaming(result: @escaping FlutterResult) {
        rtmpStreamer?.start()
        bitrateController?.start()
        result(nil)
    }
    
    private func stopStreaming(result: @escaping FlutterResult) {
        rtmpStreamer?.stop()
        bitrateController?.stop()
        result(nil)
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
        
        // 3. Encode frame (Hardware Encoder work)
        videoEncoder?.encodeFrame(pixelBuffer: targetBuffer, presentationTimeUs: presentationTimeUs)
        
        // 4. Update preview texture
        DispatchQueue.main.async { [weak self] in
            self?.previewTexture?.updateBuffer(targetBuffer)
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
