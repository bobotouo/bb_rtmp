package com.bb.rtmp

import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.view.TextureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    // FBO 渲染循环控制标志
    private var isFboRenderLoopRunning = false

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "com.bb.rtmp/plugin")
        channel.setMethodCallHandler(this)
        textureRegistry = flutterPluginBinding.textureRegistry
        context = flutterPluginBinding.applicationContext
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        release()
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> {
                scope.launch {
                    initialize(call, result)
                }
            }
            "startStreaming" -> {
                startStreaming(result)
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
            
            android.util.Log.d("BbRtmpPlugin", "用户设置分辨率: ${userSetWidth}x${userSetHeight} (${if (isPortraitMode) "竖屏" else "横屏"})")
            android.util.Log.d("BbRtmpPlugin", "FBO 画布分辨率（标准横屏）: ${fboCanvasWidth}x${fboCanvasHeight}")
            android.util.Log.d("BbRtmpPlugin", "推流分辨率（标准横屏）: ${fboCanvasWidth}x${fboCanvasHeight}")
            android.util.Log.d("BbRtmpPlugin", "相机纹理分辨率（硬件支持）: ${cameraWidth}x${cameraHeight}")

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
            rtmpStreamer = RtmpStreamer()
            if (!rtmpStreamer!!.initialize(rtmpUrl, videoEncoder!!, audioEncoder)) {
                result.error("RTMP_INIT_FAILED", "RTMP 初始化失败", null)
                resultReplied = true
                return
            }

            // 6. 设置 RTMP 元数据（使用标准横屏分辨率，即编码器实际输出的分辨率）
            // 关键：元数据的分辨率必须匹配编码器输出的实际分辨率
            android.util.Log.d("BbRtmpPlugin", "设置 RTMP 元数据: ${fboCanvasWidth}x${fboCanvasHeight} (标准横屏分辨率，即编码器实际输出)")
            rtmpStreamer!!.setMetadata(fboCanvasWidth, fboCanvasHeight, bitrate, fps, 44100, 1)

            // 7. 初始化 OpenGL 渲染器（延迟到渲染线程中初始化）
            // 在这里只创建 GlRenderer 对象，EGL 初始化在渲染线程中进行
            glRenderer = GlRenderer()
            
            // 保存初始化参数，稍后在渲染线程中使用（作为类成员变量）
            this.glEncoderSurface = encoderSurface
            this.glFboCanvasWidth = fboCanvasWidth  // FBO 画布（标准横屏分辨率）
            this.glFboCanvasHeight = fboCanvasHeight
            this.glCameraWidth = cameraWidth
            this.glCameraHeight = cameraHeight
            
            android.util.Log.d("BbRtmpPlugin", "OpenGL 渲染器对象创建成功，将在渲染线程中初始化 EGL")
            android.util.Log.d("BbRtmpPlugin", "  FBO 画布: ${fboCanvasWidth}x${fboCanvasHeight} (标准横屏分辨率)")
            android.util.Log.d("BbRtmpPlugin", "  用户设置: ${userSetWidth}x${userSetHeight} (${if (isPortraitMode) "竖屏" else "横屏"})")
            android.util.Log.d("BbRtmpPlugin", "  相机纹理: ${cameraWidth}x${cameraHeight} (硬件支持)")

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
            
            // 如果需要 FBO 旋转，创建摄像头纹理
            // 注意：纹理创建需要在 EGL 上下文创建之后，所以这里先不创建
            // 在 onCameraOpened 回调中创建

            // 8. 配置相机回调并打开
            cameraController!!.setStateCallback(object : CameraController.CameraStateCallback {
                override fun onCameraOpened() {
                    scope.launch(Dispatchers.Main) {
                        // 创建捕获会话
                        // 始终使用 FBO：摄像头输出到预览 SurfaceTexture（Flutter 显示）和 FBO SurfaceTexture（推流）
                        // 注意：预览 SurfaceTexture 会通过 CameraController.openCamera 自动添加到捕获会话
                        
                        // 如果 FBO 未初始化（比如切换摄像头后），重新初始化
                        if (glRenderer == null || fboInputSurfaceTexture == null || previewSurfaceTexture == null) {
                            android.util.Log.d("BbRtmpPlugin", "FBO 未初始化，重新初始化")
                            
                            // 重新创建 FBO 输入 SurfaceTexture
                            fboInputSurfaceTexture = SurfaceTexture(false)
                            fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                            
                            // 重新设置预览 SurfaceTexture 大小（使用 Flutter Texture 的 SurfaceTexture）
                            val flutterTexture = textureEntry?.surfaceTexture()
                            if (flutterTexture != null) {
                                flutterTexture.setDefaultBufferSize(glFboCanvasWidth, glFboCanvasHeight)
                                previewSurfaceTexture = flutterTexture
                            }
                            
                            // 重新创建 GlRenderer
                            glRenderer = GlRenderer()
                        }
                        
                        if (glRenderer != null && fboInputSurfaceTexture != null && previewSurfaceTexture != null) {
                            // 启动 FBO 渲染循环（在渲染线程中完成所有 EGL/OpenGL 操作）
                            // 包括：初始化 EGL、初始化 OpenGL、创建纹理、绑定 SurfaceTexture、创建 Surface、添加到捕获会话
                            val encoderSurface = glEncoderSurface
                            if (encoderSurface != null) {
                                // 将预览 SurfaceTexture 绑定到 Flutter Texture
                                flutterPreviewTexture.setDefaultBufferSize(glFboCanvasWidth, glFboCanvasHeight)
                                
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
                                cameraController?.createCaptureSession(videoEncoder?.getInputSurface())
                            }
                        } else {
                            // FBO 初始化失败，回退到直接输出（不应该发生）
                            android.util.Log.e("BbRtmpPlugin", "FBO 未初始化，无法创建捕获会话")
                            cameraController?.createCaptureSession(videoEncoder?.getInputSurface())
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

            bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, cameraController!!)
            bitrateController!!.initialize(bitrate, fboCanvasWidth, fboCanvasHeight)

            // 9. 打开相机（使用硬件支持的分辨率）
            // 相机只输出到 FBO 输入 SurfaceTexture（用于 FBO 渲染）
            // FBO 渲染后输出到编码器（推流）和预览 SurfaceTexture（Flutter 显示）
            cameraController!!.openCamera(cameraWidth, cameraHeight, fboInputSurfaceTexture!!)

        } catch (e: Exception) {
            if (!resultReplied) {
                result.error("INITIALIZE_ERROR", "初始化异常: ${e.message}", null)
                resultReplied = true
            }
        }
    }

    private fun startStreaming(result: Result) {
        try {
            rtmpStreamer?.start()
            bitrateController?.start()
            
            // Start Foreground Service for background stability
            context?.let { ctx ->
                val intent = android.content.Intent(ctx, RtmpService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
            
            result.success(null)
        } catch (e: Exception) {
            result.error("START_STREAMING_ERROR", "开始推流失败: ${e.message}", null)
        }
    }

    private fun stopStreaming(result: Result) {
        try {
            rtmpStreamer?.stop()
            bitrateController?.stop()
            
            // Stop Foreground Service
            context?.let { ctx ->
                val intent = android.content.Intent(ctx, RtmpService::class.java)
                ctx.stopService(intent)
            }
            
            result.success(null)
        } catch (e: Exception) {
            result.error("STOP_STREAMING_ERROR", "停止推流失败: ${e.message}", null)
        }
    }

    private suspend fun switchCamera(result: Result) {
        try {
            val controller = cameraController ?: run {
                result.error("NOT_INITIALIZED", "未初始化", null)
                return
            }

            val size = controller.getPreviewSize() ?: run {
                result.error("NO_PREVIEW_SIZE", "无预览尺寸", null)
                return
            }

            val texture = textureEntry?.surfaceTexture() ?: run {
                result.error("NO_TEXTURE", "无纹理", null)
                return
            }

            // 停止 FBO 渲染循环（只设置标志，让渲染循环自己清理资源）
            android.util.Log.d("BbRtmpPlugin", "停止 FBO 渲染循环（切换摄像头前）")
            isFboRenderLoopRunning = false
            
            // 等待渲染循环清理完成（增加等待时间，确保资源完全释放）
            kotlinx.coroutines.delay(500)

            // 创建新的 FBO 输入 SurfaceTexture
            // 注意：必须在主线程创建，且在 openCamera 之前
            fboInputSurfaceTexture = SurfaceTexture(false)
            
            // 使用用户设置的分辨率进行切换（CameraController 会自动选择最佳硬件分辨率）
            // 注意：传入 fboInputSurfaceTexture 而不是 Flutter 的 texture
            val success = controller.switchCamera(userSetWidth, userSetHeight, fboInputSurfaceTexture!!)
            
            if (success) {
                // 切换成功，获取新的相机实际分辨率并更新
                val newSize = controller.getPreviewSize()
                if (newSize != null) {
                    this.cameraOutputWidth = newSize.width
                    this.cameraOutputHeight = newSize.height
                    android.util.Log.d("BbRtmpPlugin", "切换摄像头后，相机实际输出分辨率: ${newSize.width}x${newSize.height}")
                }
                // CaptureSession 会在 onCameraOpened 回调中自动创建
                // 在 onCameraOpened 回调中会重新初始化 FBO
                result.success(null)
            } else {
                result.error("SWITCH_CAMERA_FAILED", "切换摄像头失败", null)
            }
        } catch (e: Exception) {
            result.error("SWITCH_CAMERA_ERROR", "切换摄像头异常: ${e.message}", null)
        }
    }
    
    /**
     * 停止 FBO 渲染循环并释放资源
     * 注意：这个方法只设置标志，实际的资源释放由渲染循环在 EGL 上下文中完成
     */
    private fun stopFboRenderLoop() {
        android.util.Log.d("BbRtmpPlugin", "停止 FBO 渲染循环")
        isFboRenderLoopRunning = false
        
        // 注意：实际的资源释放由渲染循环在 EGL 上下文中完成
        // fboInputSurfaceTexture 由 CameraController 管理，不需要手动释放
        // previewSurfaceTexture 是 Flutter Texture 的 SurfaceTexture，由 Flutter 管理，不需要手动释放
        // 这里只清空引用，实际的释放由各自的拥有者完成
        fboInputSurfaceTexture = null
        previewSurfaceTexture = null
        
        // GlRenderer 和纹理的释放由渲染循环完成
        android.util.Log.d("BbRtmpPlugin", "FBO 渲染循环停止标志已设置")
    }

    private val lifecycleCallbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: android.app.Activity) {
        }

        override fun onActivityResumed(activity: android.app.Activity) {
        }

        override fun onActivityStarted(activity: android.app.Activity) {
            if (activity == this@BbRtmpPlugin.activity) {
                android.util.Log.d("BbRtmpPlugin", "App entering foreground (Activity Started) - Re-triggering video pipeline")
                isInBackground = false
                rtmpStreamer?.stopHeartbeat()
                
                // Video Resume Fix: Re-trigger camera session and request keyframe
                if (rtmpStreamer?.isStreaming() == true) {
                    val controller = cameraController
                    if (controller != null) {
                        scope.launch(Dispatchers.Main) {
                            if (!controller.isCameraDeviceOpen()) {
                                android.util.Log.w("BbRtmpPlugin", "Camera device was closed by OS, performing full re-open")
                                
                                // Stop old loop and clean up references to ensure a fresh start
                                stopFboRenderLoop()
                                glRenderer = null
                                
                                // Re-create the input texture (CameraController will attach it to GL context later)
                                fboInputSurfaceTexture = SurfaceTexture(false)
                                fboInputSurfaceTexture!!.setDefaultBufferSize(glCameraWidth, glCameraHeight)
                                
                                // Full re-open flow
                                val success = controller.openCamera(glCameraWidth, glCameraHeight, fboInputSurfaceTexture!!)
                                if (success) {
                                    android.util.Log.d("BbRtmpPlugin", "Camera re-opened successfully on resume")
                                }
                            } else {
                                android.util.Log.d("BbRtmpPlugin", "Requesting keyframe and re-creating camera capture session for resume")
                                videoEncoder?.requestKeyFrame()
                                try {
                                    controller.createCaptureSession(emptyList<android.view.Surface>())
                                    android.util.Log.d("BbRtmpPlugin", "Camera capture session re-triggered successfully")
                                } catch (e: Exception) {
                                    android.util.Log.e("BbRtmpPlugin", "Failed to re-trigger camera capture session", e)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            if (activity == this@BbRtmpPlugin.activity) {
                android.util.Log.d("BbRtmpPlugin", "App entering background (Activity Stopped)")
                isInBackground = true
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

    // Since we don't have a direct "appDidEnterBackground" in ActivityAware, 
    // we can use the result of getStatus or other methods to check if we are minimized
    // OR ideally, we can use the Lifecycle library if available.
    // For now, let's add a manual check or use the Activity's lifecycle.

    private suspend fun changeResolution(call: MethodCall, result: Result) {
        try {
            var targetWidth = call.argument<Int>("width") ?: run {
                result.error("INVALID_ARGUMENT", "width 不能为空", null)
                return
            }
            var targetHeight = call.argument<Int>("height") ?: run {
                result.error("INVALID_ARGUMENT", "height 不能为空", null)
                return
            }

            val controller = cameraController ?: run {
                result.error("NOT_INITIALIZED", "未初始化", null)
                return
            }

            val texture = textureEntry?.surfaceTexture() ?: run {
                result.error("NO_TEXTURE", "无纹理", null)
                return
            }

            // 判断用户设置的方向
            val isPortrait = targetWidth < targetHeight
            
            // 保存用户设置的分辨率和方向（用于预览显示）
            this.isPortraitMode = isPortrait
            this.userSetWidth = targetWidth
            this.userSetHeight = targetHeight
            
            // FBO 画布分辨率 = 标准横屏分辨率（始终宽>=高，如 1920x1080）
            // 推流和预览 Surface 都使用标准横屏分辨率
            val fboCanvasWidth = kotlin.math.max(targetWidth, targetHeight)  // 标准横屏：宽 >= 高
            val fboCanvasHeight = kotlin.math.min(targetWidth, targetHeight)
            
            // 保存推流分辨率（标准横屏分辨率）
            this.streamWidth = fboCanvasWidth
            this.streamHeight = fboCanvasHeight
            
            android.util.Log.d("BbRtmpPlugin", "用户设置分辨率: ${targetWidth}x${targetHeight} (${if (isPortrait) "竖屏" else "横屏"})")
            android.util.Log.d("BbRtmpPlugin", "FBO 画布分辨率（标准横屏）: ${fboCanvasWidth}x${fboCanvasHeight}")
            android.util.Log.d("BbRtmpPlugin", "推流分辨率（标准横屏）: ${fboCanvasWidth}x${fboCanvasHeight}")

            // 2. 停止推流和编码
            rtmpStreamer?.stop()
            bitrateController?.stop()

            // 3. 关闭旧的捕获会话（必须在主线程）
            scope.launch(Dispatchers.Main) {
                try {
                    controller.closeCamera()
                    
                    // 4. 释放旧编码器
                    videoEncoder?.release()
                    audioEncoder?.release()

                    // 5. 重新初始化编码器（使用标准横屏分辨率）
                    val bitrate = bitrateController?.getCurrentBitrate() ?: 2000000
                    videoEncoder = VideoEncoder()
                    val encoderSurface = videoEncoder!!.initialize(fboCanvasWidth, fboCanvasHeight, bitrate, 30)
                    if (encoderSurface == null) {
                        result.error("ENCODER_INIT_FAILED", "视频编码器初始化失败", null)
                        return@launch
                    }

                    // 6. 重新初始化音频编码器
                    audioEncoder?.let {
                        val audio = AudioEncoder()
                        if (audio.initialize()) {
                            audioEncoder = audio
                        }
                    }

                    // 7. 重新初始化 RTMP
                    rtmpStreamer?.release()
                    rtmpStreamer = RtmpStreamer()
                    if (!rtmpStreamer!!.initialize(rtmpUrl, videoEncoder!!, audioEncoder)) {
                        result.error("RTMP_INIT_FAILED", "RTMP 重新初始化失败", null)
                        return@launch
                    }

                    // 8. 设置元数据（使用标准横屏分辨率）
                    val audioSampleRate = audioEncoder?.getSampleRate() ?: 44100
                    val audioChannels = audioEncoder?.getChannelCount() ?: 1
                    rtmpStreamer!!.setMetadata(fboCanvasWidth, fboCanvasHeight, bitrate, 30, audioSampleRate, audioChannels)

                    // 9. 更新码率控制器
                    bitrateController = BitrateController(videoEncoder!!, rtmpStreamer!!, controller)
                    bitrateController!!.initialize(bitrate, fboCanvasWidth, fboCanvasHeight)

                    // 10. 更新预览纹理大小（使用标准横屏分辨率，因为 Camera2 要求所有 Surface 分辨率匹配）
                    texture.setDefaultBufferSize(fboCanvasWidth, fboCanvasHeight)
                    
                    // 11. 更新 OpenGL 参数
                    this@BbRtmpPlugin.glFboCanvasWidth = fboCanvasWidth
                    this@BbRtmpPlugin.glFboCanvasHeight = fboCanvasHeight
                    this@BbRtmpPlugin.glEncoderSurface = encoderSurface

                    // 11. 重新打开相机（使用推流分辨率）
                    controller.setStateCallback(object : CameraController.CameraStateCallback {
                        override fun onCameraOpened() {
                            scope.launch(Dispatchers.Main) {
                                // 获取相机实际选择的分辨率并更新
                                val actualSize = controller.getPreviewSize()
                                if (actualSize != null) {
                                    this@BbRtmpPlugin.cameraOutputWidth = actualSize.width
                                    this@BbRtmpPlugin.cameraOutputHeight = actualSize.height
                                    android.util.Log.d("BbRtmpPlugin", "切换分辨率后，相机实际输出分辨率: ${actualSize.width}x${actualSize.height}")
                                }
                                
                                // 创建捕获会话
                                controller.createCaptureSession(encoderSurface)
                                result.success(null)
                            }
                        }

                        override fun onCameraError(error: String) {
                            result.error("CAMERA_ERROR", "切换分辨率时相机错误: $error", null)
                        }
                    })

                    // 12. 打开相机（使用推流分辨率）
                    // 12. 打开相机（使用相机硬件支持的分辨率，由 CameraController 自动选择）
                    // 注意：这里传入的是用户设置的分辨率，用于 CameraController 选择最接近的硬件分辨率
                    val success = controller.openCamera(targetWidth, targetHeight, texture)
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
            val bitrate = call.argument<Int>("bitrate") ?: run {
                result.error("INVALID_ARGUMENT", "bitrate 不能为空", null)
                return
            }

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
            
            // 推流分辨率（用户设置的分辨率，FBO 画布分辨率）
            val streamWidthFinal = this.streamWidth
            val streamHeightFinal = this.streamHeight
            
            // 预览分辨率（用于计算预览宽高比）
            // 预览显示的是 FBO 输出（标准横屏分辨率），但需要根据用户设置的方向来显示宽高比
            // 返回用户设置的分辨率，用于 Flutter 端正确显示宽高比
            // 如果用户设置的是竖屏（1080x1920），预览应该显示为竖屏比例（9:16）
            // 如果用户设置的是横屏（1920x1080），预览应该显示为横屏比例（16:9）
            // 但实际 FBO 和预览 Surface 都是标准横屏分辨率（1920x1080）
            // 确保预览分辨率符合当前方向
            // 如果 isPortraitMode 为 true，确保 w < h
            // 如果 isPortraitMode 为 false，确保 w > h
            // 预览分辨率（用于计算预览宽高比）
            // 直接返回 FBO 画布分辨率（标准横屏），确保 Flutter AspectRatio 与纹理缓冲区一致
            // 避免 Flutter 强制拉伸导致变形
            val finalPreviewWidth = streamWidthFinal
            val finalPreviewHeight = streamHeightFinal

            android.util.Log.d("BbRtmpPlugin", "getStatus: isPortraitMode=$isPortraitMode, userSet=${userSetWidth}x${userSetHeight}, FBO=${streamWidthFinal}x${streamHeightFinal}, preview=${finalPreviewWidth}x${finalPreviewHeight}")
            android.util.Log.d("BbRtmpPlugin", "getStatus: streamWidth=$streamWidth, streamHeight=$streamHeight")

            val status = mapOf(
                "isStreaming" to isStreaming,
                "currentBitrate" to currentBitrate,
                "fps" to 30.0, // TODO: 从编码器获取实际帧率
                "width" to streamWidthFinal, // 推流分辨率（用户设置，FBO 画布）
                "height" to streamHeightFinal, // 推流分辨率（用户设置，FBO 画布）
                "previewWidth" to finalPreviewWidth, // 预览宽度（用于计算宽高比）
                "previewHeight" to finalPreviewHeight, // 预览高度（用于计算宽高比）
                "cameraId" to (cameraId ?: "")
            )

            result.success(status)
        } catch (e: Exception) {
            result.error("GET_STATUS_ERROR", "获取状态失败: ${e.message}", null)
        }
    }

    /**
     * 启动 FBO 渲染循环
     * 注意：在渲染线程中初始化 EGL 和创建纹理
     * @param inputSurfaceTexture FBO 输入 SurfaceTexture（相机输出）
     * @param previewSurfaceTexture 预览 SurfaceTexture（FBO 输出，用于 Flutter 显示）
     * @param encoderSurface 编码器 Surface（FBO 输出，用于推流）
     * @param canvasWidth FBO 画布宽度
     * @param canvasHeight FBO 画布高度
     * @param cameraWidth 相机纹理宽度
     * @param cameraHeight 相机纹理高度
     */
    private fun startFboRenderLoop(
        inputSurfaceTexture: SurfaceTexture,
        previewSurfaceTexture: SurfaceTexture,
        encoderSurface: Surface,
        canvasWidth: Int,
        canvasHeight: Int,
        cameraWidth: Int,
        cameraHeight: Int
    ) {
        scope.launch(Dispatchers.Default) {
            val renderer = glRenderer ?: return@launch
            val ctx = context ?: return@launch
            
            android.util.Log.d("BbRtmpPlugin", "启动 FBO 渲染循环（在渲染线程中）")
            
            // 创建预览 Surface（从预览 SurfaceTexture）
            var previewSurface: Surface? = null
            try {
                previewSurface = Surface(previewSurfaceTexture)
                
                // 在渲染线程中初始化 EGL（使用编码器 Surface 和预览 Surface）
                renderer.initEgl(encoderSurface, previewSurface)
                android.util.Log.d("BbRtmpPlugin", "EGL 初始化成功（在渲染线程中，双 EGLSurface 方案）")
            } catch (e: Exception) {
                android.util.Log.e("BbRtmpPlugin", "EGL 初始化失败", e)
                previewSurface?.release()
                return@launch
            }
            
            // 在渲染线程中初始化 OpenGL
            try {
                renderer.initGl(canvasWidth, canvasHeight)
                android.util.Log.d("BbRtmpPlugin", "OpenGL 初始化成功（在渲染线程中）")
            } catch (e: Exception) {
                android.util.Log.e("BbRtmpPlugin", "OpenGL 初始化失败", e)
                return@launch
            }
            
            // 在渲染线程中创建摄像头纹理（必须在 EGL 上下文绑定后）
            val textures = IntArray(1)
            android.opengl.GLES20.glGenTextures(1, textures, 0)
            cameraTextureId = textures[0]
            
            if (cameraTextureId == 0) {
                android.util.Log.e("BbRtmpPlugin", "无法创建摄像头纹理，停止渲染循环")
                return@launch
            }
            
            // 设置纹理参数
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            
            android.util.Log.d("BbRtmpPlugin", "摄像头纹理已创建: $cameraTextureId")
            
            // 将 FBO 输入 SurfaceTexture 绑定到纹理（必须在创建 Surface 之前）
            try {
                inputSurfaceTexture.attachToGLContext(cameraTextureId)
                android.util.Log.d("BbRtmpPlugin", "FBO 输入 SurfaceTexture 已绑定到纹理 $cameraTextureId")
            } catch (e: Exception) {
                android.util.Log.e("BbRtmpPlugin", "绑定 FBO 输入 SurfaceTexture 失败", e)
                return@launch
            }
            
            // 将 FBO texture 绑定到预览 SurfaceTexture（用于 Flutter 显示）
            // 注意：previewSurfaceTexture 是 Flutter 的 SurfaceTexture，需要从 FBO texture 更新
            // 这里我们需要创建一个额外的渲染步骤，将 FBO texture 渲染到 previewSurfaceTexture
            // 但根据单 EGLSurface 方案，preview 应该通过 SurfaceTexture 直接消费 FBO texture
            // 实际上，我们需要在 renderFrame 之后，将 FBO texture 的内容更新到 previewSurfaceTexture
            // 这需要额外的渲染步骤，但不在 EGLSurface 中，而是在当前的 EGLContext 中
            
            // 在主线程创建 Surface 并添加到相机捕获会话
            // 相机只输出到 FBO 输入 SurfaceTexture
            // 注意：CameraController.openCamera 已经创建了 previewSurface（来自 fboInputSurfaceTexture）
            // createCaptureSession 会自动添加 previewSurface，我们传入空列表即可
            scope.launch(Dispatchers.Main) {
                // 传入空列表，createCaptureSession 会自动添加 previewSurface（FBO 输入 Surface）
                cameraController?.createCaptureSession(emptyList<Surface>())
                android.util.Log.d("BbRtmpPlugin", "FBO 输入 Surface（来自 CameraController.previewSurface）已添加到相机捕获会话")
            }
            
            // 设置渲染循环运行标志
            isFboRenderLoopRunning = true
            
            var frameCount = 0L
            var lastLogTime = System.currentTimeMillis()
            var errorCount = 0L
            var lastTimestampNs = 0L  // 用于确保时间戳递增
            
            while (isFboRenderLoopRunning && glRenderer != null && cameraTextureId != 0) {
                try {
                    val frameStartTime = System.currentTimeMillis()
                    
                    // 注意：makeCurrent 只需要在循环开始前调用一次
                    // 在循环中重复调用可能导致 EGL_BAD_ACCESS 错误
                    
                    // 更新 FBO 输入 SurfaceTexture（获取新的摄像头帧）
                    // 注意：updateTexImage 必须在正确的线程中调用，且需要在 EGL 上下文中
                    // 使用 onFrameAvailable 驱动，而不是轮询 + sleep
                    try {
                        inputSurfaceTexture.updateTexImage()
                    } catch (e: Exception) {
                        errorCount++
                        if (errorCount % 30 == 0L) {
                            android.util.Log.w("BbRtmpPlugin", "updateTexImage 失败 (已失败 $errorCount 次): ${e.message}")
                        }
                        // 不再使用 Thread.sleep，等待下一帧通过 onFrameAvailable 触发
                        continue
                    }
                    
                    // 1. Skip GPU work if in background to save power and avoid potential EGL errors
                    if (isInBackground) {
                        frameCount++
                        // Controlled sleep to avoid busy loop in background
                        val targetFrameTime = 1000L / 30
                        Thread.sleep(targetFrameTime)
                        continue
                    }
                    
                    // 获取帧时间戳（纳秒）- 必须在 updateTexImage() 之后立即获取
                    // 这对于 MediaCodec 很重要，如果时间戳为 0 或倒退，编码器可能无法正确处理帧
                    val timestampNs = inputSurfaceTexture.timestamp
                    
                    // 确保时间戳递增（防止时间戳倒退导致编码器丢帧）
                    val finalTimestampNs = if (timestampNs > 0 && timestampNs > lastTimestampNs) {
                        timestampNs
                    } else if (timestampNs > 0 && timestampNs <= lastTimestampNs) {
                        // 时间戳倒退，使用上一个时间戳 + 增量（约 33ms for 30fps）
                        lastTimestampNs + 33_333_333L  // 约 33.33ms
                    } else {
                        // 时间戳为 0，使用系统时间作为后备方案
                        val systemTime = System.nanoTime()
                        if (systemTime > lastTimestampNs) {
                            systemTime
                        } else {
                            lastTimestampNs + 33_333_333L
                        }
                    }
                    
                    // 更新最后的时间戳
                    lastTimestampNs = finalTimestampNs
                    
                    // 调试：每 30 帧打印一次时间戳
                    if (frameCount % 30 == 0L) {
                        android.util.Log.d("BbRtmpPlugin", "帧时间戳: SurfaceTexture=$timestampNs ns, 使用=${if (timestampNs > 0) "SurfaceTexture" else "System.nanoTime()"} ($finalTimestampNs ns)")
                    }
                    
                    // 获取 FBO 输入 SurfaceTexture 的变换矩阵（用于处理坐标变换）
                    val transformMatrix = FloatArray(16)
                    inputSurfaceTexture.getTransformMatrix(transformMatrix)
                    
                    // 调试：每 30 帧打印一次变换矩阵和纹理信息（用于验证相机纹理的实际尺寸和方向）
                    if (frameCount % 30 == 0L) {
                        android.util.Log.d("BbRtmpPlugin", "=== 相机硬件纹理信息 ===")
                        android.util.Log.d("BbRtmpPlugin", "硬件纹理尺寸: ${glCameraWidth}x${glCameraHeight} (宽${if (glCameraWidth > glCameraHeight) ">" else if (glCameraWidth < glCameraHeight) "<" else "="}高)")
                        android.util.Log.d("BbRtmpPlugin", "SurfaceTexture 变换矩阵 2x2: [${transformMatrix[0]}, ${transformMatrix[1]}, ${transformMatrix[4]}, ${transformMatrix[5]}]")
                        
                        // 分析变换矩阵，判断纹理内容的方向
                        // 变换矩阵 [a, b, c, d] 表示：
                        // x' = a*x + c*y
                        // y' = b*x + d*y
                        val a = transformMatrix[0]
                        val b = transformMatrix[1]
                        val c = transformMatrix[4]
                        val d = transformMatrix[5]
                        
                        // 判断旋转角度
                        when {
                            a == 1.0f && b == 0.0f && c == 0.0f && d == 1.0f -> {
                                android.util.Log.d("BbRtmpPlugin", "纹理内容方向: 无旋转（0度）")
                            }
                            a == 0.0f && b == -1.0f && c == 1.0f && d == 0.0f -> {
                                android.util.Log.d("BbRtmpPlugin", "纹理内容方向: 逆时针旋转90度（竖屏内容）")
                            }
                            a == -1.0f && b == 0.0f && c == 0.0f && d == -1.0f -> {
                                android.util.Log.d("BbRtmpPlugin", "纹理内容方向: 旋转180度")
                            }
                            a == 0.0f && b == 1.0f && c == -1.0f && d == 0.0f -> {
                                android.util.Log.d("BbRtmpPlugin", "纹理内容方向: 顺时针旋转90度（竖屏内容）")
                            }
                            else -> {
                                android.util.Log.d("BbRtmpPlugin", "纹理内容方向: 未知变换 (a=$a, b=$b, c=$c, d=$d)")
                            }
                        }
                        android.util.Log.d("BbRtmpPlugin", "=========================")
                    }
                    
                    // 渲染：直接将相机纹理贴到 FBO 画布上（不做任何旋转、裁剪、改变）
                    try {
                        // 调试：每 30 帧打印一次渲染信息
                        if (frameCount % 30 == 0L) {
                            android.util.Log.d("BbRtmpPlugin", "=== FBO 渲染信息 ===")
                            android.util.Log.d("BbRtmpPlugin", "FBO 画布尺寸: ${glFboCanvasWidth}x${glFboCanvasHeight}")
                            android.util.Log.d("BbRtmpPlugin", "相机纹理尺寸: ${glCameraWidth}x${glCameraHeight}")
                        }
                        
                        // 计算渲染到 FBO 时需要的旋转角度
                        // 关键：Camera 的方向永远由「传感器 + Display Rotation」决定，和 isPortraitMode 无关
                        // 必须根据 sensorOrientation 和 displayRotation 计算正确的旋转角度
                        val controller = cameraController
                        var rotation = 0
                        var isInputContentPortrait = false
                        
                        if (controller != null) {
                            val displayRotation = controller.getDisplayRotation()
                            val sensorOrientation = controller.getSensorOrientation()
                            val isFront = controller.isFrontFacing()
                            
                            // 1. 计算输入内容是否为竖屏 (isInputContentPortrait)
                            // 逻辑：如果 (Sensor - Display) % 180 == 90，则是竖屏
                            // 例如：
                            // Portrait (Display 0):
                            //   Front (Sensor 270): |270 - 0| = 270 % 180 = 90 -> Portrait
                            //   Back (Sensor 90): |90 - 0| = 90 % 180 = 90 -> Portrait
                            // Landscape (Display 90):
                            //   Front (Sensor 270): |270 - 90| = 180 % 180 = 0 -> Landscape
                            //   Back (Sensor 90): |90 - 90| = 0 % 180 = 0 -> Landscape
                            val relativeRotation = kotlin.math.abs(sensorOrientation - displayRotation)
                            isInputContentPortrait = (relativeRotation % 180) == 90
                            
                            // 2. 计算旋转角度 (rotation)
                            // 根据用户反馈调整：
                            // Front Portrait: 0 (User said upright)
                            // Back Portrait: 90 (User said sideways, needs 90 CW to fix?)
                            // Landscape: 270 (User said sideways, needs -90/270 CW to fix?)
                            
                            rotation = if (isFront) {
                                if (displayRotation == 90 || displayRotation == 270) {
                                    270 // Landscape: Rotate -90 (CW)
                                } else {
                                    0   // Portrait: No rotation
                                }
                            } else {
                                // Back Camera
                                if (displayRotation == 90 || displayRotation == 270) {
                                    270 // Landscape: Rotate -90 (CW)
                                } else {
                                    0   // Portrait: No rotation (User reported 90 was causing 90deg CW rotation)
                                }
                            }

                            if (frameCount % 30 == 0L) {
                                android.util.Log.d("BbRtmpPlugin", "=== 旋转角度计算 ===")
                                android.util.Log.d("BbRtmpPlugin", "sensorOrientation: ${sensorOrientation}°")
                                android.util.Log.d("BbRtmpPlugin", "displayRotation: ${displayRotation}°")
                                android.util.Log.d("BbRtmpPlugin", "isFrontCamera: $isFront")
                                android.util.Log.d("BbRtmpPlugin", "isInputContentPortrait: $isInputContentPortrait")
                                android.util.Log.d("BbRtmpPlugin", "finalRotation: ${rotation}°")
                            }
                        } else {
                            android.util.Log.w("BbRtmpPlugin", "cameraController 为空，使用默认旋转 0°")
                        }
                        
                        // 使用 FBO 渲染：先渲染到 FBO，然后输出到编码器和预览 Surface（只渲染一次，性能更好）
                        renderer.renderFrame(
                            cameraTexture = cameraTextureId,
                            stMatrix = transformMatrix,
                            videoWidth = glCameraWidth,
                            videoHeight = glCameraHeight,
                            mode = GlRenderer.ScaleMode.FIT,
                            extraRotation = rotation,
                            isInputContentPortrait = isInputContentPortrait,
                            timestampNs = finalTimestampNs
                        )
                        
                        frameCount++
                        
                        val currentTime = System.currentTimeMillis()
                        
                        // 每 1 秒打印一次日志
                        if (currentTime - lastLogTime >= 1000) {
                            android.util.Log.d("BbRtmpPlugin", "FBO 渲染: 帧数=$frameCount, 错误=$errorCount, swapBuffers成功")
                            lastLogTime = currentTime
                            frameCount = 0
                        }
                    } catch (e: Exception) {
                        errorCount++
                        if (errorCount % 30 == 0L) {
                            android.util.Log.e("BbRtmpPlugin", "渲染失败 (已失败 $errorCount 次)", e)
                        }
                        // 继续运行，不中断
                    }
                    
                    // 控制帧率（30fps），避免时间戳倒退
                    // 虽然不使用 Thread.sleep，但需要确保不会过快渲染导致时间戳问题
                    val frameTime = System.currentTimeMillis() - frameStartTime
                    val targetFrameTime = 1000L / 30  // 30fps
                    val sleepTime = targetFrameTime - frameTime
                    if (sleepTime > 0) {
                        // 使用短暂的 sleep 来控制帧率，避免时间戳倒退
                        Thread.sleep(sleepTime)
                    }
                } catch (e: Exception) {
                    errorCount++
                    android.util.Log.e("BbRtmpPlugin", "FBO 渲染循环异常 (已失败 $errorCount 次)", e)
                    // 继续运行，不中断
                }
            }
            
            // 清理纹理（如果循环正常结束）
            if (cameraTextureId != 0) {
                try {
                    // 释放纹理（在 EGL 上下文中）
                    // 注意：需要先检查 SurfaceTexture 是否有效，避免在已废弃的 SurfaceTexture 上调用 detachFromGLContext
                    try {
                        if (!inputSurfaceTexture.isReleased) {
                            inputSurfaceTexture.detachFromGLContext()
                        }
                        android.opengl.GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
                    } catch (e: Exception) {
                        android.util.Log.w("BbRtmpPlugin", "释放纹理失败", e)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BbRtmpPlugin", "清理摄像头纹理失败", e)
                }
                cameraTextureId = 0
            }
            
            // 释放预览 Surface
            try {
                previewSurface?.release()
            } catch (e: Exception) {
                android.util.Log.w("BbRtmpPlugin", "释放预览 Surface 失败", e)
            }
            
            // 释放 GlRenderer（在 EGL 上下文中）
            try {
                glRenderer?.release()
                glRenderer = null
                android.util.Log.d("BbRtmpPlugin", "GlRenderer 已释放")
            } catch (e: Exception) {
                android.util.Log.w("BbRtmpPlugin", "释放 GlRenderer 失败", e)
            }
            
            android.util.Log.d("BbRtmpPlugin", "FBO 渲染循环已停止，总帧数: $frameCount")
        }
    }

    private fun release() {
        try {
            // 停止 FBO 渲染循环
            stopFboRenderLoop()
            
            // Stop Foreground Service
            context?.let { ctx ->
                val intent = android.content.Intent(ctx, RtmpService::class.java)
                ctx.stopService(intent)
            }
            
            bitrateController?.release()
            rtmpStreamer?.release()
            videoEncoder?.release()
            audioEncoder?.release()
            cameraController?.closeCamera()

            textureEntry?.release()

            bitrateController = null
            rtmpStreamer = null
            videoEncoder = null
            audioEncoder = null
            cameraController = null
            textureEntry = null
            streamWidth = 0
            streamHeight = 0
        } catch (e: Exception) {
            // 忽略释放错误
        }
    }
}

