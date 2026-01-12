package com.bb.rtmp

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RtmpStreamer {
    private val TAG = "RtmpStreamer"
    private var rtmpHandle: Long = 0
    private var rtmpUrl: String = ""
    private val isStreaming = AtomicBoolean(false)
    private val startTime = AtomicLong(0)
    
    // 静态计数器用于日志（避免日志过多）
    private var staticVideoFrameCount = 0
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var savedSps: ByteArray? = null
    private var savedPps: ByteArray? = null

    /**
     * 初始化 RTMP 推流器
     */
    fun initialize(url: String, videoEncoder: VideoEncoder, audioEncoder: AudioEncoder?): Boolean {
        this.rtmpUrl = url
        this.videoEncoder = videoEncoder
        this.audioEncoder = audioEncoder

        try {
            rtmpHandle = RtmpNative.init(url)
            if (rtmpHandle == 0L) {
                Log.e(TAG, "RTMP 初始化失败")
                return false
            }

            // 设置编码器回调
            videoEncoder.setCallback(object : VideoEncoder.EncoderCallback {
                override fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
                    if (isStreaming.get()) {
                        sendVideoData(data, info)
                    } else {
                        // 每 30 帧打印一次警告（避免日志过多）
                        staticVideoFrameCount++
                        if (staticVideoFrameCount % 30 == 0) {
                            Log.w(TAG, "收到编码数据但推流未开始 (isStreaming=false), 总帧数=$staticVideoFrameCount")
                        }
                    }
                }

                override fun onCodecConfig(sps: ByteArray, pps: ByteArray) {
                    Log.d(TAG, "收到 SPS/PPS: SPS size=${sps.size}, PPS size=${pps.size}, isStreaming=${isStreaming.get()}")
                    // 保存 SPS/PPS
                    savedSps = sps
                    savedPps = pps
                    
                    // 如果已经在推流，立即发送
                    if (rtmpHandle != 0L && isStreaming.get()) {
                        sendSpsPps()
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "视频编码错误: $error")
                }
            })

            audioEncoder?.setCallback(object : AudioEncoder.EncoderCallback {
                override fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
                    if (isStreaming.get()) {
                        sendAudioData(data, info)
                    }
                }

                override fun onError(error: String) {
                    Log.e(TAG, "音频编码错误: $error")
                }
            })

            Log.d(TAG, "RTMP 推流器初始化成功")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化 RTMP 推流器失败", e)
            return false
        }
    }

    /**
     * 设置元数据信息（用于 AMF0 onMetaData）
     */
    fun setMetadata(width: Int, height: Int, videoBitrate: Int, fps: Int, audioSampleRate: Int, audioChannels: Int) {
        if (rtmpHandle != 0L) {
            try {
                RtmpNative.setMetadata(rtmpHandle, width, height, videoBitrate, fps, audioSampleRate, audioChannels)
                val direction = if (width < height) "竖屏" else if (width > height) "横屏" else "正方形"
                Log.d(TAG, "设置元数据: ${width}x${height} ($direction), bitrate=$videoBitrate, fps=$fps")
            } catch (e: Exception) {
                Log.e(TAG, "设置元数据失败", e)
            }
        } else {
            Log.w(TAG, "RTMP 未初始化，无法设置元数据")
        }
    }

    /**
     * 开始推流
     */
    fun start() {
        if (isStreaming.get()) {
            Log.w(TAG, "推流已在进行中")
            return
        }

        startTime.set(System.currentTimeMillis() * 1000) // 微秒
        isStreaming.set(true)
        Log.d(TAG, "开始推流")
        
        // 如果已有 SPS/PPS，立即发送
        if (savedSps != null && savedPps != null) {
            Log.d(TAG, "推流开始时发送已保存的 SPS/PPS")
            sendSpsPps()
        } else {
            Log.w(TAG, "推流开始时没有 SPS/PPS，等待编码器输出")
        }
    }
    
    /**
     * 发送 SPS/PPS 到 RTMP
     */
    private fun sendSpsPps() {
        val sps = savedSps ?: return
        val pps = savedPps ?: return
        
        if (rtmpHandle == 0L) {
            Log.w(TAG, "RTMP 未初始化，无法发送 SPS/PPS")
            return
        }
        
        Log.d(TAG, "发送 SPS/PPS: SPS size=${sps.size}, PPS size=${pps.size}")
        // 将 SPS/PPS 组合成 Annex-B 格式并发送
        val spsPpsData = ByteArray(sps.size + pps.size + 8)
        var idx = 0
        // SPS start code
        spsPpsData[idx++] = 0x00
        spsPpsData[idx++] = 0x00
        spsPpsData[idx++] = 0x00
        spsPpsData[idx++] = 0x01
        System.arraycopy(sps, 0, spsPpsData, idx, sps.size)
        idx += sps.size
        // PPS start code
        spsPpsData[idx++] = 0x00
        spsPpsData[idx++] = 0x00
        spsPpsData[idx++] = 0x00
        spsPpsData[idx++] = 0x01
        System.arraycopy(pps, 0, spsPpsData, idx, pps.size)
        
        val result = RtmpNative.sendVideo(rtmpHandle, spsPpsData, spsPpsData.size, 0, true)
        if (result != 0) {
            Log.w(TAG, "发送 SPS/PPS 失败: $result")
        } else {
            Log.d(TAG, "SPS/PPS 发送成功")
        }
    }

    /**
     * 停止推流
     */
    fun stop() {
        if (!isStreaming.get()) {
            return
        }

        isStreaming.set(false)
        Log.d(TAG, "停止推流")
    }

    /**
     * 发送视频数据
     */
    private fun sendVideoData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (rtmpHandle == 0L) {
            Log.w(TAG, "RTMP handle 为 0，跳过发送视频数据")
            return
        }
        
        if (!isStreaming.get()) {
            Log.w(TAG, "推流未开始，跳过发送视频数据 (isStreaming=false)")
            return
        }

        try {
            val timestamp = (System.currentTimeMillis() * 1000 - startTime.get()) / 1000 // 转换为微秒
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            
            // 每 30 帧打印一次日志（用于调试）
            staticVideoFrameCount++
            if (isKeyFrame || staticVideoFrameCount % 30 == 0) {
                Log.d(TAG, "发送视频数据: size=${info.size}, pts=${info.presentationTimeUs}, isKeyFrame=$isKeyFrame (总帧数=$staticVideoFrameCount)")
            }

            // 零拷贝：直接传递 ByteBuffer 地址
            if (data.isDirect) {
                val address = getDirectBufferAddress(data)
                if (address != 0L) {
                    val result = RtmpNative.sendVideoBuffer(
                        rtmpHandle,
                        address,
                        info.offset,
                        info.size,
                        timestamp,
                        isKeyFrame
                    )
                    if (result != 0) {
                        Log.w(TAG, "发送视频数据失败: $result")
                    }
                } else {
                    // 回退到数组方式
                    sendVideoDataArray(data, info, timestamp, isKeyFrame)
                }
            } else {
                // 非直接缓冲区，使用数组方式
                sendVideoDataArray(data, info, timestamp, isKeyFrame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送视频数据异常", e)
        }
    }

    /**
     * 发送视频数据（数组方式）
     */
    private fun sendVideoDataArray(data: ByteBuffer, info: MediaCodec.BufferInfo, timestamp: Long, isKeyFrame: Boolean) {
        val array = ByteArray(info.size)
        data.position(info.offset)
        data.get(array, 0, info.size)
        data.rewind()

        val result = RtmpNative.sendVideo(rtmpHandle, array, info.size, timestamp, isKeyFrame)
        if (result != 0) {
            Log.w(TAG, "发送视频数据失败: $result")
        }
    }

    /**
     * 发送音频数据
     */
    private fun sendAudioData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (rtmpHandle == 0L) {
            return
        }

        try {
            val timestamp = (System.currentTimeMillis() * 1000 - startTime.get()) / 1000 // 转换为微秒

            // 零拷贝：直接传递 ByteBuffer 地址
            if (data.isDirect) {
                val address = getDirectBufferAddress(data)
                if (address != 0L) {
                    val result = RtmpNative.sendAudioBuffer(
                        rtmpHandle,
                        address,
                        info.offset,
                        info.size,
                        timestamp
                    )
                    if (result != 0) {
                        Log.w(TAG, "发送音频数据失败: $result")
                    }
                } else {
                    // 回退到数组方式
                    sendAudioDataArray(data, info, timestamp)
                }
            } else {
                // 非直接缓冲区，使用数组方式
                sendAudioDataArray(data, info, timestamp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送音频数据异常", e)
        }
    }

    /**
     * 发送音频数据（数组方式）
     */
    private fun sendAudioDataArray(data: ByteBuffer, info: MediaCodec.BufferInfo, timestamp: Long) {
        val array = ByteArray(info.size)
        data.position(info.offset)
        data.get(array, 0, info.size)
        data.rewind()

        val result = RtmpNative.sendAudio(rtmpHandle, array, info.size, timestamp)
        if (result != 0) {
            Log.w(TAG, "发送音频数据失败: $result")
        }
    }

    /**
     * 获取直接缓冲区的地址（使用反射）
     */
    private fun getDirectBufferAddress(buffer: ByteBuffer): Long {
        return try {
            val field = buffer.javaClass.getDeclaredField("address")
            field.isAccessible = true
            field.getLong(buffer)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 获取网络统计信息
     */
    fun getStats(): NetworkStats? {
        if (rtmpHandle == 0L) {
            return null
        }

        try {
            val stats = RtmpNative.getStats(rtmpHandle) ?: return null
            if (stats.size >= 3) {
                return NetworkStats(
                    bytesSent = stats[0],
                    delayMs = stats[1].toInt(),
                    packetLossPercent = stats[2].toInt()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取统计信息失败", e)
        }

        return null
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        
        if (rtmpHandle != 0L) {
            RtmpNative.close(rtmpHandle)
            rtmpHandle = 0
        }

        videoEncoder = null
        audioEncoder = null
        Log.d(TAG, "RTMP 推流器已释放")
    }

    /**
     * 是否正在推流
     */
    fun isStreaming(): Boolean = isStreaming.get()
}

/**
 * 网络统计信息
 */
data class NetworkStats(
    val bytesSent: Long,
    val delayMs: Int,
    val packetLossPercent: Int
)

