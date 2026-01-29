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
    // 预览 Surface（来自 PlatformView，替代 SurfaceTexture）
    private var previewSurface: Surface? = null
    
    // 保存 OpenGL 初始化参数（用于在渲染线程中初始化）
    @Volatile
    private var glEncoderSurface: Surface? = null
    @Volatile
    private var glFboCanvasWidth: Int = 0
    @Volatile
    private var glFboCanvasHeight: Int = 0
    @Volatile
    private var glCameraWidth: Int = 0
    @Volatile
    private var glCameraHeight: Int = 0
    // FBO 渲染循环控制
    @Volatile
    private var isFboRenderLoopRunning = false
    private var fboRenderJob: kotlinx.coroutines.Job? = null
    // 时间戳管理（用于确保时间戳递增，避免编码器丢帧）
    private var lastTimestampNs: Long = 0
    
    // NV12 帧数据句柄传递相关
    private var frameEventChannel: EventChannel? = null
    @Volatile
    private var frameEventSink: EventChannel.EventSink? = null
    
    // 推流状态通知相关
    private var statusEventChannel: EventChannel? = null
    @Volatile
    private var statusEventSink: EventChannel.EventSink? = null
    @Volatile
    private var enableFrameCallback: Boolean = false
    @Volatile
    private var frameSkip: Int = 0 // 跳帧数，0 表示不跳帧（每帧都回调）
    
    // PlatformView 预览 Surface 的实际大小
    @Volatile
    private var previewSurfaceWidth: Int = 0
    @Volatile
    private var previewSurfaceHeight: Int = 0
    
    @Volatile
    private var isPreviewSurfaceReady = false
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
                val msg = ">>> onListen: Frame channel CONNECTED <<<"
                android.util.Log.e("BbRtmpPlugin", msg)
                println("BbRtmpPlugin: $msg")
                frameEventSink = events
                enableFrameCallback = true
            }
            
            override fun onCancel(arguments: Any?) {
                val msg = ">>> onCancel: Frame channel DISCONNECTED <<<"
                android.util.Log.e("BbRtmpPlugin", msg)
                println("BbRtmpPlugin: $msg")
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
        // 注册 PlatformView Factory
        flutterPluginBinding.platformViewRegistry.registerViewFactory(
            "bb_rtmp_preview",
            BbRtmpPreviewFactory(
                onSurfaceCreated = { surface: Surface ->
                    previewSurface = surface
                    isPreviewSurfaceReady = true
                    android.util.Log.i("BbRtmpPlugin", "PlatformView Surface Created")
                    // 后台切前台时 Surface 会重新创建，渲染循环已退出且 glRenderer 被置空；旧编码器 Surface 在 EGL 释放后可能失效，需重建编码器+GlRenderer 才能恢复推流
                    if (glRenderer == null && fboInputSurfaceTexture != null && cameraController != null && glFboCanvasWidth > 0 && glFboCanvasHeight > 0) {
                        android.util.Log.i("BbRtmpPlugin", "Surface 重建后恢复编码器与 GlRenderer 并重启渲染循环")
                        val bitrate = bitrateController?.getCurrentBitrate() ?: videoEncoder?.getCurrentBitrate() ?: 2000000
                        val newEncoder = VideoEncoder()
                        val newSurface = newEncoder.initialize(glFboCanvasWidth, glFboCanvasHeight, bitrate, 30)
                        if (newSurface != null) {
                            val oldEncoder = videoEncoder
                            videoEncoder = newEncoder
                            oldEncoder?.release()
                            glEncoderSurface = newSurface
                            if (rtmpStreamer != null) {
                                rtmpStreamer!!.replaceVideoEncoder(videoEncoder!!)
                                if (rtmpStreamer!!.isStreaming()) {
                                    val audioRate = audioEncoder?.getSampleRate() ?: 44100
                                    val audioCh = audioEncoder?.getChannelCount() ?: 1
                                    rtmpStreamer!!.onResolutionChangeComplete(glFboCanvasWidth, glFboCanvasHeight, bitrate, 30, audioRate, audioCh)
                                    videoEncoder?.requestKeyFrame()
                                }
                            }
                        }
                        glRenderer = GlRenderer()
                        cameraTextureId = 0
                    }
                    tryStartRenderLoopIfReady()
                },
                onSurfaceChanged = { width: Int, height: Int ->
                    previewSurfaceWidth = width
                    previewSurfaceHeight = height
                    android.util.Log.i("BbRtmpPlugin", "Preview Surface Changed: ${width}x${height}")
                },
                onSurfaceDestroyed = {
                    isPreviewSurfaceReady = false
                    previewSurface = null
                    // 停止渲染循环（或者让渲染循环检测到 Surface 无效自动暂停）
                    isFboRenderLoopRunning = false
                    android.util.Log.i("BbRtmpPlugin", "PlatformView Surface Destroyed")
                }
            )
        )
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
            val hardwareSize = cameraController!!.selectBestResolution(streamWidth, streamHeight, targetCameraId)
            val maxSize = cameraController!!.getMaxSupportedResolution(targetCameraId)
            
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
            this.userSetWidth = streamWidth
            this.userSetHeight = streamHeight
            
            // 4. FBO 画布分辨率 = 标准横屏分辨率
            val fboCanvasWidth = kotlin.math.max(streamWidth, streamHeight)
            val fboCanvasHeight = kotlin.math.min(streamWidth, streamHeight)
            
            this.streamWidth = fboCanvasWidth
            this.streamHeight = fboCanvasHeight
            

            // 5. 初始化编码器
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
            
            // 6. 初始化 RTMP 推流器（如果 URL 存在）
            if (rtmpUrl.isNotEmpty()) {
                rtmpStreamer = RtmpStreamer()
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
                rtmpStreamer!!.setMetadata(fboCanvasWidth, fboCanvasHeight, bitrate, fps, 44100, 1)
            }

            // 7. 初始化 FBO 和 GlRenderer
            glRenderer = GlRenderer()
            
            this.glEncoderSurface = encoderSurface
            this.glFboCanvasWidth = fboCanvasWidth
            this.glFboCanvasHeight = fboCanvasHeight
            this.glCameraWidth = cameraWidth
            this.glCameraHeight = cameraHeight
            
            // 创建 FBO 输入 SurfaceTexture
            fboInputSurfaceTexture = SurfaceTexture(false)
            fboInputSurfaceTexture!!.setDefaultBufferSize(cameraWidth, cameraHeight)
            
            // 8. 创建 ImageReader
            imageReader = ImageReader.newInstance(cameraWidth, cameraHeight, ImageFormat.YUV_420_888, 4)
            imageReader?.setOnImageAvailableListener(imageAvailableListener, null)
            imageReaderSurface = imageReader?.surface
            
            // 9. 配置相机回调并打开
            cameraController!!.setStateCallback(object : CameraController.CameraStateCallback {
                override fun onCameraOpened() {
                    scope.launch(Dispatchers.Main) {
                        // 确保 FBO 输入纹理已就绪
                        if (fboInputSurfaceTexture == null) {
                            fboInputSurfaceTexture = SurfaceTexture(false)
                            fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                        }
                        
                        // 确保 GlRenderer 已创建
                        if (glRenderer == null) {
                            glRenderer = GlRenderer()
                            cameraTextureId = 0
                        }
                        
                        // 尝试启动渲染循环
                        // (注意：如果 previewSurface 还没准备好，这里会跳过，稍后由 BbRtmpPreviewFactory 的回调再次触发)
                        tryStartRenderLoopIfReady()
                        
                        // 返回结果
                        if (!resultReplied) {
                            result.success(-1L) // PlatformView 模式返回无效 textureId
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

            // 初始化码率控制器（ABR 一步到位切分辨率时热切换编码器）
            if (rtmpStreamer != null) {
                bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, cameraController!!)
                bitrateController!!.initialize(bitrate, fboCanvasWidth, fboCanvasHeight)
                bitrateController!!.setResolutionChangeCallback { w, h ->
                    scope.launch(Dispatchers.Main) { doHotResolutionSwitch(w, h) }
                }
            }

            // 10. 打开相机
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
    private fun tryStartRenderLoopIfReady() {
        if (glRenderer != null && fboInputSurfaceTexture != null && isPreviewSurfaceReady && previewSurface != null) {
            val encoderSurface = glEncoderSurface
            if (encoderSurface != null) {
                startFboRenderLoop(
                    fboInputSurfaceTexture!!,
                    previewSurface!!,
                    encoderSurface,
                    glFboCanvasWidth,
                    glFboCanvasHeight,
                    glCameraWidth,
                    glCameraHeight
                )
            }
        }
    }

    /**
     * ABR 触发的分辨率热切换：不中断推流，替换编码器并更新 metadata/SPS/PPS，仅发关键帧直至首帧
     */
    private fun doHotResolutionSwitch(w: Int, h: Int) {
        if (rtmpStreamer == null || !rtmpStreamer!!.isStreaming() || bitrateController == null || cameraController == null) return
        scope.launch(Dispatchers.Default) {
            isFboRenderLoopRunning = false
            fboRenderJob?.join()
            fboRenderJob = null
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                try {
                    val oldEncoder = videoEncoder
                    videoEncoder = VideoEncoder()
                    val bitrate = bitrateController!!.getCurrentBitrate()
                    val surface = videoEncoder!!.initialize(w, h, bitrate, 30)
                    if (surface == null) {
                        android.util.Log.e("BbRtmpPlugin", "doHotResolutionSwitch: 新编码器初始化失败")
                        tryStartRenderLoopIfReady()
                        return@withContext
                    }
                    oldEncoder?.release()
                    rtmpStreamer!!.replaceVideoEncoder(videoEncoder!!)
                    val audioRate = audioEncoder?.getSampleRate() ?: 44100
                    val audioCh = audioEncoder?.getChannelCount() ?: 1
                    rtmpStreamer!!.onResolutionChangeComplete(w, h, bitrate, 30, audioRate, audioCh)
                    glFboCanvasWidth = w
                    glFboCanvasHeight = h
                    glEncoderSurface = surface
                    streamWidth = w
                    streamHeight = h
                    bitrateController!!.updateResolution(w, h)
                    videoEncoder?.requestKeyFrame()
                    // 上一轮循环退出时 finally 里已置 glRenderer=null，必须重建否则 tryStartRenderLoopIfReady 不会启动
                    if (glRenderer == null) {
                        glRenderer = GlRenderer()
                        cameraTextureId = 0
                    }
                    tryStartRenderLoopIfReady()
                    android.util.Log.i("BbRtmpPlugin", "doHotResolutionSwitch: ${w}x${h} 完成")
                } catch (e: Exception) {
                    android.util.Log.e("BbRtmpPlugin", "doHotResolutionSwitch 异常", e)
                    tryStartRenderLoopIfReady()
                }
            }
        }
    }

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
                        
                        // 初始化码率控制器（ABR 一步到位切分辨率时热切换编码器）
                        bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, cameraController!!)
                        bitrateController!!.initialize(newBitrate, glFboCanvasWidth, glFboCanvasHeight)
                        bitrateController!!.setResolutionChangeCallback { w, h ->
                            scope.launch(Dispatchers.Main) { doHotResolutionSwitch(w, h) }
                        }
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
            
            // 3. 释放预览纹理 (虽然已废弃但为了兼容可能还需要清理 textureEntry)
            textureEntry?.release()
            textureEntry = null
            // previewSurfaceTexture = null // 移除
            
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
            
            // 释放旧的 GlRenderer（如果存在）
            glRenderer?.release()
            glRenderer = null
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
                
                // 重新创建 GlRenderer（因为之前的已释放）
                glRenderer = GlRenderer()
                
                // 恢复渲染循环
                tryStartRenderLoopIfReady()
                
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

            // PlatformView 模式下不需要 textureEntry
            // val texture = textureEntry?.surfaceTexture() ...

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

            // 停止渲染循环
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

                    // texture.setDefaultBufferSize(fboCanvasWidth, fboCanvasHeight) // 移除
                    
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
                                    this@BbRtmpPlugin.glCameraWidth = actualSize.width
                                    this@BbRtmpPlugin.glCameraHeight = actualSize.height
                                }
                                
                                // 重新创建 GlRenderer (以防参数变化需要重建)
                                glRenderer?.release()
                                glRenderer = GlRenderer()
                                cameraTextureId = 0
                                
                                // 重启渲染循环
                                tryStartRenderLoopIfReady()
                                
                                // 创建 Capture Session (虽然 tryStartRenderLoopIfReady 里也会做，但作为双重保障，且这里没有副作用)
                                // 注意：实际上如果 tryStartRenderLoopIfReady 成功启动了循环，循环内部会 createCaptureSession
                                // 为了避免竞争，这里可以如果不调用 createCaptureSession，让循环去调。
                                // 但是循环启动是异步的。为了确保这里 result.success 之前配置完成，我们可以让循环去调。
                                // 如果这里不调，相机可能不出数据？
                                // startFboRenderLoop 内部也是在 attachToGLContext 后才 createCaptureSession。
                                // 只要 startFboRenderLoop 能跑起来就行。
                                
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
        previewSurface: Surface,
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
            android.util.Log.e("BbRtmpPlugin", ">>> startFboRenderLoop TASK STARTED <<<")
            println("BbRtmpPlugin: >>> startFboRenderLoop TASK STARTED <<<")
            
            try {
                // previewSurface 已经是由 PlatformView 传入的 Surface 对象，直接使用
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
                val fboReadWarmupMs = 200L
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
                var consecutiveErrors = 0
                val maxConsecutiveErrors = 30 // 连续30次错误后停止循环

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
                        
                        // 后台时暂停渲染，但保持循环运行（避免相机和编码器停止）
                        if (isInBackground) {
                            Thread.sleep(100) // 后台时降低渲染频率
                            continue
                        }
                        
                        // 检查预览Surface是否有效
                        if (!isPreviewSurfaceReady || previewSurface == null) {
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
                        
                        // 局部捕获当前的 sink 和开启状态，避免多线程引用变化
                        val currentSink = frameEventSink
                        val isCallbackEnabled = enableFrameCallback
                        
                        // 跳帧调用 YOLO：根据 skipFrame 参数决定（0 表示不跳帧）
                        yoloFrameCounter++
                        val isFrameMatch = (frameSkip == 0 || ((yoloFrameCounter - 1) % (frameSkip + 1) == 0))
                        
                        val shouldReadForYolo = isCallbackEnabled && currentSink != null && pastWarmup && isFrameMatch
                        
                        if (frameCount % 30 == 0L) {
                             val diag = "FrameCallback Diagnostic: shouldRead=$shouldReadForYolo, enabled=$isCallbackEnabled, sinkActive=${currentSink != null}, warmup=$pastWarmup, match=$isFrameMatch, out=$glFboCanvasWidth"
                             android.util.Log.e("BbRtmpPlugin", diag)
                             println("BbRtmpPlugin: $diag")
                        }

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
                            fboReadTarget = targetBuf,
                            previewWidth = previewSurfaceWidth,
                            previewHeight = previewSurfaceHeight
                        )

                        if (targetBuf != null && currentSink != null) {
                            val address = NativeBridge.getDirectBufferAddress(targetBuf)
                            if (address != 0L) {
                                // 记录实际发送的宽高，确保与 Buffer 分配一致
                                val outW = canvasWidth
                                val outH = canvasHeight
                                
                                scope.launch(Dispatchers.Main) {
                                    try {
                                        currentSink.success(mapOf(
                                            "type" to "fbo_rgba",
                                            "address" to address,
                                            "width" to outW,
                                            "height" to outH,
                                            "stride" to (outW * 4)
                                        ))
                                        if (frameCount % 60 == 0L) {
                                            android.util.Log.d("BbRtmpPlugin", "Successfully sent frame address: $address (${outW}x${outH})")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("BbRtmpPlugin", "EventChannel success error", e)
                                    }
                                }
                            } else {
                                android.util.Log.e("BbRtmpPlugin", "Failed to get direct buffer address")
                            }
                        }
                        
                        frameCount++
                        
                        val frameTime = System.currentTimeMillis() - frameStartTime
                        val sleepTime = 33 - frameTime
                        if (sleepTime > 0) Thread.sleep(sleepTime)
                        
                    } catch (e: Exception) {
                        consecutiveErrors++
                        android.util.Log.e("BbRtmpPlugin", "渲染循环异常 (连续错误: $consecutiveErrors)", e)
                        
                        // 如果是关键错误（如EGL context丢失），停止渲染循环
                        if (e.message?.contains("EGL") == true || 
                            e.message?.contains("context") == true ||
                            e.message?.contains("surface") == true) {
                            android.util.Log.e("BbRtmpPlugin", "检测到关键错误，停止渲染循环: ${e.message}")
                            break
                        }
                        
                        // 连续错误过多，停止渲染循环
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            android.util.Log.e("BbRtmpPlugin", "连续错误过多 ($consecutiveErrors)，停止渲染循环")
                            break
                        }
                        
                        // 其他错误短暂等待后继续
                        Thread.sleep(10)
                    }
                    
                    // 成功渲染一帧，重置错误计数
                    if (consecutiveErrors > 0) {
                        consecutiveErrors = 0
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
                // previewSurface 来自 PlatformView 的 SurfaceView，由其自行管理，不要 release
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
                android.util.Log.i("BbRtmpPlugin", "Activity Started - 恢复预览和推流")
                isInBackground = false
                rtmpStreamer?.stopHeartbeat()
                
                // 恢复相机和渲染循环
                cameraController?.let { controller ->
                    scope.launch(Dispatchers.Main) {
                        try {
                            if (!controller.isCameraDeviceOpen()) {
                                // 相机已关闭，需要重新打开
                                android.util.Log.i("BbRtmpPlugin", "相机已关闭，重新打开相机")
                                
                                // 确保渲染循环已停止
                                stopFboRenderLoop()
                                
                                // 重新创建 GlRenderer 和 SurfaceTexture
                                glRenderer?.release()
                                glRenderer = GlRenderer()
                                cameraTextureId = 0
                                
                                fboInputSurfaceTexture?.release()
                                fboInputSurfaceTexture = SurfaceTexture(false)
                                fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                                
                                // 重新创建 ImageReader
                                imageReader?.close()
                                imageReader = ImageReader.newInstance(glCameraWidth, glCameraHeight, android.graphics.ImageFormat.YUV_420_888, 4)
                                imageReader?.setOnImageAvailableListener(imageAvailableListener, null)
                                imageReaderSurface?.release()
                                imageReaderSurface = imageReader?.surface
                                
                                // 重新打开相机
                                controller.setStateCallback(object : CameraController.CameraStateCallback {
                                    override fun onCameraOpened() {
                                        scope.launch(Dispatchers.Main) {
                                            android.util.Log.i("BbRtmpPlugin", "相机重新打开成功，恢复渲染循环")
                                            // 恢复渲染循环
                                            tryStartRenderLoopIfReady()
                                        }
                                    }
                                    
                                    override fun onCameraError(error: String) {
                                        android.util.Log.e("BbRtmpPlugin", "恢复相机失败: $error")
                                    }
                                })
                                
                                controller.openCamera(glCameraWidth, glCameraHeight, fboInputSurfaceTexture!!)
                            } else {
                                // 相机仍然打开，恢复渲染循环和预览
                                android.util.Log.i("BbRtmpPlugin", "相机仍然打开，恢复渲染循环")
                                
                                // 请求关键帧，确保推流恢复
                                videoEncoder?.requestKeyFrame()
                                
                                // 恢复渲染循环（如果已停止）
                                if (!isFboRenderLoopRunning && previewSurface != null && isPreviewSurfaceReady) {
                                    tryStartRenderLoopIfReady()
                                }
                                
                                // 重新创建捕获会话，确保预览恢复
                                try {
                                    val surfaces = mutableListOf<Surface>()
                                    imageReaderSurface?.let { surfaces.add(it) }
                                    controller.createCaptureSession(surfaces)
                                } catch (e: Exception) {
                                    android.util.Log.e("BbRtmpPlugin", "恢复捕获会话失败", e)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("BbRtmpPlugin", "恢复预览和推流失败", e)
                        }
                    }
                }
            }
        }
        override fun onActivityStopped(activity: android.app.Activity) {
            if (activity == this@BbRtmpPlugin.activity) {
                android.util.Log.i("BbRtmpPlugin", "Activity Stopped - 进入后台")
                isInBackground = true
                // 注意：不要停止渲染循环，只是标记为后台状态
                // 渲染循环会检测 isInBackground 并暂停渲染，但保持相机和编码器运行
                if (rtmpStreamer?.isStreaming() == true) {
                    rtmpStreamer?.startHeartbeat()
                }
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