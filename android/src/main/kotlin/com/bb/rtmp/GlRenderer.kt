package com.bb.rtmp

import android.opengl.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 修复版 OpenGL ES 渲染器
 * 支持 Cover (全屏铺满) 和 Fit (比例适配)
 */
class GlRenderer {
    private val TAG = "GlRenderer"

    // 定义显示模式
    enum class ScaleMode {
        FIT,   // 完整显示画面，比例不一致时留黑边 (Contain)
        COVER  // 撑满全屏，比例不一致时裁剪边缘 (Center Crop)
    }

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglEncoderSurface: EGLSurface? = null  // 编码器 Surface（主要的 EGLSurface）
    private var eglPreviewSurface: EGLSurface? = null  // 预览 Surface（临时的 EGLSurface，用于将 FBO texture 渲染到 previewSurfaceTexture）
    
    // FBO 相关 (双缓冲)
    private val fboTextureIds = IntArray(2)  // FBO 纹理 IDs
    private val fboFrameBuffers = IntArray(2)  // FBO Frame Buffer Objects
    private var currentFboIndex = 0  // 当前使用的 FBO 索引 (Ping-Pong)
    private var fboProgramHandle = 0  // 用于从 FBO 读取的 Shader Program
    
    private var programHandle = 0
    private var textureHandle = 0
    
