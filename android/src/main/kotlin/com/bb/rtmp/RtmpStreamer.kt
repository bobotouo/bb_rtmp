package com.bb.rtmp

import android.media.MediaCodec
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class RtmpStreamer {
    private val TAG = "RtmpStreamer"
    private var rtmpHandle: Long = 0
    private var rtmpUrl: String = ""
    private val isStreaming = AtomicBoolean(false)
    private val startTime = AtomicLong(0)
    private var isRefreshing = false
    
    // 状态回调接口
    interface StatusCallback {
        fun onStatus(status: String, error: String?)
    }
    private var statusCallback: StatusCallback? = null
    
    fun setStatusCallback(callback: StatusCallback?) {
        statusCallback = callback
    }
    
    // 异步发送队列（避免阻塞编码器回调线程）
    private data class VideoFrame(
        val data: ByteArray,
        val size: Int,
        val timestamp: Long,
        val isKeyFrame: Boolean
    )
    private val videoSendQueue = LinkedBlockingQueue<VideoFrame>(5) // 限制队列避免弱网下积压导致内存爆炸（与 iOS 一致）
    private var videoSendThread: Thread? = null
    private val videoSendThreadRunning = AtomicBoolean(false)
    private val videoQueueSize = AtomicInteger(0) // 缓存队列大小，避免频繁调用 size()
    
    // 音频发送队列
    private data class AudioFrame(
        val data: ByteArray,
        val size: Int,
        val timestamp: Long
    )
    private val audioSendQueue = LinkedBlockingQueue<AudioFrame>(60) // 最多缓存 60 帧（约 2 秒）
    private var audioSendThread: Thread? = null
    private val audioSendThreadRunning = AtomicBoolean(false)
    
    // 统计信息
    private val droppedFrames = AtomicInteger(0)
    private val sentFrames = AtomicInteger(0)
    private val sendErrorCount = AtomicInteger(0)

    // 分辨率切换后仅发关键帧直至首帧关键帧发送，便于播放端恢复
    private val resolutionChangePending = AtomicBoolean(false)
    
    // 静态计数器用于日志（避免日志过多）
    private var staticVideoFrameCount = 0
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var savedSps: ByteArray? = null
    private var savedPps: ByteArray? = null
    
    // Heartbeat for background streaming (Deep copy for thread safety)
    private var lastVideoDataBytes: ByteArray? = null
    private var lastVideoInfo: MediaCodec.BufferInfo? = null
    private val heartbeatLock = Any()
    private var heartbeatTimer: java.util.Timer? = null

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
     * 热切换视频编码器（如 ABR 切分辨率时替换为新分辨率编码器）
     */
    fun replaceVideoEncoder(newEncoder: VideoEncoder) {
        videoEncoder = newEncoder
        savedSps = null
        savedPps = null
        newEncoder.setCallback(object : VideoEncoder.EncoderCallback {
            override fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo) {
                if (isStreaming.get()) {
                    sendVideoData(data, info)
                } else {
                    staticVideoFrameCount++
                    if (staticVideoFrameCount % 30 == 0) {
                        Log.w(TAG, "收到编码数据但推流未开始 (isStreaming=false), 总帧数=$staticVideoFrameCount")
                    }
                }
            }
            override fun onCodecConfig(sps: ByteArray, pps: ByteArray) {
                Log.d(TAG, "收到 SPS/PPS: SPS size=${sps.size}, PPS size=${pps.size}, isStreaming=${isStreaming.get()}")
                savedSps = sps
                savedPps = pps
                if (rtmpHandle != 0L && isStreaming.get()) {
                    sendSpsPps()
                }
            }
            override fun onError(error: String) {
                Log.e(TAG, "视频编码错误: $error")
            }
        })
        Log.d(TAG, "已替换视频编码器")
    }

    /**
     * 开始推流
     */
    fun start() {
        if (isStreaming.get()) {
            Log.w(TAG, "推流已在进行中")
            return
        }

        startTime.set(System.currentTimeMillis()) // milliseconds
        isStreaming.set(true)
        
        // 重置统计
        droppedFrames.set(0)
        sentFrames.set(0)
        
        // 启动发送线程
        startSendThreads()
        
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
     * 启动发送线程
     */
    private fun startSendThreads() {
        // 启动视频发送线程
        if (!videoSendThreadRunning.get()) {
            videoSendThreadRunning.set(true)
            videoSendThread = Thread {
                videoSendLoop()
            }
            videoSendThread!!.start()
        }
        
        // 启动音频发送线程
        if (!audioSendThreadRunning.get()) {
            audioSendThreadRunning.set(true)
            audioSendThread = Thread {
                audioSendLoop()
            }
            audioSendThread!!.start()
        }
    }
    
    /**
     * 停止发送线程
     */
    private fun stopSendThreads() {
        videoSendThreadRunning.set(false)
        audioSendThreadRunning.set(false)
        
        // 唤醒等待的线程
        videoSendQueue.clear()
        videoQueueSize.set(0) // 重置队列大小计数
        audioSendQueue.clear()
        
        // 等待线程结束
        videoSendThread?.join(1000)
        audioSendThread?.join(1000)
        
        videoSendThread = null
        audioSendThread = null
    }
    
    /**
     * 视频发送循环（在独立线程中运行）
     */
    private fun videoSendLoop() {
        Log.d(TAG, "视频发送线程启动")
        var lastLogTime = System.currentTimeMillis()
        var framesInLastSecond = 0
        
        while (videoSendThreadRunning.get() && isStreaming.get()) {
            try {
                // 从队列中取出帧（最多等待 100ms）
                val frame = videoSendQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frame != null && rtmpHandle != 0L) {
                    // 更新队列大小计数
                    videoQueueSize.decrementAndGet()
                    
                    val sendStartTime = System.currentTimeMillis()
                    val result = RtmpNative.sendVideo(rtmpHandle, frame.data, frame.size, frame.timestamp, frame.isKeyFrame)
                    val sendDuration = System.currentTimeMillis() - sendStartTime
                    
                    if (result != 0) {
                        sendErrorCount.incrementAndGet()
                        Log.w(TAG, "发送视频数据失败: $result, 耗时=${sendDuration}ms")
                        handleSocketError(result)
                    } else {
                        if (frame.isKeyFrame) {
                            resolutionChangePending.set(false)
                        }
                        sentFrames.incrementAndGet()
                        framesInLastSecond++
                        
                        // 如果发送耗时过长（>50ms），记录警告
                        if (sendDuration > 50) {
                            Log.w(TAG, "视频帧发送耗时过长: ${sendDuration}ms, size=${frame.size}, isKeyFrame=${frame.isKeyFrame}")
                        }
                    }
                }
                
                // 每秒打印一次统计信息
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    val queueSize = videoQueueSize.get() // 使用缓存的大小，避免调用 size()
                    val dropped = droppedFrames.get()
                    val sent = sentFrames.get()
                    if (queueSize > 10 || dropped > 0) {
                        Log.w(TAG, "发送统计: 队列大小=$queueSize, 每秒发送=$framesInLastSecond 帧, 总发送=$sent, 总丢弃=$dropped")
                    }
                    framesInLastSecond = 0
                    lastLogTime = now
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "视频发送循环异常", e)
            }
        }
        Log.d(TAG, "视频发送线程结束")
    }
    
    /**
     * 音频发送循环（在独立线程中运行）
     */
    private fun audioSendLoop() {
        Log.d(TAG, "音频发送线程启动")
        while (audioSendThreadRunning.get() && isStreaming.get()) {
            try {
                // 从队列中取出帧（最多等待 100ms）
                val frame = audioSendQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (frame != null && rtmpHandle != 0L) {
                    val sendStartTime = System.currentTimeMillis()
                    val result = RtmpNative.sendAudio(rtmpHandle, frame.data, frame.size, frame.timestamp)
                    val sendDuration = System.currentTimeMillis() - sendStartTime
                    
                    if (result != 0) {
                        Log.w(TAG, "发送音频数据失败: $result, 耗时=${sendDuration}ms")
                        handleSocketError(result)
                    } else if (sendDuration > 50) {
                        // 如果发送耗时过长，记录警告
                        Log.w(TAG, "音频帧发送耗时过长: ${sendDuration}ms, size=${frame.size}")
                    }
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "音频发送循环异常", e)
            }
        }
        Log.d(TAG, "音频发送线程结束")
    }
    
    /**
     * 发送 SPS/PPS 到 RTMP（同步发送，必须在发送线程启动后调用）
     */
    private fun sendSpsPps() {
        val sps = savedSps ?: return
        val pps = savedPps ?: return
        
        if (rtmpHandle == 0L) {
            Log.w(TAG, "RTMP 未初始化，无法发送 SPS/PPS")
            return
        }
        
        Log.d(TAG, "发送 SPS/PPS: SPS size=${sps.size}, PPS size=${pps.size}")
        // 将 SPS/PPS 组合成 Annex-B 格式并发送（同步发送，确保在视频帧之前）
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
        
        // SPS/PPS 必须同步发送，确保在视频帧之前到达
        val result = RtmpNative.sendVideo(rtmpHandle, spsPpsData, spsPpsData.size, 0L, true)
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
        stopHeartbeat()
        stopSendThreads()
        
        // 打印统计信息
        val dropped = droppedFrames.get()
        val sent = sentFrames.get()
        if (dropped > 0) {
            Log.w(TAG, "推流统计: 发送 $sent 帧, 丢弃 $dropped 帧 (队列满)")
        } else {
            Log.d(TAG, "推流统计: 发送 $sent 帧, 丢弃 $dropped 帧")
        }
        
        Log.d(TAG, "停止推流")
    }

    private fun handleSocketError(error: Int) {
        // -1 usually indicates a socket error from our C++ wrapper
        if (error != 0 && !isRefreshing && isStreaming.get()) {
            Log.e(TAG, "Critical socket error detected ($error). Triggering refresh...")
            statusCallback?.onStatus("error", "RTMP 连接错误，正在重连...")
            refreshConnection()
        }
    }

    fun refreshConnection() {
        if (!isStreaming.get() || isRefreshing) return
        isRefreshing = true
        
        Log.d(TAG, "Refreshing RTMP connection...")
        statusCallback?.onStatus("reconnecting", null)
        
        Thread {
            try {
                // 1. Close old connection
                if (rtmpHandle != 0L) {
                    RtmpNative.close(rtmpHandle)
                    rtmpHandle = 0
                }
                
                // 2. Wait for server cleanup
                Thread.sleep(1500)
                
                // 3. Re-init
                rtmpHandle = RtmpNative.init(rtmpUrl)
                if (rtmpHandle != 0L) {
                    applyCachedMetadata()
                    sendSpsPps()
                    
                    // 清空发送队列，避免发送旧数据
                    videoSendQueue.clear()
                    audioSendQueue.clear()
                    
                    Log.d(TAG, "RTMP connection refreshed successfully")
                    statusCallback?.onStatus("connected", null)
                    // Force a keyframe
                    videoEncoder?.requestKeyFrame()
                } else {
                    Log.e(TAG, "Failed to refresh RTMP connection")
                    statusCallback?.onStatus("failed", "RTMP 重连失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during RTMP refresh", e)
                statusCallback?.onStatus("failed", "RTMP 重连异常: ${e.message}")
            } finally {
                isRefreshing = false
            }
        }.start()
    }

    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedVideoBitrate = 0
    private var cachedFps = 30
    private var cachedAudioSampleRate = 44100
    private var cachedAudioChannels = 1

    /**
     * 设置元数据信息（用于 AMF0 onMetaData）
     */
    fun setMetadata(width: Int, height: Int, videoBitrate: Int, fps: Int, audioSampleRate: Int, audioChannels: Int) {
        cachedWidth = width
        cachedHeight = height
        cachedVideoBitrate = videoBitrate
        cachedFps = fps
        cachedAudioSampleRate = audioSampleRate
        cachedAudioChannels = audioChannels
        
        applyCachedMetadata()
    }

    private fun applyCachedMetadata() {
        if (rtmpHandle != 0L) {
            try {
                RtmpNative.setMetadata(rtmpHandle, cachedWidth, cachedHeight, cachedVideoBitrate, cachedFps, cachedAudioSampleRate, cachedAudioChannels)
                val direction = if (cachedWidth < cachedHeight) "竖屏" else if (cachedWidth > cachedHeight) "横屏" else "正方形"
                Log.d(TAG, "已应用元数据: ${cachedWidth}x${cachedHeight} ($direction), bitrate=$cachedVideoBitrate, fps=$cachedFps")
            } catch (e: Exception) {
                Log.e(TAG, "应用元数据失败", e)
            }
        }
    }

    private fun getStreamTimestamp(): Long {
        return System.currentTimeMillis() - startTime.get()
    }

    fun startHeartbeat() {
        if (!isStreaming.get() || heartbeatTimer != null) return
        Log.d(TAG, "Starting background video heartbeat")
        heartbeatTimer = java.util.Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                sendHeartbeatFrame()
            }
        }, 1000, 1000) // Every 1 second
    }

    fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        Log.d(TAG, "Stopped background video heartbeat")
    }

    private fun sendHeartbeatFrame() {
        val bytes = synchronized(heartbeatLock) { lastVideoDataBytes } ?: return
        val info = synchronized(heartbeatLock) { lastVideoInfo } ?: return
        
        val timestamp = getStreamTimestamp()
        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        
        // 将心跳帧加入发送队列（使用异步发送）
        val frame = VideoFrame(bytes, bytes.size, timestamp, isKeyFrame)
        if (!videoSendQueue.offer(frame)) {
            // 队列满，尝试清空队列并加入心跳帧
            videoSendQueue.clear()
            videoSendQueue.offer(frame)
            Log.w(TAG, "心跳帧：队列满，清空队列")
        }
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
            val timestamp = getStreamTimestamp()
            val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            
            // 复制数据（用于异步发送和心跳）
            val bytes = ByteArray(info.size)
            data.position(info.offset)
            data.get(bytes)
            data.position(info.offset) // Restore position
            
            // Cache for heartbeat
            synchronized(heartbeatLock) {
                lastVideoDataBytes = bytes
                val infoCopy = MediaCodec.BufferInfo()
                infoCopy.set(0, info.size, info.presentationTimeUs, info.flags)
                lastVideoInfo = infoCopy
            }
            
            // 每 30 帧打印一次日志（用于调试）
            staticVideoFrameCount++
            val currentQueueSize = videoQueueSize.get()
            if (isKeyFrame || staticVideoFrameCount % 30 == 0) {
                Log.d(TAG, "收到视频数据: size=${info.size}, pts=${info.presentationTimeUs}, isKeyFrame=$isKeyFrame (总帧数=$staticVideoFrameCount, 队列大小=$currentQueueSize)")
            }

            // 分辨率切换后仅发关键帧直至首帧关键帧发送，便于播放端恢复
            if (resolutionChangePending.get() && !isKeyFrame) {
                return
            }

            // 将帧加入发送队列（异步发送，避免阻塞编码器回调线程）
            val frame = VideoFrame(bytes, info.size, timestamp, isKeyFrame)
            
            // 如果队列满了，根据策略处理：
            // 1. 如果是关键帧，清空队列并加入关键帧（确保关键帧能发送）
            // 2. 如果是非关键帧，尝试丢弃最旧的帧（FIFO策略）
            if (!videoSendQueue.offer(frame)) {
                // 队列满了
                if (isKeyFrame) {
                    // 关键帧：清空队列并加入关键帧（确保关键帧能发送）
                    // 注意：清空队列是 O(n) 操作，但只在队列满且是关键帧时执行，频率很低
                    val queueSize = currentQueueSize
                    videoSendQueue.clear()
                    videoQueueSize.set(0)
                    videoSendQueue.offer(frame)
                    videoQueueSize.incrementAndGet()
                    droppedFrames.addAndGet(queueSize)
                    Log.w(TAG, "队列满，清空队列以插入关键帧 (清空了 $queueSize 帧)")
                } else {
                    // 非关键帧：尝试丢弃最旧的帧（FIFO策略），为新帧腾出空间
                    // 注意：LinkedBlockingQueue 不支持直接移除最旧的元素，所以只能丢弃当前帧
                    // 但我们可以通过检查队列大小来优化：如果队列接近满，丢弃当前帧
                    droppedFrames.incrementAndGet()
                    val dropped = droppedFrames.get()
                    if (dropped % 30 == 0) {
                        Log.w(TAG, "队列满，丢弃非关键帧 (已丢弃 $dropped 帧, 队列大小=$currentQueueSize)")
                    }
                }
            } else {
                // 成功加入队列，更新大小计数
                videoQueueSize.incrementAndGet()
                
                // 如果队列大小超过阈值，记录警告（用于监控网络状况）
                if (currentQueueSize > 20) {
                    Log.w(TAG, "视频发送队列积压严重: $currentQueueSize 帧，可能存在网络问题")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理视频数据异常", e)
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
            val timestamp = getStreamTimestamp()
            
            // 复制数据（用于异步发送）
            val bytes = ByteArray(info.size)
            data.position(info.offset)
            data.get(bytes)
            data.position(info.offset) // Restore position

            // 将帧加入发送队列（异步发送，避免阻塞编码器回调线程）
            val frame = AudioFrame(bytes, info.size, timestamp)
            
            // 如果队列满了，直接丢弃（音频可以容忍丢帧）
            if (!audioSendQueue.offer(frame)) {
                // 队列满，丢弃（不记录日志，避免日志过多）
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理音频数据异常", e)
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
        
        // 确保发送线程已停止
        stopSendThreads()
        
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

    fun getDroppedFrames(): Int = droppedFrames.get()
    fun getSendErrorCount(): Int = sendErrorCount.get()
    fun resetDroppedFrames() {
        droppedFrames.set(0)
    }

    /**
     * 分辨率切换后设为 true，发送首帧关键帧后自动清除
     */
    fun setResolutionChangePending(pending: Boolean) {
        resolutionChangePending.set(pending)
    }

    /**
     * 分辨率切换完成后调用：更新元数据、发送 SPS/PPS、标记仅发关键帧直至首帧
     */
    fun onResolutionChangeComplete(width: Int, height: Int, videoBitrate: Int, fps: Int, audioSampleRate: Int, audioChannels: Int) {
        setMetadata(width, height, videoBitrate, fps, audioSampleRate, audioChannels)
        sendSpsPps()
        setResolutionChangePending(true)
    }
}

/**
 * 网络统计信息
 */
data class NetworkStats(
    val bytesSent: Long,
    val delayMs: Int,
    val packetLossPercent: Int
)

