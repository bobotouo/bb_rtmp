package com.bb.rtmp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoEncoder {
    private val TAG = "VideoEncoder"
    private var mediaCodec: MediaCodec? = null
    private var width = 0
    private var height = 0
    private var bitrate = 0
    private var fps = 30
    private var surface: Surface? = null
    private val isEncoding = AtomicBoolean(false)
    private var encoderCallback: EncoderCallback? = null
    private var encodeThread: Thread? = null
    
    // 保存 SPS/PPS，以便在设置回调时立即通知
    private var savedSps: ByteArray? = null
    private var savedPps: ByteArray? = null
    private val spsPpsLock = Any()
    
    // 静态计数器用于日志（避免日志过多）
    private var staticOutputFrameCount = 0
    private var staticEmptyFrameCount = 0
    
    // FPS 统计
    private val fpsStats = FpsStats()
    
    /**
     * FPS 统计类
     */
    private class FpsStats {
        private val frameTimestamps = mutableListOf<Long>()
        private val maxSamples = 60 // 保留最近60帧的时间戳
        private val lock = Any()
        
        fun recordFrame() {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                frameTimestamps.add(now)
                // 只保留最近1秒内的帧
                val oneSecondAgo = now - 1000
                frameTimestamps.removeAll { it < oneSecondAgo }
            }
        }
        
        fun getFps(): Float {
            synchronized(lock) {
                if (frameTimestamps.size < 2) return 0f
                val oldest = frameTimestamps.first()
                val newest = frameTimestamps.last()
                val duration = (newest - oldest).toFloat() / 1000f // 秒
                if (duration <= 0) return 0f
                return (frameTimestamps.size - 1) / duration
            }
        }
        
        fun reset() {
            synchronized(lock) {
                frameTimestamps.clear()
            }
        }
    }

    interface EncoderCallback {
        fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onCodecConfig(sps: ByteArray, pps: ByteArray) {}
        fun onError(error: String)
    }

    fun setCallback(callback: EncoderCallback) {
        this.encoderCallback = callback
        
        // 如果已经有保存的 SPS/PPS，立即通知回调
        synchronized(spsPpsLock) {
            if (savedSps != null && savedPps != null) {
                Log.d(TAG, "设置回调时发现已有 SPS/PPS，立即通知: SPS size=${savedSps!!.size}, PPS size=${savedPps!!.size}")
                callback.onCodecConfig(savedSps!!, savedPps!!)
            }
        }
    }

    /**
     * 初始化视频编码器
     */
    fun initialize(width: Int, height: Int, bitrate: Int, fps: Int): Surface? {
        this.width = width
        this.height = height
        this.bitrate = bitrate
        this.fps = fps

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 480p 时缩短 GOP（1 秒）减少关键帧尖峰，与 iOS 一致；其他分辨率 2 秒
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (height == 480) 1 else 2)
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = encoder.createInputSurface()
            
            // 验证 Surface 是否创建成功
            if (surface == null) {
                throw RuntimeException("MediaCodec.createInputSurface() 返回 null")
            }
            Log.d(TAG, "MediaCodec Surface 创建成功: $surface")
            
            encoder.start()
            Log.d(TAG, "MediaCodec 已启动，等待输入帧...")

            mediaCodec = encoder
            isEncoding.set(true)

            // 启动编码线程
            encodeThread = Thread {
                encodeLoop()
            }
            encodeThread!!.start()

            Log.d(TAG, "视频编码器初始化成功: ${width}x${height}, bitrate=$bitrate, fps=$fps")
            return surface
        } catch (e: Exception) {
            Log.e(TAG, "初始化视频编码器失败", e)
            encoderCallback?.onError("初始化视频编码器失败: ${e.message}")
            return null
        }
    }

    /**
     * 编码循环
     */
    private fun encodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isEncoding.get()) {
            val codec = mediaCodec ?: break // 如果 codec 被释放，退出循环
            try {
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 没有输出数据，继续等待
                    }
                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        Log.d(TAG, "输出格式改变: $newFormat")
                        
                        // 打印 MediaFormat 信息，用于调试
                        Log.d(TAG, "MediaFormat toString: $newFormat")
                        
                        // 从 MediaFormat 中提取 SPS/PPS（CSD-0 和 CSD-1）
                        val csd0 = newFormat.getByteBuffer("csd-0") // SPS
                        val csd1 = newFormat.getByteBuffer("csd-1") // PPS
                        
                        Log.d(TAG, "csd-0: ${if (csd0 != null) "found, size=${csd0.remaining()}" else "null"}")
                        Log.d(TAG, "csd-1: ${if (csd1 != null) "found, size=${csd1.remaining()}" else "null"}")
                        
                        if (csd0 != null && csd1 != null) {
                            // 将 SPS/PPS 转换为字节数组并通知回调
                            val spsArray = ByteArray(csd0.remaining())
                            csd0.get(spsArray)
                            csd0.rewind()
                            
                            val ppsArray = ByteArray(csd1.remaining())
                            csd1.get(ppsArray)
                            csd1.rewind()
                            
                            Log.d(TAG, "从 MediaFormat 提取 SPS/PPS: SPS size=${spsArray.size}, PPS size=${ppsArray.size}")
                            
                            // 打印前几个字节用于验证
                            if (spsArray.isNotEmpty()) {
                                val spsHex = spsArray.take(8).joinToString(" ") { "%02x".format(it) }
                                Log.d(TAG, "SPS 前8字节: $spsHex")
                            }
                            if (ppsArray.isNotEmpty()) {
                                val ppsHex = ppsArray.take(8).joinToString(" ") { "%02x".format(it) }
                                Log.d(TAG, "PPS 前8字节: $ppsHex")
                            }
                            
                            // 检查 SPS/PPS 是否改变（避免重复通知）
                            var shouldNotify = false
                            synchronized(spsPpsLock) {
                                val hasChanged = savedSps == null || savedPps == null || 
                                                !savedSps!!.contentEquals(spsArray) || 
                                                !savedPps!!.contentEquals(ppsArray)
                                
                                if (hasChanged) {
                                    savedSps = spsArray
                                    savedPps = ppsArray
                                    shouldNotify = true
                                }
                            }
                            
                            // 只在改变时通知回调（检查回调是否仍然有效）
                            val callback = encoderCallback
                            if (shouldNotify && callback != null) {
                                try {
                                    callback.onCodecConfig(spsArray, ppsArray)
                                } catch (e: Exception) {
                                    Log.e(TAG, "SPS/PPS 回调异常（可能已释放）", e)
                                }
                            }
                        } else {
                            Log.w(TAG, "MediaFormat 中未找到 SPS/PPS (csd-0/csd-1)")
                        }
                    }
                    outputBufferId >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // 记录帧统计
                            fpsStats.recordFrame()
                            
                            // 零拷贝：直接传递 ByteBuffer 给回调
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            // 创建只读副本，避免数据被修改
                            val data = outputBuffer.slice()
                            
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            
                            // 每 30 帧打印一次日志（用于调试）
                            staticOutputFrameCount++
                            if (isKeyFrame || staticOutputFrameCount % 30 == 0) {
                                // 打印前几个字节，用于验证数据格式
                                val previewBytes = ByteArray(minOf(16, bufferInfo.size))
                                val originalPosition = data.position()
                                data.get(previewBytes)
                                data.position(originalPosition)
                                
                                val hexString = previewBytes.joinToString(" ") { "%02x".format(it) }
                                Log.d(TAG, "编码输出帧: size=${bufferInfo.size}, pts=${bufferInfo.presentationTimeUs}, isKeyFrame=$isKeyFrame (总帧数=$staticOutputFrameCount)")
                                Log.d(TAG, "  数据前${previewBytes.size}字节: $hexString")
                                
                                // 检查数据格式：Annex-B (00 00 00 01) 或 AVCC (长度前缀)
                                if (previewBytes.size >= 4) {
                                    if (previewBytes[0] == 0x00.toByte() && previewBytes[1] == 0x00.toByte() && 
                                        previewBytes[2] == 0x00.toByte() && previewBytes[3] == 0x01.toByte()) {
                                        Log.d(TAG, "  数据格式: Annex-B (00 00 00 01)")
                                    } else if (previewBytes[0] == 0x00.toByte() && previewBytes[1] == 0x00.toByte() && 
                                               previewBytes[2] == 0x01.toByte()) {
                                        Log.d(TAG, "  数据格式: Annex-B (00 00 01)")
                                    } else {
                                        val lengthPrefix = (previewBytes[0].toInt() and 0xFF shl 24) or 
                                                          (previewBytes[1].toInt() and 0xFF shl 16) or
                                                          (previewBytes[2].toInt() and 0xFF shl 8) or
                                                          (previewBytes[3].toInt() and 0xFF)
                                        Log.d(TAG, "  数据格式: 可能是 AVCC (长度前缀=$lengthPrefix)")
                                    }
                                }
                            }
                            
                            // 安全调用回调（检查回调是否仍然有效）
                            val callback = encoderCallback
                            if (callback != null) {
                                try {
                                    callback.onEncodedData(data, bufferInfo)
                                } catch (e: Exception) {
                                    Log.e(TAG, "编码数据回调异常（可能已释放）", e)
                                }
                            }
                        } else {
                            if (bufferInfo.size == 0) {
                                // 每 30 次打印一次警告（避免日志过多）
                                staticEmptyFrameCount++
                                if (staticEmptyFrameCount % 30 == 0) {
                                    Log.w(TAG, "编码输出缓冲区大小为 0 (已发生 $staticEmptyFrameCount 次)")
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "编码循环异常", e)
                if (isEncoding.get()) {
                    encoderCallback?.onError("编码异常: ${e.message}")
                }
                break
            }
        }
    }

    /**
     * 更新码率
     */
    fun updateBitrate(newBitrate: Int) {
        val codec = mediaCodec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
            codec.setParameters(params)
            this.bitrate = newBitrate
            Log.d(TAG, "码率更新为: $newBitrate")
        } catch (e: Exception) {
            Log.e(TAG, "更新码率失败", e)
            encoderCallback?.onError("更新码率失败: ${e.message}")
        }
    }

    /**
     * 请求关键帧
     */
    fun requestKeyFrame() {
        val codec = mediaCodec ?: return
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            Log.d(TAG, "请求关键帧")
        } catch (e: Exception) {
            Log.e(TAG, "请求关键帧失败", e)
        }
    }

    /**
     * 释放编码器
     */
    fun release() {
        // 1. 先清除回调，防止回调在释放后继续执行
        encoderCallback = null
        
        // 2. 停止编码
        isEncoding.set(false)
        
        // 3. 等待编码线程结束
        encodeThread?.join(1000) // 最多等待 1 秒
        encodeThread = null
        
        // 4. 释放 MediaCodec 和 Surface
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            surface?.release()
            surface = null
            synchronized(spsPpsLock) {
                savedSps = null
                savedPps = null
            }
            fpsStats.reset()
            Log.d(TAG, "视频编码器已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放视频编码器失败", e)
        }
    }

    /**
     * 获取当前码率
     */
    fun getCurrentBitrate(): Int = bitrate
    
    /**
     * 获取当前编码帧率（FPS）
     */
    fun getCurrentFps(): Float = fpsStats.getFps()

    /**
     * 获取编码器 Surface（用于摄像头输入）
     */
    fun getInputSurface(): Surface? = surface
}