    private var canvasWidth = 0
    private var canvasHeight = 0
    
    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uVertexTransform;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = uVertexTransform * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES uTexture;
        uniform mat4 uTextureTransform;
        void main() {
            vec4 transformedCoord = uTextureTransform * vec4(vTexCoord, 0.0, 1.0);
            gl_FragColor = texture2D(uTexture, transformedCoord.xy);
        }
    """.trimIndent()

    private val vertexCoords = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    )

    private val texCoords = floatArrayOf(
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    )

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var vertexTransformHandle = 0
    private var textureTransformHandle = 0
    
    // FBO 读取相关的 Handle
    private var fboPositionHandle = 0
    private var fboTexCoordHandle = 0
    private var fboTextureHandle = 0

    /**
     * 初始化 EGL，使用编码器 Surface 和预览 Surface（双 EGLSurface 方案，但只切换一次）
     * @param encoderSurface 编码器 Surface（用于推流）
     * @param previewSurface 预览 Surface（从 previewSurfaceTexture 创建，用于将 FBO texture 渲染到预览）
     * @param sharedContext 共享的 EGL Context
     */
    fun initEgl(encoderSurface: android.view.Surface, previewSurface: android.view.Surface? = null, sharedContext: EGLContext = EGL14.EGL_NO_CONTEXT) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], sharedContext, contextAttribs, 0)
        
        // 创建编码器 Surface
        eglEncoderSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        
        // 创建预览 Surface（如果提供）
        if (previewSurface != null) {
            eglPreviewSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], previewSurface, intArrayOf(EGL14.EGL_NONE), 0)
        }
        
        // 绑定到编码器 Surface（主要的渲染目标）
        EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext)
    }

    fun initGl(canvasWidth: Int, canvasHeight: Int) {
        this.canvasWidth = canvasWidth
        this.canvasHeight = canvasHeight

        // 初始化主渲染 Shader（用于渲染相机纹理到 FBO）
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programHandle = GLES20.glCreateProgram()
        GLES20.glAttachShader(programHandle, vertexShader)
        GLES20.glAttachShader(programHandle, fragmentShader)
        GLES20.glLinkProgram(programHandle)

        positionHandle = GLES20.glGetAttribLocation(programHandle, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programHandle, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(programHandle, "uTexture")
        vertexTransformHandle = GLES20.glGetUniformLocation(programHandle, "uVertexTransform")
        textureTransformHandle = GLES20.glGetUniformLocation(programHandle, "uTextureTransform")

        vertexBuffer = ByteBuffer.allocateDirect(vertexCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(vertexCoords); position(0)
        }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(texCoords); position(0)
        }
        
        // 创建 FBO 纹理和 Frame Buffer (双缓冲)
        GLES20.glGenTextures(2, fboTextureIds, 0)
        GLES20.glGenFramebuffers(2, fboFrameBuffers, 0)

        for (i in 0 until 2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, canvasWidth, canvasHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboFrameBuffers[i])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureIds[i], 0)

            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw RuntimeException("FBO [$i] 初始化失败: $status")
            }
        }
        
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        
        // 初始化 FBO 读取 Shader（用于从 FBO 读取到 Surface）
        val fboVertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        
        val fboFragmentShaderCode = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
        
        val fboVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, fboVertexShaderCode)
        val fboFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fboFragmentShaderCode)
        fboProgramHandle = GLES20.glCreateProgram()
        GLES20.glAttachShader(fboProgramHandle, fboVertexShader)
        GLES20.glAttachShader(fboProgramHandle, fboFragmentShader)
        GLES20.glLinkProgram(fboProgramHandle)
        
        fboPositionHandle = GLES20.glGetAttribLocation(fboProgramHandle, "aPosition")
        fboTexCoordHandle = GLES20.glGetAttribLocation(fboProgramHandle, "aTexCoord")
        fboTextureHandle = GLES20.glGetUniformLocation(fboProgramHandle, "uTexture")
    }

    /**
     * 渲染相机纹理到 FBO（只渲染一次）
     * @param mode 传入 ScaleMode.FIT 或 ScaleMode.COVER
     * @param extraRotation 额外的旋转角度（0, 90, 180, 270），用于横屏时旋转内容
     * @param isInputContentPortrait 输入内容是否为竖屏（用于计算宽高比）
     */
    /**
     * @param readTarget 若需读像素：传入 capacity>=width*height*4 的 DirectByteBuffer，在渲染完成后、解绑 FBO 前立即读取
     */
    fun drawFrameToFbo(cameraTexture: Int, stMatrix: FloatArray, videoWidth: Int, videoHeight: Int, mode: ScaleMode, extraRotation: Int = 0, isInputContentPortrait: Boolean = false, readTarget: ByteBuffer? = null) {
        // 切换 FBO (Ping-Pong)
        currentFboIndex = (currentFboIndex + 1) % 2
        
        // 绑定到当前 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboFrameBuffers[currentFboIndex])
        GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
        
        GLES20.glUseProgram(programHandle)
        GLES20.glClearColor(0f, 0f, 0f, 1f) // 设置背景黑边
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val canvasAspect = canvasWidth.toFloat() / canvasHeight.toFloat()
        
        // 根据 isInputContentPortrait 决定是否交换宽高来计算比例
        // 即使 extraRotation 为 0，如果 isInputContentPortrait 为 true，也认为是竖屏内容
        val effectiveVideoWidth = if (isInputContentPortrait) videoHeight else videoWidth
        val effectiveVideoHeight = if (isInputContentPortrait) videoWidth else videoHeight
        val videoAspect = effectiveVideoWidth.toFloat() / effectiveVideoHeight.toFloat()

        var scaleX = 1.0f
        var scaleY = 1.0f

        when (mode) {
            ScaleMode.COVER -> {
                // Cover 模式：长边裁剪，确保填满画布
                if (videoAspect > canvasAspect) {
                    scaleX = videoAspect / canvasAspect
                } else {
                    scaleY = canvasAspect / videoAspect
                }
            }
            ScaleMode.FIT -> {
                // Fit 模式：完整展示，短边留黑边
                if (videoAspect > canvasAspect) {
                    scaleY = canvasAspect / videoAspect
                } else {
                    scaleX = videoAspect / canvasAspect
                }
            }
        }
        
        // 调试日志 (每 60 帧打印一次，避免刷屏)
        // 注意：这里没有 frameCount，只能简单打印
        // Log.d(TAG, "drawFrameToFbo: rot=$extraRotation, video=${videoWidth}x${videoHeight}, eff=${effectiveVideoWidth}x${effectiveVideoHeight}, canvas=${canvasWidth}x${canvasHeight}, scale=${scaleX}x${scaleY}")

        val vMatrix = FloatArray(16)
        Matrix.setIdentityM(vMatrix, 0)
        
        // 矩阵变换顺序：先旋转，再缩放
        // 经过测试，rotateM(90) 导致 90 CCW，rotateM(-90) 导致 90 CW
        // 说明 0 度即为正向 (Upright)
        // 因此不需要应用 extraRotation 进行顶点旋转
        // 但 extraRotation 仍用于计算宽高比 (isRotated)
        
        Matrix.scaleM(vMatrix, 0, scaleX, scaleY, 1.0f)
        
        if (extraRotation != 0) {
            // 注意：这里旋转轴是 Z 轴
            // Matrix.rotateM 默认是逆时针旋转 (CCW)
            // extraRotation 是相机需要顺时针旋转的角度 (CW)
            // 所以需要取反，或者用 360 - extraRotation
            Matrix.rotateM(vMatrix, 0, -extraRotation.toFloat(), 0f, 0f, 1f)
        }
        
        GLES20.glUniformMatrix4fv(vertexTransformHandle, 1, false, vMatrix, 0)
        GLES20.glUniformMatrix4fv(textureTransformHandle, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        
        // 在解绑 FBO 之前读取像素（渲染完成的最可靠时机）
        val readSize = canvasWidth * canvasHeight * 4
        if (readTarget != null && readTarget.capacity() >= readSize) {
            readTarget.rewind()
            GLES20.glReadPixels(0, 0, canvasWidth, canvasHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readTarget)
            readTarget.rewind()
            // glReadPixels 为底行在前（OpenGL 坐标系），翻转为顶行在前，使发给 AI 的帧与预览一致，识别效果正常
            val rowBytes = canvasWidth * 4
            val tmpI = ByteArray(rowBytes)
            val tmpJ = ByteArray(rowBytes)
            for (i in 0 until canvasHeight / 2) {
                val j = canvasHeight - 1 - i
                readTarget.position(i * rowBytes)
                readTarget.get(tmpI)
                readTarget.position(j * rowBytes)
                readTarget.get(tmpJ)
                readTarget.position(i * rowBytes)
                readTarget.put(tmpJ)
                readTarget.position(j * rowBytes)
                readTarget.put(tmpI)
            }
            readTarget.rewind()
        }
        
        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
    
    /**
     * 从 FBO 读取并渲染到当前绑定的 Surface
     */
    private fun drawFboToSurface(viewportWidth: Int, viewportHeight: Int) {
        GLES20.glUseProgram(fboProgramHandle)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[currentFboIndex])
        GLES20.glUniform1i(fboTextureHandle, 0)
        
        GLES20.glEnableVertexAttribArray(fboPositionHandle)
        GLES20.glVertexAttribPointer(fboPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(fboTexCoordHandle)
        GLES20.glVertexAttribPointer(fboTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisableVertexAttribArray(fboPositionHandle)
        GLES20.glDisableVertexAttribArray(fboTexCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * 渲染一帧：先渲染到 FBO，然后输出到编码器和预览 Surface
     * @param cameraTexture 相机纹理 ID
     * @param stMatrix SurfaceTexture 变换矩阵
     * @param videoWidth 视频宽度
     * @param videoHeight 视频高度
     * @param mode 缩放模式
     * @param extraRotation 额外旋转角度
     * @param timestampNs 时间戳（纳秒）
     * @param previewWidth 预览 Surface 实际宽度（0 表示使用 canvasWidth）
     * @param previewHeight 预览 Surface 实际高度（0 表示使用 canvasHeight）
     */
    fun renderFrame(cameraTexture: Int, stMatrix: FloatArray, videoWidth: Int, videoHeight: Int, mode: ScaleMode, extraRotation: Int, isInputContentPortrait: Boolean, timestampNs: Long, fboReadTarget: ByteBuffer? = null, previewWidth: Int = 0, previewHeight: Int = 0) {
        // 1. 渲染到 FBO，并在渲染完成后立即读取像素（若需要）
        drawFrameToFbo(cameraTexture, stMatrix, videoWidth, videoHeight, mode, extraRotation, isInputContentPortrait, fboReadTarget)

        // 2. 输出到编码器 Surface (始终使用 FBO 大小 = 标准横屏分辨率)
        if (eglEncoderSurface != null) {
            drawFboToSurface(canvasWidth, canvasHeight)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglEncoderSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, eglEncoderSurface)
        }

        // 3. 输出到预览 Surface (使用实际 Surface 大小, 以支持全屏铺满)
        if (eglPreviewSurface != null) {
            EGL14.eglMakeCurrent(eglDisplay, eglPreviewSurface, eglPreviewSurface, eglContext)
            
            val targetW = if (previewWidth > 0) previewWidth else canvasWidth
            val targetH = if (previewHeight > 0) previewHeight else canvasHeight
            
            drawFboToSurface(targetW, targetH)
            
            // 移除 glFinish()，使用双缓冲解决撕裂
            EGL14.eglSwapBuffers(eglDisplay, eglPreviewSurface)
            EGL14.eglMakeCurrent(eglDisplay, eglEncoderSurface, eglEncoderSurface, eglContext)
        }
    }
    
    /**
     * 获取 FBO texture ID（用于预览渲染）
     */
    fun getFboTextureId(): Int = fboTextureIds[currentFboIndex]
    
    /**
     * 从 FBO 读取 RGBA 像素数据（用于 YOLO 检测）
     * 注意：必须在正确的 EGL context 中调用（即在 renderFrame 之后调用）
     * @param targetBuffer 可选：复用该 DirectByteBuffer 写入，避免每帧分配、且保证在插件侧长期有效；需 capacity >= width*height*4
     * @return ByteBuffer 包含 RGBA 数据，格式为 width * height * 4 字节；若使用 targetBuffer 则返回同一实例
     * 注意：glReadPixels 读取的数据是上下翻转的（OpenGL 坐标系左下角为原点），
     * 我们在这里翻转数据，使其返回正确方向的图像（标准图像坐标系，左上角为原点）
     */
    fun readFboRgba(targetBuffer: ByteBuffer? = null): ByteBuffer? {
        if (fboFrameBuffers[0] == 0 || canvasWidth <= 0 || canvasHeight <= 0) {
            return null
        }
        val bufferSize = canvasWidth * canvasHeight * 4
        val buffer = if (targetBuffer != null && targetBuffer.capacity() >= bufferSize) {
            targetBuffer.clear()
            targetBuffer.order(ByteOrder.nativeOrder())
            targetBuffer
        } else {
            ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder())
        }

        // 确保绑定到当前 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboFrameBuffers[currentFboIndex])

        // 从 FBO 读取像素数据（RGBA 格式）
        buffer.rewind()
        GLES20.glReadPixels(0, 0, canvasWidth, canvasHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        val error = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        if (error != GLES20.GL_NO_ERROR) {
            android.util.Log.e(TAG, "glReadPixels error: $error")
            return null
        }

        // 为了性能，移除 CPU 翻转。如果检测算法需要，可以在应用侧翻转坐标轴。
        buffer.rewind()
        return buffer
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, code)
        GLES20.glCompileShader(shader)
        return shader
    }

    fun release() {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        
        // 释放 FBO
        // 释放 FBO
        if (fboTextureIds[0] != 0) {
            GLES20.glDeleteTextures(2, fboTextureIds, 0)
            fboTextureIds[0] = 0
            fboTextureIds[1] = 0
        }
        if (fboFrameBuffers[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fboFrameBuffers, 0)
            fboFrameBuffers[0] = 0
            fboFrameBuffers[1] = 0
        }
        
        eglEncoderSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglPreviewSurface?.let { EGL14.eglDestroySurface(eglDisplay, it) }
        eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
        EGL14.eglTerminate(eglDisplay)
        eglEncoderSurface = null
        eglPreviewSurface = null
    }
}