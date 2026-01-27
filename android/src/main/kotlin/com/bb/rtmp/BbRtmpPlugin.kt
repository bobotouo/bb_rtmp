package com.bb.rtmp

import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import com.bb.rtmp.NativeBridge
import android.view.Surface
import android.view.TextureView
import android.view.View
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BbRtmpPlugin : FlutterPlugin, MethodCallHandler, io.flutter.embedding.engine.plugins.activity.ActivityAware {
    private lateinit var channel: MethodChannel
    private var textureRegistry: TextureRegistry? = null
    private var context: Context? = null
    private var activity: android.app.Activity? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isInBackground = false

    private var cameraController: CameraController? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var rtmpStreamer: RtmpStreamer? = null
    private var bitrateController: BitrateController? = null
    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var rtmpUrl: String = ""
    // 保存推流分辨率（用户设置的分辨率，不匹配硬件）
    private var streamWidth: Int = 0
    private var streamHeight: Int = 0
    // 保存相机实际输出分辨率（用于预览宽高比计算）
    private var cameraOutputWidth: Int = 0
    private var cameraOutputHeight: Int = 0
    // 保存用户设置的方向和分辨率（用于预览宽高比计算）
    private var isPortraitMode: Boolean = false
    private var userSetWidth: Int = 0  // 用户设置的分辨率宽度（用于预览显示）
    private var userSetHeight: Int = 0  // 用户设置的分辨率高度（用于预览显示）
    // OpenGL 渲染器（用于 FBO 旋转和裁剪）
    private var glRenderer: GlRenderer? = null
    private var cameraTextureId: Int = 0
    // 独立的 SurfaceTexture 用于 FBO 输入（相机输出）
    private var fboInputSurfaceTexture: SurfaceTexture? = null
    // 预览 SurfaceTexture（FBO 输出，用于 Flutter 显示）
    private var previewSurfaceTexture: SurfaceTexture? = null
    // 保存 OpenGL 初始化参数（用于在渲染线程中初始化）
    private var glEncoderSurface: Surface? = null
    private var glFboCanvasWidth: Int = 0
    private var glFboCanvasHeight: Int = 0
    private var glCameraWidth: Int = 0
    private var glCameraHeight: Int = 0
    // FBO 渲染循环控制
    private var isFboRenderLoopRunning = false
    private var fboRenderJob: kotlinx.coroutines.Job? = null
    // 时间戳管理（用于确保时间戳递增，避免编码器丢帧）
    private var lastTimestampNs: Long = 0
    
    // NV12 帧数据句柄传递相关
    private var frameEventChannel: EventChannel? = null
    private var frameEventSink: EventChannel.EventSink? = null
    
    // 推流状态通知相关
    private var statusEventChannel: EventChannel? = null
    private var statusEventSink: EventChannel.EventSink? = null
    private var enableFrameCallback: Boolean = false
    private var frameSkip: Int = 0 // 跳帧数，0 表示不跳帧（每帧都回调）
    private var imageReader: ImageReader? = null
    private var imageReaderSurface: Surface? = null
    // 句柄映射表：句柄ID -> HardwareBuffer
    private val handleToBuffer = ConcurrentHashMap<Long, HardwareBuffer>()
    // 句柄映射表：句柄ID -> Image（需要保存以便释放时关闭）
    private val handleToImage = ConcurrentHashMap<Long, Image>()
    // 句柄映射表：句柄ID -> AHardwareBuffer* 指针（零拷贝）
    private val handleToNativePtr = ConcurrentHashMap<Long, Long>()
    // 句柄计数器（从大于 Int 范围的值开始，确保通过 MethodChannel 传递为 Long）
    private var handleCounter: Long = 1L shl 33
    private val handleLock = Any()

    // ImageReader 回调监听器
    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        if (!this.enableFrameCallback || frameEventSink == null) {
            // 如果回调未启用，直接关闭 Image
            reader.acquireLatestImage()?.close()
            return@OnImageAvailableListener
        }
        
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        
        try {
            // 检查 Android 版本，HardwareBuffer 需要 API 26+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val hardwareBuffer = image.hardwareBuffer
                if (hardwareBuffer != null) {
                    // 如果缓存中的帧已接近 ImageReader 上限，直接丢弃本帧，避免占满导致崩溃
                    val currentCached = handleToImage.size
                    val maxImages = reader.maxImages.coerceAtLeast(4)
                    if (currentCached >= maxImages - 1) {
                        // 丢弃本帧，释放资源
                        try {
                            image.close()
                        } catch (_: Exception) {}
                        return@OnImageAvailableListener
                    }

                    // 生成唯一句柄，存入前做简单的淘汰，避免占满 maxImages
                    val handle = synchronized(handleLock) {
                        // 如果已接收的帧数接近 ImageReader 的上限，清理最旧的帧
                        val limit = (reader.maxImages * 2).coerceAtLeast(4) // 保留一些冗余
                        if (handleToImage.size >= limit) {
                            // 移除最旧的一个
                            val oldestKey = handleToImage.keys.minOrNull()
                            if (oldestKey != null) {
                                try {
                                    handleToNativePtr.remove(oldestKey)?.let {
                                        try { NativeBridge.unlockAHardwareBuffer(it) } catch (_: Exception) {}
                                        try { NativeBridge.releaseAHardwareBufferPtr(it) } catch (_: Exception) {}
                                    }
                                    handleToBuffer.remove(oldestKey)?.close()
                                    handleToImage.remove(oldestKey)?.close()
                                } catch (_: Exception) {}
                            }
                        }

                        handleCounter++
                        handleToBuffer[handleCounter] = hardwareBuffer
                        handleToImage[handleCounter] = image
                        handleCounter
                    }
                    
                    // 获取图像信息
                    val width = image.width
                    val height = image.height
                    val timestamp = System.currentTimeMillis() * 1000 // 微秒
                    val pixelFormat = image.format
                    
                    // 通过 EventChannel 发送句柄信息
                    val eventData = mapOf(
                        "type" to "nv12_handle",
                        "handle" to handle,
                        "width" to width,
                        "height" to height,
                        "timestamp" to timestamp,
                        "pixelFormat" to pixelFormat
                    )
                    
                    // 注意：Android 的 EventChannel.EventSink 必须在主线程调用（与 iOS 不同）
                    // 但我们可以先切换到主线程，避免阻塞当前线程
                    scope.launch(Dispatchers.Main) {
                        frameEventSink?.success(eventData)
                    }
                } else {
                    android.util.Log.w("BbRtmpPlugin", "Image 没有 HardwareBuffer（API < 26 或格式不支持）")
                    image.close()
                }
            } else {
                android.util.Log.w("BbRtmpPlugin", "HardwareBuffer 需要 Android 8.0+，当前版本: ${android.os.Build.VERSION.SDK_INT}")
                image.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "处理 Image 失败", e)
            // 确保异常情况下也释放 Image
            try {
                image.close()
            } catch (closeException: Exception) {
                android.util.Log.e("BbRtmpPlugin", "关闭 Image 失败", closeException)
            }
        }
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.bb.rtmp/plugin")
        channel.setMethodCallHandler(this)
        textureRegistry = flutterPluginBinding.textureRegistry
        context = flutterPluginBinding.applicationContext
        
        // 注册 EventChannel 用于传递帧数据句柄
        frameEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.bb.rtmp/frames")
        frameEventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                frameEventSink = events
                enableFrameCallback = true
            }
            
            override fun onCancel(arguments: Any?) {
                frameEventSink = null
                enableFrameCallback = false
                // 清理所有未释放的句柄
                synchronized(handleLock) {
                    // 先关闭所有 Image
                    handleToImage.values.forEach { image ->
                        try {
                            image.close()
                        } catch (e: Exception) {
                            android.util.Log.w("BbRtmpPlugin", "关闭 Image 失败", e)
                        }
                    }
                    handleToImage.clear()
                    
                    // 释放 AHardwareBuffer 指针（先解锁再释放）
                    handleToNativePtr.values.forEach { ptr ->
                        if (ptr != 0L) {
                            try {
                                NativeBridge.unlockAHardwareBuffer(ptr)
                            } catch (e: Exception) {
                                android.util.Log.w("BbRtmpPlugin", "解锁 AHardwareBuffer 失败", e)
                            }
                            try {
                                NativeBridge.releaseAHardwareBufferPtr(ptr)
                            } catch (e: Exception) {
                                android.util.Log.w("BbRtmpPlugin", "释放 AHardwareBuffer 指针失败", e)
                            }
                        }
                    }
                    handleToNativePtr.clear()

                    // 然后关闭所有 HardwareBuffer
                    handleToBuffer.values.forEach { buffer ->
                        try {
                            buffer.close()
                        } catch (e: Exception) {
                            android.util.Log.w("BbRtmpPlugin", "释放 HardwareBuffer 失败", e)
                        }
                    }
                    handleToBuffer.clear()
                }
            }
        })
        
        // 注册 EventChannel 用于推流状态通知
        statusEventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "com.bb.rtmp/status")
        statusEventChannel?.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                statusEventSink = events
            }

            override fun onCancel(arguments: Any?) {
                statusEventSink = null
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        release()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initializePreview" -> {
                scope.launch {
                    initializePreview(call, result)
                }
            }
            "initialize" -> {
                scope.launch {
                    initialize(call, result)
                }
            }
            "startStreaming" -> {
                startStreaming(call, result)
            }
            "stopStreaming" -> {
                stopStreaming(result)
            }
            "release" -> {
                release()
                result.success(null)
            }
            "switchCamera" -> {
                scope.launch {
                    switchCamera(result)
                }
            }
            "changeResolution" -> {
                scope.launch {
                    changeResolution(call, result)
                }
            }
            "setBitrate" -> {
                setBitrate(call, result)
            }
            "getStatus" -> {
                getStatus(result)
            }
            "enableFrameCallback" -> {
                enableFrameCallback(call, result)
            }
            "releasePixelBufferHandle" -> {
                releasePixelBufferHandle(call, result)
            }
            "getHardwareBufferNativeHandle" -> {
                getHardwareBufferNativeHandle(call, result)
            }
            "getImagePlanes" -> {
                getImagePlanes(call, result)
            }
            "getZoomRange" -> {
                getZoomRange(result)
            }
            "setZoom" -> {
                setZoom(call, result)
            }
            "stopPreview" -> {
                stopPreview()
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private suspend fun initialize(call: MethodCall, result: Result) {
        var resultReplied = false // 防止重复提交 Result
        
        try {
            val rtmpUrl = call.argument<String>("rtmpUrl") ?: ""
            var streamWidth = call.argument<Int>("width") ?: 1920
            var streamHeight = call.argument<Int>("height") ?: 1080
            val bitrate = call.argument<Int>("bitrate") ?: 2000000
            val fps = call.argument<Int>("fps") ?: 30
            val enableAudio = call.argument<Boolean>("enableAudio") ?: true
            val isPortrait = call.argument<Boolean>("isPortrait") ?: false
            val initialCameraFacing = call.argument<String>("initialCameraFacing") ?: "front"
            
            val ctx = context ?: return
            val registry = textureRegistry ?: return

            // 1. 初始化相机控制器
            cameraController = CameraController(ctx)
            cameraController!!.setInitialCameraFacing(initialCameraFacing == "front")
            val cameras = cameraController!!.getAvailableCameras()
            if (cameras.isEmpty()) {
                result.error("NO_CAMERA", "设备无相机", null)
                return
            }
            
            // 默认选择前置或后置
            val isFront = cameraController!!.isFrontFacing()
            val targetCameraId = cameras.find { id ->
                val characteristics = ctx.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id)
                val facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (isFront) facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT 
                else facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: cameras[0]

            // 2. 查找硬件支持的最佳分辨率（用于相机纹理）
            // 如果用户设置的分辨率超出相机支持，使用最大支持的分辨率
            val hardwareSize = cameraController!!.selectBestResolution(streamWidth, streamHeight, targetCameraId)
            val maxSize = cameraController!!.getMaxSupportedResolution(targetCameraId)
            
            // 如果选择的分辨率大于最大支持的分辨率，使用最大支持的分辨率
            val finalHardwareSize = if (hardwareSize != null && maxSize != null && 
                (hardwareSize.width * hardwareSize.height > maxSize.width * maxSize.height)) {
                maxSize
            } else {
                hardwareSize ?: maxSize ?: android.util.Size(streamWidth, streamHeight)
            }
            
            val cameraWidth = finalHardwareSize.width
            val cameraHeight = finalHardwareSize.height
            
            // 保存相机输出分辨率
            this.cameraOutputWidth = cameraWidth
            this.cameraOutputHeight = cameraHeight
            
            // 3. 保存用户设置的方向和分辨率（用于预览宽高比显示）
            this.isPortraitMode = isPortrait
            this.userSetWidth = streamWidth  // 保存用户设置的分辨率（用于预览显示）
            this.userSetHeight = streamHeight
            
            // 4. FBO 画布分辨率 = 标准横屏分辨率（始终宽>=高，如 1920x1080）
            // 推流和预览 Surface 都使用标准横屏分辨率
            val fboCanvasWidth = kotlin.math.max(streamWidth, streamHeight)  // 标准横屏：宽 >= 高
            val fboCanvasHeight = kotlin.math.min(streamWidth, streamHeight)
            
            // 保存推流分辨率（标准横屏分辨率）
            this.streamWidth = fboCanvasWidth
            this.streamHeight = fboCanvasHeight
            

            // 5. 初始化编码器（使用标准横屏分辨率）
            videoEncoder = VideoEncoder()
            val encoderSurface = videoEncoder!!.initialize(fboCanvasWidth, fboCanvasHeight, bitrate, fps)
            if (encoderSurface == null) {
                result.error("ENCODER_INIT_FAILED", "视频编码器初始化失败", null)
                resultReplied = true
                return
            }

            if (enableAudio) {
                audioEncoder = AudioEncoder()
                audioEncoder!!.initialize()
            }

            this.rtmpUrl = rtmpUrl
            
            // 只在 RTMP URL 不为空时初始化推流器
            if (rtmpUrl.isNotEmpty()) {
                rtmpStreamer = RtmpStreamer()
                // 设置状态回调
                rtmpStreamer!!.setStatusCallback(object : RtmpStreamer.StatusCallback {
                    override fun onStatus(status: String, error: String?) {
                        notifyStreamingStatus(status, error)
                    }
                })
                if (!rtmpStreamer!!.initialize(rtmpUrl, videoEncoder!!, audioEncoder)) {
                    result.error("RTMP_INIT_FAILED", "RTMP 初始化失败", null)
                    resultReplied = true
                    return
                }

                // 设置 RTMP 元数据（使用标准横屏分辨率，即编码器实际输出的分辨率）
                rtmpStreamer!!.setMetadata(fboCanvasWidth, fboCanvasHeight, bitrate, fps, 44100, 1)
            }

            // 7. 初始化 OpenGL 渲染器（延迟到渲染线程中初始化）
            // 在这里只创建 GlRenderer 对象，EGL 初始化在渲染线程中进行
            glRenderer = GlRenderer()
            
            // 保存初始化参数，稍后在渲染线程中使用（作为类成员变量）
            this.glEncoderSurface = encoderSurface
            this.glFboCanvasWidth = fboCanvasWidth  // FBO 画布（标准横屏分辨率）
            this.glFboCanvasHeight = fboCanvasHeight
            this.glCameraWidth = cameraWidth
            this.glCameraHeight = cameraHeight
            

            // 7. 准备预览纹理（使用 FBO 画布分辨率，因为预览显示的是 FBO 输出）
            // Flutter 的 Texture 的 SurfaceTexture 将作为 FBO 的预览输出 Surface
            textureEntry = registry.createSurfaceTexture()
            val flutterPreviewTexture = textureEntry!!.surfaceTexture()
            flutterPreviewTexture.setDefaultBufferSize(fboCanvasWidth, fboCanvasHeight)
            
            // 保存预览 SurfaceTexture（FBO 输出，用于 Flutter 显示）
            // 直接使用 Flutter Texture 的 SurfaceTexture
            this.previewSurfaceTexture = flutterPreviewTexture
            
            // 创建 FBO 输入 SurfaceTexture（相机输出，用于 FBO 渲染）
            // 这个 SurfaceTexture 接收相机的原始输出
            fboInputSurfaceTexture = SurfaceTexture(false) // false 表示不在构造函数中创建纹理
            fboInputSurfaceTexture!!.setDefaultBufferSize(cameraWidth, cameraHeight)
            
            // 8. 创建 ImageReader 用于获取 NV12 帧数据（零拷贝）
            // 使用 YUV_420_888 格式，这是相机最常用的格式，包含 NV12/NV21 数据
            // 增加 maxImages 到 4，避免 AI 处理慢时占满缓冲区
            imageReader = ImageReader.newInstance(cameraWidth, cameraHeight, ImageFormat.YUV_420_888, 4)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, null)
            imageReaderSurface = imageReader?.surface
            
            // 9. 配置相机回调并打开
            cameraController!!.setStateCallback(object : CameraController.CameraStateCallback {
                override fun onCameraOpened() {
                    scope.launch(Dispatchers.Main) {
                        // 如果 FBO 未初始化（比如切换摄像头后），重新初始化
                        if (glRenderer == null || fboInputSurfaceTexture == null || previewSurfaceTexture == null) {
                            // 重要：只有在 fboInputSurfaceTexture 为 null 时才创建新的
                            // 如果是 switchCamera 后，fboInputSurfaceTexture 已经在 switchCamera 中创建并传给相机了
                            // 这里不应该重新创建，否则会导致相机输出到旧的 SurfaceTexture，而渲染循环绑定新的
                            if (fboInputSurfaceTexture == null) {
                                fboInputSurfaceTexture = SurfaceTexture(false)
                                fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                            } else {
                                // 如果已经存在，确保大小正确
                                fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                            }
                            
                            // 重新设置预览 SurfaceTexture 大小
                            val flutterTexture = textureEntry?.surfaceTexture()
                            if (flutterTexture != null) {
                                flutterTexture.setDefaultBufferSize(glFboCanvasWidth, glFboCanvasHeight)
                                previewSurfaceTexture = flutterTexture
                            }
                            
                            // 重新创建 GlRenderer
                            glRenderer?.release()
                            glRenderer = GlRenderer()
                            cameraTextureId = 0
                            
                            // 重要：在 onCameraOpened 回调中，不要重置时间戳！
                            // 因为：
                            // 1. 如果是首次初始化，lastTimestampNs 已经是 0，不需要重置
                            // 2. 如果是切换摄像头后，switchCamera 已经保持了时间戳连续，这里不应该重置
                            // 只有在首次调用 initialize 时，lastTimestampNs 才会是 0
                        }
                        
                        if (glRenderer != null && fboInputSurfaceTexture != null && previewSurfaceTexture != null) {
                            val encoderSurface = glEncoderSurface
                            if (encoderSurface != null) {
                                startFboRenderLoop(
                                    fboInputSurfaceTexture!!,
                                    previewSurfaceTexture!!,
                                    encoderSurface,
                                    glFboCanvasWidth,
                                    glFboCanvasHeight,
                                    glCameraWidth,
                                    glCameraHeight
                                )
                            } else {
                                android.util.Log.e("BbRtmpPlugin", "编码器 Surface 未初始化，无法启动渲染循环")
                                val surfaces = mutableListOf<Surface>()
                                imageReaderSurface?.let { surfaces.add(it) }
                                cameraController?.createCaptureSession(surfaces)
                            }
                        } else {
                            android.util.Log.e("BbRtmpPlugin", "FBO 未初始化，无法创建捕获会话")
                            val surfaces = mutableListOf<Surface>()
                            imageReaderSurface?.let { surfaces.add(it) }
                            cameraController?.createCaptureSession(surfaces)
                        }
                        
                        if (!resultReplied) {
                            result.success(textureEntry!!.id())
                            resultReplied = true
                        }
                    }
                }

                override fun onCameraError(error: String) {
                    if (!resultReplied) {
                        result.error("CAMERA_ERROR", error, null)
                        resultReplied = true
                    }
                }
            })

            // 只在 RTMP 推流器已初始化时创建码率控制器
            if (rtmpStreamer != null) {
                bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, cameraController!!)
                bitrateController!!.initialize(bitrate, fboCanvasWidth, fboCanvasHeight)
            }

            // 9. 打开相机
            cameraController!!.openCamera(cameraWidth, cameraHeight, fboInputSurfaceTexture!!)

        } catch (e: Exception) {
            if (!resultReplied) {
                result.error("INITIALIZE_ERROR", "初始化异常: ${e.message}", null)
                resultReplied = true
            }
        }
    }

    /**
     * 仅初始化预览（不包含推流配置）
     * 
     * 这个方法只初始化相机和预览，推流配置在 startStreaming 时进行
     */
    private suspend fun initializePreview(call: MethodCall, result: Result) {
        // 创建一个包含预览参数的 Map，然后用 MethodCall 构造函数
        val previewArgs = mapOf(
            "rtmpUrl" to "",  // 空 URL，跳过 RTMP 初始化
            "width" to (call.argument<Int>("width") ?: 1920),
            "height" to (call.argument<Int>("height") ?: 1080),
            "bitrate" to 2000000,  // 默认码率（预览时不重要）
            "fps" to (call.argument<Int>("fps") ?: 30),
            "enableAudio" to false,  // 预览时不需要音频
            "isPortrait" to (call.argument<Boolean>("isPortrait") ?: false),
            "initialCameraFacing" to (call.argument<String>("initialCameraFacing") ?: "front")
        )
        val previewCall = MethodCall("initializePreview", previewArgs)
        
        initialize(previewCall, result)
    }

    private fun startStreaming(call: MethodCall, result: Result) {
        try {
            // 检查是否已初始化预览
            if (glRenderer == null || cameraController == null || videoEncoder == null) {
                result.error("NOT_INITIALIZED", "请先调用 initialize 或 initializePreview", null)
                return
            }
            
            // 获取参数
            val newRtmpUrl = call.argument<String>("rtmpUrl")
            val newBitrate = call.argument<Int>("bitrate") ?: 2000000
            val enableAudio = call.argument<Boolean>("enableAudio") ?: true
            
            // 更新 RTMP URL
            if (newRtmpUrl != null && newRtmpUrl.isNotEmpty()) {
                this.rtmpUrl = newRtmpUrl
            }
            
            // 检查 RTMP URL
            if (rtmpUrl.isEmpty()) {
                result.error("NO_RTMP_URL", "未设置 RTMP 推流地址", null)
                return
            }
            
            // 立即返回，不等待连接完成
            result.success(null)
            
            // 发送连接中状态
            notifyStreamingStatus("connecting", null)
            
            // 在后台线程中初始化 RTMP 连接
            scope.launch(Dispatchers.IO) {
                try {
                    // 如果 RTMP 推流器未初始化，现在初始化
                    if (rtmpStreamer == null) {
                        // 初始化音频编码器（如果需要）
                        if (enableAudio && audioEncoder == null) {
                            audioEncoder = AudioEncoder()
                            audioEncoder!!.initialize()
                        }
                        
                        rtmpStreamer = RtmpStreamer()
                        // 设置状态回调
                        rtmpStreamer!!.setStatusCallback(object : RtmpStreamer.StatusCallback {
                            override fun onStatus(status: String, error: String?) {
                                notifyStreamingStatus(status, error)
                            }
                        })
                        if (!rtmpStreamer!!.initialize(rtmpUrl, videoEncoder!!, audioEncoder)) {
                            notifyStreamingStatus("failed", "RTMP 连接失败，请检查网络和推流地址")
                            rtmpStreamer = null
                            return@launch
                        }
                        
                        // 设置 RTMP 元数据
                        rtmpStreamer!!.setMetadata(glFboCanvasWidth, glFboCanvasHeight, newBitrate, 30, 44100, 1)
                        
                        // 初始化码率控制器
                        bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, cameraController!!)
                        bitrateController!!.initialize(newBitrate, glFboCanvasWidth, glFboCanvasHeight)
                    } else {
                        // RTMP 已初始化，直接使用现有配置
                    }
                    
                    // 更新码率（通过码率控制器）
                    if (newBitrate > 0) {
                        bitrateController?.setBitrate(newBitrate)
                    }
                    
                    rtmpStreamer?.start()
                    bitrateController?.start()
                    audioEncoder?.start()
                    
                    context?.let { ctx ->
                        val intent = android.content.Intent(ctx, RtmpService::class.java)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            ctx.startForegroundService(intent)
                        } else {
                            ctx.startService(intent)
                        }
                    }
                    
                    // 发送连接成功状态
                    notifyStreamingStatus("connected", null)
                } catch (e: Exception) {
                    android.util.Log.e("BbRtmpPlugin", "后台初始化 RTMP 失败", e)
                    notifyStreamingStatus("failed", "开始推流失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            result.error("START_STREAMING_ERROR", "开始推流失败: ${e.message}", null)
        }
    }
    
    /**
     * 通知推流状态变化
     */
    private fun notifyStreamingStatus(status: String, error: String?) {
        val sink = statusEventSink ?: return
        try {
            val statusMap = mutableMapOf<String, Any>(
                "status" to status
            )
            if (error != null) {
                statusMap["error"] = error
            }
            // EventChannel 必须在主线程调用
            scope.launch(Dispatchers.Main) {
                sink.success(statusMap)
            }
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "发送状态通知失败", e)
        }
    }

    private fun stopPreview() {
        android.util.Log.i("BbRtmpPlugin", "stopPreview")
        try {
            // 1. 停止 FBO 渲染循环
            stopFboRenderLoop()
            
            // 2. 停止相机
            cameraController?.closeCamera()
            
            // 3. 释放预览纹理
            textureEntry?.release()
            textureEntry = null
            previewSurfaceTexture = null
            
            // 4. 下面的资源在 release 时也会处理，但 stopPreview 应该优先清理预览相关的
            fboInputSurfaceTexture?.release()
            fboInputSurfaceTexture = null
            
            imageReaderSurface?.release()
            imageReaderSurface = null
            imageReader?.close()
            imageReader = null
            
            glRenderer?.release()
            glRenderer = null
            
            audioEncoder?.stop()
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "stopPreview failed", e)
        }
    }

    private fun stopStreaming(result: Result) {
        try {
            // 1. 先停止推流和码率控制
            rtmpStreamer?.stop()
            bitrateController?.stop()
            audioEncoder?.stop()
            
            // 2. 停止服务
            context?.let { ctx ->
                val intent = android.content.Intent(ctx, RtmpService::class.java)
                ctx.stopService(intent)
            }
            
            // 发送停止状态
            notifyStreamingStatus("stopped", null)
            
            result.success(null)
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "停止推流失败", e)
            result.error("STOP_STREAMING_ERROR", "停止推流失败: ${e.message}", null)
        }
    }

    private suspend fun switchCamera(result: Result) {
        try {
            val controller = cameraController ?: run {
                result.error("NOT_INITIALIZED", "未初始化", null)
                return
            }

            // 停止 FBO 渲染循环
            isFboRenderLoopRunning = false
            fboRenderJob?.join()
            fboRenderJob = null
            
            // 重要：切换摄像头时不要重置时间戳！
            // 因为编码器（MediaCodec）实例没有重新创建，它的 PTS 必须保持连续递增。
            // 如果重置为 0，编码器会因为时间戳倒退而丢弃所有后续帧，导致黑屏。

            // 释放旧的 ImageReader
            imageReaderSurface?.release()
            imageReaderSurface = null
            imageReader?.close()
            imageReader = null

            val oldSurfaceTexture = fboInputSurfaceTexture
            fboInputSurfaceTexture = null
            
            if (glRenderer != null) {
                glRenderer = null
            }
            cameraTextureId = 0
            
            oldSurfaceTexture?.release()

            fboInputSurfaceTexture = SurfaceTexture(false)
            
            // 使用当前已知的分辨率预设大小（会在相机打开后更新）
            val tempWidth = if (glCameraWidth > 0) glCameraWidth else userSetWidth
            val tempHeight = if (glCameraHeight > 0) glCameraHeight else userSetHeight
            fboInputSurfaceTexture!!.setDefaultBufferSize(tempWidth, tempHeight)
            
            imageReader = ImageReader.newInstance(tempWidth, tempHeight, ImageFormat.YUV_420_888, 4)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, null)
            imageReaderSurface = imageReader?.surface
            
            val success = controller.switchCamera(userSetWidth, userSetHeight, fboInputSurfaceTexture!!)
            
            if (success) {
                val newSize = controller.getPreviewSize()
                if (newSize != null) {
                    this.cameraOutputWidth = newSize.width
                    this.cameraOutputHeight = newSize.height
                    // 关键：同步更新渲染所需的相机分辨率，确保渲染循环使用正确的尺寸
                    this.glCameraWidth = newSize.width
                    this.glCameraHeight = newSize.height
                }
                videoEncoder?.requestKeyFrame()
                result.success(null)
            } else {
                result.error("SWITCH_CAMERA_FAILED", "切换摄像头失败", null)
            }
        } catch (e: Exception) {
            result.error("SWITCH_CAMERA_ERROR", "切换摄像头异常: ${e.message}", null)
        }
    }

    private suspend fun changeResolution(call: MethodCall, result: Result) {
        try {
            val targetWidth = call.argument<Int>("width") ?: 1920
            val targetHeight = call.argument<Int>("height") ?: 1080

            val controller = cameraController ?: run {
                result.error("NOT_INITIALIZED", "未初始化", null)
                return
            }

            val texture = textureEntry?.surfaceTexture() ?: run {
                result.error("NO_TEXTURE", "无纹理", null)
                return
            }

            val isPortrait = targetWidth < targetHeight
            this.isPortraitMode = isPortrait
            this.userSetWidth = targetWidth
            this.userSetHeight = targetHeight
            
            val fboCanvasWidth = kotlin.math.max(targetWidth, targetHeight)
            val fboCanvasHeight = kotlin.math.min(targetWidth, targetHeight)
            
            this.streamWidth = fboCanvasWidth
            this.streamHeight = fboCanvasHeight

            rtmpStreamer?.stop()
            bitrateController?.stop()

            isFboRenderLoopRunning = false
            fboRenderJob?.join()
            fboRenderJob = null
            
            lastTimestampNs = 0

            scope.launch(Dispatchers.Main) {
                try {
                    controller.closeCamera()
                    
                    videoEncoder?.release()
                    audioEncoder?.release()
                    
                    imageReaderSurface?.release()
                    imageReaderSurface = null
                    imageReader?.close()
                    imageReader = null

                    val bitrate = bitrateController?.getCurrentBitrate() ?: 2000000
                    videoEncoder = VideoEncoder()
                    val encoderSurface = videoEncoder!!.initialize(fboCanvasWidth, fboCanvasHeight, bitrate, 30)
                    if (encoderSurface == null) {
                        result.error("ENCODER_INIT_FAILED", "视频编码器初始化失败", null)
                        return@launch
                    }

                    audioEncoder?.let {
                        val audio = AudioEncoder()
                        if (audio.initialize()) {
                            audioEncoder = audio
                        }
                    }

                    rtmpStreamer?.release()
                    rtmpStreamer = RtmpStreamer()
                    // 设置状态回调
                    rtmpStreamer!!.setStatusCallback(object : RtmpStreamer.StatusCallback {
                        override fun onStatus(status: String, error: String?) {
                            notifyStreamingStatus(status, error)
                        }
                    })
                    if (!rtmpStreamer!!.initialize(rtmpUrl, videoEncoder!!, audioEncoder)) {
                        result.error("RTMP_INIT_FAILED", "RTMP 重新初始化失败", null)
                        return@launch
                    }

                    val audioSampleRate = audioEncoder?.getSampleRate() ?: 44100
                    val audioChannels = audioEncoder?.getChannelCount() ?: 1
                    rtmpStreamer!!.setMetadata(fboCanvasWidth, fboCanvasHeight, bitrate, 30, audioSampleRate, audioChannels)

                    bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, controller)
                    bitrateController!!.initialize(bitrate, fboCanvasWidth, fboCanvasHeight)

                    texture.setDefaultBufferSize(fboCanvasWidth, fboCanvasHeight)
                    
                    this@BbRtmpPlugin.glFboCanvasWidth = fboCanvasWidth
                    this@BbRtmpPlugin.glFboCanvasHeight = fboCanvasHeight
                    this@BbRtmpPlugin.glEncoderSurface = encoderSurface
                    
                    imageReader = ImageReader.newInstance(targetWidth, targetHeight, ImageFormat.YUV_420_888, 4)
                    imageReader?.setOnImageAvailableListener(imageAvailableListener, null)
                    imageReaderSurface = imageReader?.surface

                    fboInputSurfaceTexture = SurfaceTexture(false)
                    fboInputSurfaceTexture!!.setDefaultBufferSize(targetWidth, targetHeight)
                    
                    controller.setStateCallback(object : CameraController.CameraStateCallback {
                        override fun onCameraOpened() {
                            scope.launch(Dispatchers.Main) {
                                val actualSize = controller.getPreviewSize()
                                if (actualSize != null) {
                                    this@BbRtmpPlugin.cameraOutputWidth = actualSize.width
                                    this@BbRtmpPlugin.cameraOutputHeight = actualSize.height
                                }
                                val surfaces = mutableListOf<Surface>()
                                imageReaderSurface?.let { surfaces.add(it) }
                                controller.createCaptureSession(surfaces)
                                result.success(null)
                            }
                        }

                        override fun onCameraError(error: String) {
                            result.error("CAMERA_ERROR", "切换分辨率时相机错误: $error", null)
                        }
                    })

                    val success = controller.openCamera(targetWidth, targetHeight, fboInputSurfaceTexture!!)
                    if (!success) {
                        result.error("CHANGE_RESOLUTION_FAILED", "打开相机失败", null)
                    }
                } catch (e: Exception) {
                    result.error("CHANGE_RESOLUTION_ERROR", "切换分辨率异常: ${e.message}", null)
                }
            }
        } catch (e: Exception) {
            result.error("CHANGE_RESOLUTION_ERROR", "切换分辨率异常: ${e.message}", null)
        }
    }

    private fun setBitrate(call: MethodCall, result: Result) {
        try {
            val bitrate = call.argument<Int>("bitrate") ?: 2000000
            bitrateController?.setBitrate(bitrate)
            result.success(null)
        } catch (e: Exception) {
            result.error("SET_BITRATE_ERROR", "设置码率失败: ${e.message}", null)
        }
    }

    private fun getStatus(result: Result) {
        try {
            val isStreaming = rtmpStreamer?.isStreaming() ?: false
            val currentBitrate = bitrateController?.getCurrentBitrate() ?: 0
            val cameraId = cameraController?.getCurrentCameraId()
            
            val status = mapOf(
                "isStreaming" to isStreaming,
                "currentBitrate" to currentBitrate,
                "fps" to 30.0,
                "width" to streamWidth,
                "height" to streamHeight,
                "previewWidth" to streamWidth,
                "previewHeight" to streamHeight,
                "cameraId" to (cameraId ?: "")
            )

            result.success(status)
        } catch (e: Exception) {
            result.error("GET_STATUS_ERROR", "获取状态失败: ${e.message}", null)
        }
    }

    private fun stopFboRenderLoop() {
        isFboRenderLoopRunning = false
        
        // 等待渲染循环真正结束
        try {
            // 使用 runBlocking 等待协程完成（在 release 时调用，可以阻塞）
            kotlinx.coroutines.runBlocking {
                fboRenderJob?.join()
            }
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "等待 FBO 渲染循环结束失败", e)
        }
        fboRenderJob = null
        
        // 注意：rgbaBufferPool 在函数作用域内，函数结束后会自动释放
        // DirectByteBuffer 会被 GC 自动回收，无需手动释放
    }

    private fun startFboRenderLoop(
        inputSurfaceTexture: SurfaceTexture,
        previewSurfaceTexture: SurfaceTexture,
        encoderSurface: Surface,
        canvasWidth: Int,
        canvasHeight: Int,
        cameraWidth: Int,
        cameraHeight: Int
    ) {
        if (isFboRenderLoopRunning) return
        isFboRenderLoopRunning = true

        fboRenderJob = scope.launch(Dispatchers.Default) {
            val renderer = glRenderer ?: return@launch
            
            var previewSurface: Surface? = null
            try {
                previewSurface = Surface(previewSurfaceTexture)
                renderer.initEgl(encoderSurface, previewSurface)
                renderer.initGl(canvasWidth, canvasHeight)
                
                val textures = IntArray(1)
                android.opengl.GLES20.glGenTextures(1, textures, 0)
                cameraTextureId = textures[0]
                
                android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
                android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                
                inputSurfaceTexture.attachToGLContext(cameraTextureId)
                
                scope.launch(Dispatchers.Main) {
                    val surfaces = mutableListOf<Surface>()
                    imageReaderSurface?.let { surfaces.add(it) }
                    cameraController?.createCaptureSession(surfaces)
                }
                
                var frameCount = 0L
                var lastLogTime = System.currentTimeMillis()
                val fboLoopStartMs = System.currentTimeMillis()
                // 延迟 N 秒后再读像素/发 fbo_rgba，避免相机与 Surface 未稳定时读到全黑
                val fboReadWarmupMs = 1000L
                var fboReadWarmupLogged = false
                var fboWarmupStartLogged = false
                // 使用固定大小的环形 buffer pool（4 个），控制内存占用
                // 每个 buffer 大小：canvasWidth * canvasHeight * 4 字节
                // 4 个 buffer 足够覆盖异步延迟：渲染线程→主线程→YOLO后台线程
                val rgbaBufferPoolSize = 4 // 固定大小，减少内存占用（从 16 减少到 4）
                val rgbaBufferPool = Array(rgbaBufferPoolSize) {
                    ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 4).order(ByteOrder.nativeOrder())
                }
                var rgbaPoolIndex = 0 // 环形索引
                // 跳帧调用 YOLO（根据 skipFrame 参数决定，0 表示不跳帧）
                var yoloFrameCounter = 0
                // 异常处理和重试机制（在循环外定义，保持状态）
                var updateTexImageErrorCount = 0
                val maxUpdateTexImageErrors = 10

                while (isFboRenderLoopRunning && glRenderer != null && cameraTextureId != 0) {
                    try {
                        val frameStartTime = System.currentTimeMillis()
                        
                        if (inputSurfaceTexture.isReleased) {
                            android.util.Log.w("BbRtmpPlugin", "SurfaceTexture 已被释放，停止渲染循环")
                            break
                        }
                        
                        // 异常处理和重试机制
                        try {
                            inputSurfaceTexture.updateTexImage()
                            updateTexImageErrorCount = 0 // 重置错误计数
                        } catch (e: Exception) {
                            updateTexImageErrorCount++
                            if (e.message?.contains("abandoned") == true || e.message?.contains("released") == true) {
                                android.util.Log.w("BbRtmpPlugin", "updateTexImage 失败，SurfaceTexture 已废弃: ${e.message}")
                                break
                            }
                            // 连续错误过多，停止渲染循环
                            if (updateTexImageErrorCount >= maxUpdateTexImageErrors) {
                                android.util.Log.e("BbRtmpPlugin", "updateTexImage 连续失败 $updateTexImageErrorCount 次，停止渲染循环")
                                break
                            }
                            // 短暂等待后重试
                            Thread.sleep(10)
                            continue
                        }
                        
                        if (isInBackground) {
                            Thread.sleep(33)
                            continue
                        }
                        
                        val timestampNs = inputSurfaceTexture.timestamp
                        
                        // 调试：检查时间戳是否有效
                        if (frameCount == 0L && timestampNs == 0L) {
                            android.util.Log.w("BbRtmpPlugin", "警告：第一帧时间戳为 0，可能相机还没有开始输出")
                        }
                        val finalTimestampNs = synchronized(this@BbRtmpPlugin) {
                            if (timestampNs <= lastTimestampNs) {
                                lastTimestampNs + 33_333_333L
                            } else {
                                timestampNs
                            }.also { lastTimestampNs = it }
                        }
                        
                        val transformMatrix = FloatArray(16)
                        inputSurfaceTexture.getTransformMatrix(transformMatrix)
                        
                        val controller = cameraController
                        var rotation = 0
                        var isInputContentPortrait = false
                        
                        if (controller != null) {
                            val displayRotation = controller.getDisplayRotation()
                            val sensorOrientation = controller.getSensorOrientation()
                            val isFront = controller.isFrontFacing()
                            
                            val relativeRotation = kotlin.math.abs(sensorOrientation - displayRotation)
                            isInputContentPortrait = (relativeRotation % 180) == 90
                            
                            rotation = if (displayRotation == 90 || displayRotation == 270) 270 else 0
                        }
                        
                        // 延迟一段时间后再读像素，避免起播时相机未稳定导致全黑
                        val elapsed = System.currentTimeMillis() - fboLoopStartMs
                        val pastWarmup = elapsed >= fboReadWarmupMs
                        // 跳帧调用 YOLO：根据 skipFrame 参数决定（0 表示不跳帧）
                        // skipFrame = 0: 每帧都回调
                        // skipFrame = n: 每隔 n 帧回调一次（第 1, n+2, 2n+3... 帧）
                        yoloFrameCounter++
                        val shouldReadForYolo = enableFrameCallback && frameEventSink != null && pastWarmup && 
                            (frameSkip == 0 || ((yoloFrameCounter - 1) % (frameSkip + 1) == 0))
                        val targetBuf = if (shouldReadForYolo) {
                            // 环形池：使用模运算实现环形索引
                            val idx = rgbaPoolIndex
                            rgbaPoolIndex = (rgbaPoolIndex + 1) % rgbaBufferPoolSize
                            rgbaBufferPool[idx]
                        } else null

                        renderer.renderFrame(
                            cameraTexture = cameraTextureId,
                            stMatrix = transformMatrix,
                            videoWidth = cameraWidth,
                            videoHeight = cameraHeight,
                            mode = GlRenderer.ScaleMode.FIT,
                            extraRotation = rotation,
                            isInputContentPortrait = isInputContentPortrait,
                            timestampNs = finalTimestampNs,
                            fboReadTarget = targetBuf
                        )

                        if (targetBuf != null) {
                            val address = NativeBridge.getDirectBufferAddress(targetBuf)
                            if (address != 0L) {
                                // 注意：Android 的 EventChannel.EventSink 必须在主线程调用（与 iOS 不同）
                                // 但我们可以先切换到主线程，避免阻塞当前渲染线程
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        frameEventSink?.success(mapOf(
                                            "type" to "fbo_rgba",
                                            "address" to address,
                                            "width" to glFboCanvasWidth,
                                            "height" to glFboCanvasHeight,
                                            "stride" to (glFboCanvasWidth * 4)
                                        ))
                                    } catch (e: Exception) {
                                        android.util.Log.e("BbRtmpPlugin", "发送 FBO RGBA 数据失败", e)
                                    }
                                }
                            } else {
                                android.util.Log.e("BbRtmpPlugin", "获取 DirectByteBuffer 地址失败")
                            }
                        }
                        
                        frameCount++
                        
                        val frameTime = System.currentTimeMillis() - frameStartTime
                        val sleepTime = 33 - frameTime
                        if (sleepTime > 0) Thread.sleep(sleepTime)
                        
                    } catch (e: Exception) {
                        android.util.Log.e("BbRtmpPlugin", "渲染循环异常", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BbRtmpPlugin", "FBO 启动失败", e)
            } finally {
                isFboRenderLoopRunning = false
                if (cameraTextureId != 0) {
                    try {
                        if (!inputSurfaceTexture.isReleased) inputSurfaceTexture.detachFromGLContext()
                        android.opengl.GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
                    } catch (e: Exception) {}
                    cameraTextureId = 0
                }
                previewSurface?.release()
                try {
                    renderer.release()
                    glRenderer = null
                } catch (e: Exception) {}
            }
        }
    }

    private fun release() {
        try {
            // 1. 先停止推流和码率控制
            rtmpStreamer?.stop()
            bitrateController?.stop()
            
            // 2. 清除编码器回调，防止回调在释放后继续执行
            videoEncoder?.setCallback(object : VideoEncoder.EncoderCallback {
                override fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
                    // 空回调，忽略所有数据
                }
                override fun onCodecConfig(sps: ByteArray, pps: ByteArray) {
                    // 空回调，忽略配置
                }
                override fun onError(error: String) {
                    // 空回调，忽略错误
                }
            })
            audioEncoder?.setCallback(object : AudioEncoder.EncoderCallback {
                override fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
                    // 空回调，忽略所有数据
                }
                override fun onError(error: String) {
                    // 空回调，忽略错误
                }
            })
            
            // 3. 停止 FBO 渲染循环（会等待循环真正结束）
            stopFboRenderLoop()
            
            // 4. 停止服务
            context?.let { ctx ->
                val intent = android.content.Intent(ctx, RtmpService::class.java)
                ctx.stopService(intent)
            }
            
            // 5. 清理句柄映射
            synchronized(handleLock) {
                // 先关闭所有 Image
                handleToImage.values.forEach { image ->
                    try {
                        image.close()
                    } catch (e: Exception) {}
                }
                handleToImage.clear()

                // 释放 AHardwareBuffer 指针（先解锁再释放）
                handleToNativePtr.values.forEach { ptr ->
                    if (ptr != 0L) {
                        try {
                            NativeBridge.unlockAHardwareBuffer(ptr)
                        } catch (e: Exception) {}
                        try {
                            NativeBridge.releaseAHardwareBufferPtr(ptr)
                        } catch (e: Exception) {}
                    }
                }
                handleToNativePtr.clear()
                
                // 然后关闭所有 HardwareBuffer
                handleToBuffer.values.forEach { buffer ->
                    try {
                        buffer.close()
                    } catch (e: Exception) {}
                }
                handleToBuffer.clear()
            }
            
            // 6. 释放 ImageReader
            imageReaderSurface?.release()
            imageReaderSurface = null
            imageReader?.close()
            imageReader = null
            
            // 7. 释放编码器和推流器（按顺序）
            bitrateController?.release()
            rtmpStreamer?.release()
            videoEncoder?.release()
            audioEncoder?.release()
            
            // 8. 关闭相机
            cameraController?.closeCamera()
            
            // 9. 释放纹理
            textureEntry?.release()

            // 10. 清空引用
            bitrateController = null
            rtmpStreamer = null
            videoEncoder = null
            audioEncoder = null
            cameraController = null
            textureEntry = null
            streamWidth = 0
            streamHeight = 0
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "释放资源失败", e)
        }
    }

    private fun enableFrameCallback(call: MethodCall, result: Result) {
        try {
            val enable = call.argument<Boolean>("enable") ?: false
            val skipFrame = call.argument<Int>("skipFrame") ?: 0
            enableFrameCallback = enable
            frameSkip = skipFrame.coerceAtLeast(0) // 确保 >= 0
            result.success(enable)
        } catch (e: Exception) {
            result.error("ENABLE_FRAME_CALLBACK_ERROR", e.message, null)
        }
    }

    private fun releasePixelBufferHandle(call: MethodCall, result: Result) {
        try {
            // 兼容 Dart 传入的 Integer/Long
            val handle = call.argument<Number>("handle")?.toLong() ?: 0L
            val released = synchronized(handleLock) {
                val buffer = handleToBuffer.remove(handle)
                val image = handleToImage.remove(handle)
                val nativePtr = handleToNativePtr.remove(handle) ?: 0L
                
                var success = false
                if (buffer != null && image != null) {
                    try {
                        // 如果已锁定，先解锁
                        if (nativePtr != 0L) {
                            try {
                                NativeBridge.unlockAHardwareBuffer(nativePtr)
                            } catch (e: Exception) {
                                android.util.Log.w("BbRtmpPlugin", "解锁 AHardwareBuffer 失败", e)
                            }
                            // 释放 native 指针
                            NativeBridge.releaseAHardwareBufferPtr(nativePtr)
                        }
                        // 先关闭 Image，这会释放 HardwareBuffer
                        image.close()
                        // 然后关闭 HardwareBuffer（虽然 Image.close() 应该已经关闭了，但为了安全还是调用）
                        buffer.close()
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.e("BbRtmpPlugin", "释放句柄失败", e)
                        // 即使关闭失败，也尝试关闭 Image
                        try {
                            image.close()
                        } catch (e2: Exception) {
                            android.util.Log.e("BbRtmpPlugin", "关闭 Image 失败", e2)
                        }
                    }
                } else if (buffer != null) {
                    // 只有 buffer 没有 image，直接关闭 buffer
                    try {
                        if (nativePtr != 0L) {
                            try {
                                NativeBridge.unlockAHardwareBuffer(nativePtr)
                            } catch (e: Exception) {
                                android.util.Log.w("BbRtmpPlugin", "解锁 AHardwareBuffer 失败", e)
                            }
                            NativeBridge.releaseAHardwareBufferPtr(nativePtr)
                        }
                        buffer.close()
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.e("BbRtmpPlugin", "关闭 HardwareBuffer 失败", e)
                    }
                } else if (image != null) {
                    // 只有 image 没有 buffer，直接关闭 image
                    try {
                        if (nativePtr != 0L) {
                            try {
                                NativeBridge.unlockAHardwareBuffer(nativePtr)
                            } catch (e: Exception) {
                                android.util.Log.w("BbRtmpPlugin", "解锁 AHardwareBuffer 失败", e)
                            }
                            NativeBridge.releaseAHardwareBufferPtr(nativePtr)
                        }
                        image.close()
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.e("BbRtmpPlugin", "关闭 Image 失败", e)
                    }
                } else if (nativePtr != 0L) {
                    // 只有 native 指针
                    try {
                        try {
                            NativeBridge.unlockAHardwareBuffer(nativePtr)
                        } catch (e: Exception) {
                            android.util.Log.w("BbRtmpPlugin", "解锁 AHardwareBuffer 失败", e)
                        }
                        NativeBridge.releaseAHardwareBufferPtr(nativePtr)
                        success = true
                    } catch (e: Exception) {
                        android.util.Log.e("BbRtmpPlugin", "释放 AHardwareBuffer 指针失败", e)
                    }
                }
                success
            }
            result.success(released)
        } catch (e: Exception) {
            result.error("RELEASE_HANDLE_ERROR", e.message, null)
        }
    }

    /**
     * 获取 Image Planes 数据（用于 YUV_420_888 格式，零拷贝）
     * 返回 Y、U、V 平面的 DirectByteBuffer 地址和 stride 信息
     */
    private fun getImagePlanes(call: MethodCall, result: Result) {
        try {
            val handle = call.argument<Number>("handle")?.toLong() ?: 0L
            val image = handleToImage[handle]
            
            if (image == null) {
                result.error("HANDLE_NOT_FOUND", "句柄不存在", null)
                return
            }
            
            if (image.format != android.graphics.ImageFormat.YUV_420_888) {
                result.error("INVALID_FORMAT", "图像格式不是 YUV_420_888", null)
                return
            }
            
            val planes = image.planes
            if (planes.size < 3) {
                result.error("INVALID_PLANES", "YUV_420_888 需要至少 3 个平面", null)
                return
            }
            
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            
            // 获取 DirectByteBuffer（零拷贝）
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            // 获取 buffer 的 native 地址
            val yAddress = if (yBuffer.isDirect) {
                // DirectByteBuffer 可以直接获取地址
                val yAddressField = yBuffer.javaClass.getDeclaredField("address")
                yAddressField.isAccessible = true
                yAddressField.getLong(yBuffer)
            } else {
                // 如果不是 DirectByteBuffer，需要转换为 DirectByteBuffer（会有一次拷贝）
                val yArray = ByteArray(yBuffer.remaining())
                yBuffer.duplicate().get(yArray)
                val yDirectBuffer = java.nio.ByteBuffer.allocateDirect(yArray.size)
                yDirectBuffer.put(yArray)
                yDirectBuffer.rewind()
                val yAddressField = yDirectBuffer.javaClass.getDeclaredField("address")
                yAddressField.isAccessible = true
                yAddressField.getLong(yDirectBuffer)
            }
            
            val uAddress = if (uBuffer.isDirect) {
                val uAddressField = uBuffer.javaClass.getDeclaredField("address")
                uAddressField.isAccessible = true
                uAddressField.getLong(uBuffer)
            } else {
                val uArray = ByteArray(uBuffer.remaining())
                uBuffer.duplicate().get(uArray)
                val uDirectBuffer = java.nio.ByteBuffer.allocateDirect(uArray.size)
                uDirectBuffer.put(uArray)
                uDirectBuffer.rewind()
                val uAddressField = uDirectBuffer.javaClass.getDeclaredField("address")
                uAddressField.isAccessible = true
                uAddressField.getLong(uDirectBuffer)
            }
            
            val vAddress = if (vBuffer.isDirect) {
                val vAddressField = vBuffer.javaClass.getDeclaredField("address")
                vAddressField.isAccessible = true
                vAddressField.getLong(vBuffer)
            } else {
                val vArray = ByteArray(vBuffer.remaining())
                vBuffer.duplicate().get(vArray)
                val vDirectBuffer = java.nio.ByteBuffer.allocateDirect(vArray.size)
                vDirectBuffer.put(vArray)
                vDirectBuffer.rewind()
                val vAddressField = vDirectBuffer.javaClass.getDeclaredField("address")
                vAddressField.isAccessible = true
                vAddressField.getLong(vDirectBuffer)
            }
            
            result.success(mapOf(
                "yPlane" to yAddress,
                "uPlane" to uAddress,
                "vPlane" to vAddress,
                "yStride" to yPlane.rowStride,
                "uStride" to uPlane.rowStride,
                "vStride" to vPlane.rowStride,
                "uPixelStride" to uPlane.pixelStride,
                "vPixelStride" to vPlane.pixelStride,
                "width" to image.width,
                "height" to image.height
            ))
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "获取 Image Planes 失败", e)
            result.error("GET_PLANES_ERROR", e.message, null)
        }
    }

    /**
     * 获取 HardwareBuffer 的虚拟地址（用于传递给需要直接访问内存的插件，如 Yolo11）
     * 优先使用 AHardwareBuffer lock 获取连续内存地址（零拷贝）
     * 注意：返回的地址指向连续内存，但格式可能不是标准 NV12，Yolo11 插件需要适配实际格式
     */
    private fun getZoomRange(result: Result) {
        try {
            val controller = cameraController ?: run {
                result.error("NOT_INITIALIZED", "相机未初始化", null)
                return
            }
            
            val zoomRange = controller.getZoomRange()
            result.success(zoomRange)
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "获取 zoom 范围失败", e)
            result.error("GET_ZOOM_RANGE_ERROR", e.message, null)
        }
    }
    
    private fun setZoom(call: MethodCall, result: Result) {
        try {
            val zoom = call.argument<Double>("zoom")?.toFloat() ?: run {
                result.error("INVALID_ARGUMENT", "zoom 参数无效", null)
                return
            }
            
            val controller = cameraController ?: run {
                result.error("NOT_INITIALIZED", "相机未初始化", null)
                return
            }
            
            val success = controller.setZoom(zoom)
            if (success) {
                result.success(true)
            } else {
                result.error("SET_ZOOM_FAILED", "设置 zoom 失败", null)
            }
        } catch (e: Exception) {
            android.util.Log.e("BbRtmpPlugin", "设置 zoom 失败", e)
            result.error("SET_ZOOM_ERROR", e.message, null)
        }
    }
    
    private fun getHardwareBufferNativeHandle(call: MethodCall, result: Result) {
        try {
            val handle = call.argument<Number>("handle")?.toLong() ?: 0L
            val image = handleToImage[handle]
            val buffer = handleToBuffer[handle]
            
            val virtualAddress = synchronized(handleLock) {
                if (buffer != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        // 优先使用 AHardwareBuffer lock 获取连续内存地址（零拷贝）
                        // 这是最可能提供连续数据的方式
                        var aBufferPtr = handleToNativePtr[handle]
                        if (aBufferPtr == null || aBufferPtr == 0L) {
                            aBufferPtr = NativeBridge.getAHardwareBufferPtr(buffer)
                            if (aBufferPtr != 0L) {
                                handleToNativePtr[handle] = aBufferPtr
                            }
                        }
                        
                        if (aBufferPtr != 0L) {
                            // 锁定 buffer 并获取虚拟地址（连续内存，零拷贝）
                            val vAddr = NativeBridge.lockAHardwareBuffer(aBufferPtr)
                            if (vAddr != 0L) {
                                // 获取图像信息用于日志
                                val width = image?.width ?: 0
                                val height = image?.height ?: 0
                                val format = image?.format ?: 0
                                return@synchronized vAddr
                            } else {
                                android.util.Log.e("BbRtmpPlugin", "锁定 AHardwareBuffer 失败")
                            }
                        }
                        null
                    } catch (e: Exception) {
                        android.util.Log.e("BbRtmpPlugin", "无法获取 AHardwareBuffer 虚拟地址", e)
                        null
                    }
                } else {
                    null
                }
            }
            
            if (virtualAddress != null && virtualAddress != 0L) {
                result.success(virtualAddress)
            } else {
                result.error("HANDLE_NOT_FOUND", "句柄不存在或无法获取虚拟地址", null)
            }
        } catch (e: Exception) {
            result.error("GET_NATIVE_HANDLE_ERROR", e.message, null)
        }
    }

    private val lifecycleCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityStarted(activity: android.app.Activity) {
            if (activity == this@BbRtmpPlugin.activity) {
                isInBackground = false
                rtmpStreamer?.stopHeartbeat()
                if (rtmpStreamer?.isStreaming() == true) {
                    cameraController?.let { controller ->
                        scope.launch(Dispatchers.Main) {
                            if (!controller.isCameraDeviceOpen()) {
                                stopFboRenderLoop()
                                glRenderer = null
                                fboInputSurfaceTexture = SurfaceTexture(false)
                                fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                                controller.openCamera(glCameraWidth, glCameraHeight, fboInputSurfaceTexture!!)
                            } else {
                                videoEncoder?.requestKeyFrame()
                                try {
                                    controller.createCaptureSession(emptyList())
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
            }
        }
        override fun onActivityStopped(activity: android.app.Activity) {
            if (activity == this@BbRtmpPlugin.activity) {
                isInBackground = true
                if (rtmpStreamer?.isStreaming() == true) rtmpStreamer?.startHeartbeat()
            }
        }
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

    override fun onAttachedToActivity(binding: io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding) {
        this.activity = binding.activity
        activity?.application?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        this.activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding) {
        this.activity = binding.activity
        activity?.application?.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    override fun onDetachedFromActivity() {
        activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
        this.activity = null
    }
}