package com.bb.rtmp

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.Rect
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraController(private val context: Context) {
    private val TAG = "CameraController"
    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var isFrontCamera = true

    fun setInitialCameraFacing(isFront: Boolean) {
        this.isFrontCamera = isFront
    }
    private var previewSize: Size? = null
    private var previewSurface: Surface? = null
    private var encoderSurface: Surface? = null
    private var additionalSurfaces: List<Surface> = emptyList()
    private var characteristics: CameraCharacteristics? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private var stateCallback: CameraStateCallback? = null
    private var currentRequestBuilder: CaptureRequest.Builder? = null
    private var currentZoom: Float = 1.0f

    interface CameraStateCallback {
        fun onCameraOpened()
        fun onCameraError(error: String)
    }

    fun setStateCallback(callback: CameraStateCallback) {
        this.stateCallback = callback
    }

    /**
     * 获取可用的摄像头 ID 列表
     */
    fun getAvailableCameras(): List<String> {
        return try {
            cameraManager.cameraIdList.toList()
        } catch (e: Exception) {
            Log.e(TAG, "获取摄像头列表失败", e)
            emptyList()
        }
    }

    /**
     * 获取摄像头支持的分辨率
     */
    fun getSupportedResolutions(cameraId: String): List<Size> {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            // 使用 SurfaceTexture 类来获取支持的尺寸，这对于预览和 MediaCodec 编码器是通用的
            map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "获取支持的分辨率失败", e)
            emptyList()
        }
    }

    /**
     * 选择最佳分辨率
     */
    fun selectBestResolution(targetWidth: Int, targetHeight: Int, cameraId: String): Size? {
        val resolutions = getSupportedResolutions(cameraId)
        if (resolutions.isEmpty()) {
            Log.e(TAG, "相机 $cameraId 没有支持的分辨率")
            return null
        }

        Log.d(TAG, "相机 $cameraId 目标分辨率: ${targetWidth}x${targetHeight}")
        Log.d(TAG, "相机支持的分辨率数量: ${resolutions.size}")
        
        // 打印前10个支持的分辨率（用于调试）
        resolutions.take(10).forEach { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            Log.d(TAG, "  支持: ${size.width}x${size.height} (比例: ${String.format("%.2f", ratio)})")
        }

        // 1. 优先查找完全匹配的分辨率
        val exactMatch = resolutions.find { it.width == targetWidth && it.height == targetHeight }
        if (exactMatch != null) {
            Log.d(TAG, "找到完全匹配: ${exactMatch.width}x${exactMatch.height}")
            return exactMatch
        }

        // 2. 判断目标是竖屏还是横屏
        val isTargetPortrait = targetWidth < targetHeight
        val targetRatio = targetWidth.toDouble() / targetHeight.toDouble()
        
        Log.d(TAG, "目标方向: ${if (isTargetPortrait) "竖屏" else "横屏"}, 目标比例: ${String.format("%.2f", targetRatio)}")

        // 3. 如果目标是竖屏，需要交换宽高后在横屏分辨率列表中查找
        // 因为相机硬件通常只支持横屏分辨率（宽 > 高）
        val searchWidth = if (isTargetPortrait) targetHeight else targetWidth
        val searchHeight = if (isTargetPortrait) targetWidth else targetHeight
        val searchRatio = searchWidth.toDouble() / searchHeight.toDouble()
        
        Log.d(TAG, "转换为横屏查找: ${searchWidth}x${searchHeight} (比例: ${String.format("%.2f", searchRatio)})")

        // 4. 过滤分辨率（排除 1:1 的正方形分辨率，除非目标就是 1:1）
        val filteredResolutions = resolutions.filter { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            // 排除 1:1 比例（除非目标就是 1:1）
            if (Math.abs(ratio - 1.0) < 0.01 && Math.abs(searchRatio - 1.0) > 0.01) {
                false
            } else {
                true
            }
        }
        
        if (filteredResolutions.isEmpty()) {
            Log.w(TAG, "过滤后没有可用分辨率，使用原始列表")
            val bestSize = resolutions.minByOrNull {
                val ratio = it.width.toDouble() / it.height.toDouble()
                Math.abs(ratio - searchRatio)
            }
            if (bestSize != null) {
                // 如果目标是竖屏，交换回来
                val result = if (isTargetPortrait) {
                    android.util.Size(bestSize.height, bestSize.width)
                } else {
                    bestSize
                }
                Log.d(TAG, "选择最接近比例的分辨率: ${result.width}x${result.height}")
                return result
            }
            return null
        }
        
        // 5. 在横屏分辨率列表中找到最接近的
        val bestSize = filteredResolutions.minByOrNull { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            // 优先考虑宽高比，其次考虑像素总数接近
            val ratioDiff = Math.abs(ratio - searchRatio)
            val pixelDiff = Math.abs(size.width * size.height - searchWidth * searchHeight)
            ratioDiff * 1000 + pixelDiff * 0.0001 // 宽高比权重更高
        }

        if (bestSize == null) {
            return null
        }

        // 6. 直接返回硬件支持的分辨率（不交换）
        // 因为硬件通常只支持横屏分辨率，编码器和预览必须使用硬件实际支持的分辨率
        // 推流元数据会使用用户设置的分辨率（在 BbRtmpPlugin 中处理）
        Log.d(TAG, "选择最接近比例的分辨率: ${bestSize.width}x${bestSize.height} (目标比例: ${String.format("%.2f", searchRatio)}, 实际比例: ${String.format("%.2f", bestSize.width.toDouble() / bestSize.height.toDouble())})")
        return bestSize
    }

    /**
     * 获取最大支持的分辨率
     */
    fun getMaxSupportedResolution(cameraId: String): Size? {
        val resolutions = getSupportedResolutions(cameraId)
        if (resolutions.isEmpty()) return null
        
        // 返回像素总数最大的分辨率
        return resolutions.maxByOrNull { it.width * it.height }
    }

    /**
     * 选择最佳预览分辨率（优先选择 1080P，如果没有则选择最大分辨率）
     */
    fun selectBestPreviewResolution(): Size? {
        val resolutions = cameraId?.let { getSupportedResolutions(it) } ?: return null
        if (resolutions.isEmpty()) return null
        
        // 优先选择 1920x1080 (1080P)
        val target1080p = resolutions.find { it.width == 1920 && it.height == 1080 }
        if (target1080p != null) {
            return target1080p
        }
        
        // 如果没有 1080P，选择最接近 16:9 的最大分辨率
        val targetAspectRatio = 16.0 / 9.0
        var bestSize: Size? = null
        var maxPixels = 0
        var minAspectDiff = Double.MAX_VALUE
        
        for (size in resolutions) {
            val aspectRatio = size.width.toDouble() / size.height.toDouble()
            val aspectDiff = Math.abs(aspectRatio - targetAspectRatio)
            val pixels = size.width * size.height
            
            // 优先选择宽高比接近 16:9 且分辨率较大的
            if (aspectDiff < 0.1 && pixels > maxPixels) {
                maxPixels = pixels
                bestSize = size
                minAspectDiff = aspectDiff
            }
        }
        
        // 如果没找到接近 16:9 的，选择最大分辨率
        return bestSize ?: resolutions.maxByOrNull { it.width * it.height }
    }

    /**
     * 打开摄像头
     */
    suspend fun openCamera(width: Int, height: Int, previewTexture: SurfaceTexture): Boolean = withContext(Dispatchers.Main) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("等待摄像头打开超时")
            }

            // 选择摄像头
            val cameras = getAvailableCameras()
            if (cameras.isEmpty()) {
                cameraOpenCloseLock.release()
                stateCallback?.onCameraError("没有可用的摄像头")
                return@withContext false
            }

            // 选择前后摄像头
            val targetCameraId = if (isFrontCamera) {
                cameras.find { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                } ?: cameras[0]
            } else {
                cameras.find { id ->
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: cameras[0]
            }

            cameraId = targetCameraId
            
            // 保存摄像头特性，用于后续设置帧率等
            characteristics = cameraManager.getCameraCharacteristics(targetCameraId)

            // 选择最佳分辨率
            val size = selectBestResolution(width, height, targetCameraId)
            if (size == null) {
                cameraOpenCloseLock.release()
                stateCallback?.onCameraError("无法获取支持的分辨率")
                return@withContext false
            }
            previewSize = size
            Log.d(TAG, "选择摄像头分辨率: ${size.width}x${size.height}（编码器目标: ${width}x${height}）")
            
            // Camera2 要求所有输出 Surface 的分辨率必须完全匹配
            // 如果摄像头不支持编码器的分辨率，使用摄像头支持的分辨率
            // 但优先选择横屏分辨率，确保编码器使用标准横屏分辨率
            if (size.width != width || size.height != height) {
                Log.w(TAG, "摄像头分辨率 ${size.width}x${size.height} 与编码器目标 ${width}x${height} 不匹配")
                Log.w(TAG, "将使用摄像头实际分辨率: ${size.width}x${size.height}")
            }

            // 设置预览 Surface
            // 确保在主线程上设置 SurfaceTexture 大小
            previewTexture.setDefaultBufferSize(size.width, size.height)
            
            // 创建 Surface（用于摄像头预览输出到 Flutter Texture）
            previewSurface = Surface(previewTexture)
            
            // 确保 SurfaceTexture 已准备好
            if (previewTexture.isReleased) {
                Log.e(TAG, "SurfaceTexture 已释放")
                cameraOpenCloseLock.release()
                stateCallback?.onCameraError("SurfaceTexture 已释放")
                return@withContext false
            }

            // 打开摄像头
            cameraManager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    cameraOpenCloseLock.release()
                    stateCallback?.onCameraOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    stateCallback?.onCameraError("摄像头断开连接")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    camera.close()
                    cameraDevice = null
                    stateCallback?.onCameraError("摄像头错误: $error")
                }
            }, null)

            true
        } catch (e: CameraAccessException) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "打开摄像头失败", e)
            stateCallback?.onCameraError("摄像头访问失败: ${e.message}")
            false
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "打开摄像头异常", e)
            stateCallback?.onCameraError("打开摄像头异常: ${e.message}")
            false
        }
    }

    /**
     * 创建捕获会话
     */
    fun createCaptureSession(encoderSurface: Surface?) {
        this.encoderSurface = encoderSurface
        createCaptureSession(listOfNotNull(encoderSurface))
    }
    
    /**
     * 创建捕获会话（支持多个 Surface）
     */
    fun createCaptureSession(surfaces: List<Surface>) {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return

        try {
            // 构建 Surface 列表（包含预览和所有传入的 Surface）
            val allSurfaces = mutableListOf<Surface>()
            allSurfaces.addAll(surfaces)
            allSurfaces.add(preview)
            
            // 保存所有 Surface，用于后续添加到捕获请求
            additionalSurfaces = surfaces.toList()

            Log.d(TAG, "创建捕获会话，Surface 数量: ${allSurfaces.size}, 分辨率: ${previewSize?.width}x${previewSize?.height}")

            device.createCaptureSession(
                allSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "捕获会话配置成功")
                        captureSession = session
                        startPreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "配置捕获会话失败: 可能是分辨率组合不被支持")
                        stateCallback?.onCameraError("相机硬件不支持当前预览和编码的分辨率组合")
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建捕获会话失败", e)
            stateCallback?.onCameraError("创建捕获会话失败: ${e.message}")
        }
    }

    /**
     * 开始预览
     */
    private fun startPreview() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return

        try {
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            currentRequestBuilder = requestBuilder
            
            // 添加 FBO 输入 Surface（previewSurface）
            previewSurface?.let { requestBuilder.addTarget(it) }
            // 添加额外的 Surface（ImageReader 等，用于帧回调）
            additionalSurfaces.forEach { requestBuilder.addTarget(it) }
            Log.d(TAG, "捕获请求目标数: ${1 + additionalSurfaces.size} (预览 + ${additionalSurfaces.size} 个附加 Surface)")

            // 设置自动对焦
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            
            // 初始化 zoom（如果支持）
            characteristics?.let { chars ->
                val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
                if (maxZoom > 1.0f) {
                    val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    rect?.let {
                        val zoomRatio = currentZoom.coerceIn(1.0f, maxZoom)
                        val cropRegion = calculateZoomCropRegion(it, zoomRatio)
                        requestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                        Log.d(TAG, "初始化 zoom: $zoomRatio, crop region: $cropRegion")
                    }
                }
            }
            
            // 设置 Camera Orientation（关键：告知 Camera 当前屏幕方向）
            // 这样 SurfaceTexture.getTransformMatrix() 才能给出正确的方向
            // characteristics?.let { chars ->
            //     val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            //     val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                
            //     // 获取屏幕旋转角度
            //     val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            //     val displayRotation = when (windowManager?.defaultDisplay?.rotation) {
            //         Surface.ROTATION_0 -> 0
            //         Surface.ROTATION_90 -> 90
            //         Surface.ROTATION_180 -> 180
            //         Surface.ROTATION_270 -> 270
            //         else -> 0
            //     }
                
            //     // 计算 Camera 应该用的 Orientation
            //     val cameraRotation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            //         // 前置摄像头：rotation = (sensorOrientation + displayRotation) % 360
            //         (sensorOrientation + displayRotation) % 360
            //     } else {
            //         // 后置摄像头：rotation = (sensorOrientation - displayRotation + 360) % 360
            //         (sensorOrientation - displayRotation + 360) % 360
            //     }
                
            //     // 设置到 CaptureRequest（即使不是拍照，Camera2 也会用这个值来决定 buffer orientation）
            //     requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, cameraRotation)
            //     Log.d(TAG, "设置 Camera Orientation: sensor=$sensorOrientation°, display=$displayRotation°, camera=$cameraRotation°")
            // }
            
            // 设置目标帧率（30fps），避免预览画面块状
            characteristics?.let { chars ->
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                fpsRanges?.let { ranges ->
                    // 选择支持 30fps 的范围
                    val targetRange = ranges.find { it.upper >= 30 } ?: ranges.maxByOrNull { it.upper }
                    targetRange?.let {
                        requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                        Log.d(TAG, "设置帧率范围: [${it.lower}, ${it.upper}]")
                    }
                }
            }
            
            session.setRepeatingRequest(requestBuilder.build(), null, null)
            Log.d(TAG, "预览已开始 (TEMPLATE_RECORD)")
        } catch (e: Exception) {
            Log.e(TAG, "开始预览失败", e)
            stateCallback?.onCameraError("开始预览失败: ${e.message}")
        }
    }

    /**
     * 切换摄像头
     */
    suspend fun switchCamera(width: Int, height: Int, previewTexture: SurfaceTexture): Boolean {
        closeCamera()
        isFrontCamera = !isFrontCamera
        return openCamera(width, height, previewTexture)
    }

    /**
     * 切换分辨率
     */
    suspend fun changeResolution(width: Int, height: Int, previewTexture: SurfaceTexture): Boolean {
        closeCamera()
        return openCamera(width, height, previewTexture)
    }

    /**
     * 关闭摄像头
     */
    fun closeCamera() {
        try {
            // 先停止预览请求
            captureSession?.stopRepeating()
            captureSession?.abortCaptures()
        } catch (e: Exception) {
            Log.w(TAG, "停止捕获请求失败", e)
        }
        
        try {
            captureSession?.close()
            captureSession = null
            Log.d(TAG, "捕获会话已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭捕获会话失败", e)
        }

        try {
            cameraDevice?.close()
            cameraDevice = null
            Log.d(TAG, "摄像头设备已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭摄像头失败", e)
        }

        previewSurface?.release()
        previewSurface = null
        encoderSurface = null
        previewSize = null
    }

    /**
     * 判断摄像头设备是否处于打开状态
     */
    fun isCameraDeviceOpen(): Boolean = cameraDevice != null

    /**
     * 获取当前预览尺寸
     */
    fun getPreviewSize(): Size? = previewSize
    
    /**
     * 获取当前预览 Surface（用于添加到捕获会话）
     */
    fun getPreviewSurface(): Surface? = previewSurface

    /**
     * 获取当前摄像头 ID
     */
    fun getCurrentCameraId(): String? = cameraId
    
    /**
     * 获取传感器方向（度）
     */
    fun getSensorOrientation(): Int {
        return characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    }
    
    /**
     * 获取当前显示旋转角度（度）
     */
    fun getDisplayRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return when (windowManager?.defaultDisplay?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
    
    /**
     * 判断是否为前置摄像头
     */
    fun isFrontFacing(): Boolean {
        return characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    }
    
    /**
     * 获取 zoom 范围
     * @return Map 包含 minZoom, maxZoom, currentZoom
     */
    fun getZoomRange(): Map<String, Float> {
        val chars = characteristics ?: return mapOf("minZoom" to 1.0f, "maxZoom" to 1.0f, "currentZoom" to 1.0f)
        
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        val minZoom = 1.0f
        
        return mapOf(
            "minZoom" to minZoom,
            "maxZoom" to maxZoom,
            "currentZoom" to currentZoom
        )
    }
    
    /**
     * 设置 zoom
     * @param zoom zoom 值，必须在 minZoom 和 maxZoom 之间
     * @return 是否设置成功
     */
    fun setZoom(zoom: Float): Boolean {
        val device = cameraDevice ?: return false
        val session = captureSession ?: return false
        val builder = currentRequestBuilder ?: return false
        val chars = characteristics ?: return false
        
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        val minZoom = 1.0f
        
        if (zoom < minZoom || zoom > maxZoom) {
            Log.e(TAG, "Zoom 值超出范围: $zoom (范围: $minZoom - $maxZoom)")
            return false
        }
        
        try {
            val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (rect == null) {
                Log.e(TAG, "无法获取 SENSOR_INFO_ACTIVE_ARRAY_SIZE")
                return false
            }
            
            val cropRegion = calculateZoomCropRegion(rect, zoom)
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
            currentZoom = zoom
            
            session.setRepeatingRequest(builder.build(), null, null)
            Log.d(TAG, "设置 zoom: $zoom, crop region: $cropRegion")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "设置 zoom 失败", e)
            return false
        }
    }
    
    /**
     * 计算 zoom 的 crop region
     */
    private fun calculateZoomCropRegion(activeArray: Rect, zoom: Float): Rect {
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()
        val width = (activeArray.width() / zoom).toInt()
        val height = (activeArray.height() / zoom).toInt()
        
        val left = (centerX - width / 2).coerceAtLeast(activeArray.left)
        val top = (centerY - height / 2).coerceAtLeast(activeArray.top)
        val right = (left + width).coerceAtMost(activeArray.right)
        val bottom = (top + height).coerceAtMost(activeArray.bottom)
        
        return Rect(left, top, right, bottom)
    }
    
    /**
     * 计算正确的相机旋转角度（用于 GL 渲染）
     * @return 旋转角度（度）
     */
    fun calculateCameraRotation(): Int {
        val sensorOrientation = getSensorOrientation()
        val displayRotation = getDisplayRotation()
        val isFront = isFrontFacing()
        
        return if (isFront) {
            // 前置摄像头：rotation = (sensorOrientation + displayRotation) % 360
            (sensorOrientation + displayRotation) % 360
        } else {
            // 后置摄像头：rotation = (sensorOrientation - displayRotation + 360) % 360
            (sensorOrientation - displayRotation + 360) % 360
        }
    }

}

