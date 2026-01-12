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
    
    // 静态计数器用于日志（避免日志过多）
    private var staticOutputFrameCount = 0
    private var staticEmptyFrameCount = 0

    interface EncoderCallback {
        fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onCodecConfig(sps: ByteArray, pps: ByteArray) {}
        fun onError(error: String)
    }

    fun setCallback(callback: EncoderCallback) {
        this.encoderCallback = callback
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
            // GOP (关键帧间隔) 2 秒，符合抖音、TikTok、视频号等主流平台标准
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
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
            Thread {
                encodeLoop()
            }.start()

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
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isEncoding.get()) {
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
                            
                            // 通知编码器回调，传递 SPS/PPS
                            encoderCallback?.onCodecConfig(spsArray, ppsArray)
                        } else {
                            Log.w(TAG, "MediaFormat 中未找到 SPS/PPS (csd-0/csd-1)")
                        }
                    }
                    outputBufferId >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0) {
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
                            
                            encoderCallback?.onEncodedData(data, bufferInfo)
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
        isEncoding.set(false)
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            surface?.release()
            surface = null
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
     * 获取编码器 Surface（用于摄像头输入）
     */
    fun getInputSurface(): Surface? = surface
}

