package com.bb.rtmp

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class BitrateController(
    private val videoEncoder: VideoEncoder,
    private val rtmpStreamer: RtmpStreamer,
    private val cameraController: CameraController
) {
    private val TAG = "BitrateController"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isRunning = AtomicLong(0)
    
    private var currentBitrate = AtomicInteger(0)
    private var minBitrate = 500000  // 500kbps
    private var maxBitrate = 5000000 // 5Mbps
    private var baseBitrate = 2000000 // 2Mbps
    
    private var currentWidth = 0
    private var currentHeight = 0
    private val supportedResolutions = listOf(
        Resolution(640, 360),   // 360p
        Resolution(854, 480),    // 480p
        Resolution(1280, 720),   // 720p
        Resolution(1920, 1080),  // 1080p
        Resolution(2560, 1440)   // 2K
    )
    private var currentResolutionIndex = 2 // 默认 720p

    data class Resolution(val width: Int, val height: Int)

    /**
     * 初始化自适应码率控制
     */
    fun initialize(initialBitrate: Int, width: Int, height: Int) {
        currentBitrate.set(initialBitrate)
        baseBitrate = initialBitrate
        currentWidth = width
        currentHeight = height
        
        // 找到当前分辨率对应的索引
        supportedResolutions.forEachIndexed { index, res ->
            if (res.width == width && res.height == height) {
                currentResolutionIndex = index
            }
        }
    }

    /**
     * 开始监控和调整
     */
    fun start() {
        if (isRunning.getAndIncrement() > 0) {
            return // 已经在运行
        }

        scope.launch {
            while (isRunning.get() > 0) {
                try {
                    adjustBitrate()
                    delay(3000) // 每 3 秒检查一次
                } catch (e: Exception) {
                    Log.e(TAG, "码率调整异常", e)
                }
            }
        }

        Log.d(TAG, "自适应码率控制已启动")
    }

    /**
     * 停止监控
     */
    fun stop() {
        isRunning.set(0)
        Log.d(TAG, "自适应码率控制已停止")
    }

    /**
     * 调整码率
     */
    private suspend fun adjustBitrate() {
        val stats = rtmpStreamer.getStats() ?: return
        val currentFps = getCurrentFps() // 需要从编码器获取，这里简化处理

        val delay = stats.delayMs
        val packetLoss = stats.packetLossPercent
        val fps = currentFps

        Log.d(TAG, "网络统计: delay=${delay}ms, loss=${packetLoss}%, fps=$fps")

        val newBitrate = when {
            // 网络状况差：延迟高或丢包率高
            delay > 500 || packetLoss > 5 -> {
                reduceBitrate(0.2f) // 降低 20%
            }
            // 编码帧率低：可能是码率过高或设备性能不足
            fps < 20 -> {
                reduceBitrate(0.15f) // 降低 15%
            }
            // 网络状况良好且帧率稳定：可以适当提高码率
            delay < 100 && packetLoss < 1 && fps >= 28 && currentBitrate.get() < baseBitrate -> {
                increaseBitrate(0.1f) // 提高 10%
            }
            else -> {
                currentBitrate.get() // 保持当前码率
            }
        }

        if (newBitrate != currentBitrate.get()) {
            updateBitrate(newBitrate)
        }
    }

    /**
     * 降低码率
     */
    private fun reduceBitrate(ratio: Float): Int {
        val current = currentBitrate.get()
        val newBitrate = (current * (1 - ratio)).toInt().coerceAtLeast(minBitrate)
        Log.d(TAG, "降低码率: $current -> $newBitrate (降低 ${(ratio * 100).toInt()}%)")
        return newBitrate
    }

    /**
     * 提高码率
     */
    private fun increaseBitrate(ratio: Float): Int {
        val current = currentBitrate.get()
        val newBitrate = (current * (1 + ratio)).toInt().coerceAtMost(maxBitrate)
        Log.d(TAG, "提高码率: $current -> $newBitrate (提高 ${(ratio * 100).toInt()}%)")
        return newBitrate
    }



    /**
     * 更新码率
     */
    private fun updateBitrate(newBitrate: Int) {
        currentBitrate.set(newBitrate)
        videoEncoder.updateBitrate(newBitrate)
        Log.d(TAG, "码率已更新: $newBitrate bps")
    }

    /**
     * 获取当前帧率（简化实现，实际应从编码器获取）
     */
    private fun getCurrentFps(): Float {
        // TODO: 实际应从 VideoEncoder 获取编码帧率统计
        // 这里返回一个模拟值，实际实现需要添加帧率统计
        return 30f
    }

    /**
     * 手动设置码率
     */
    fun setBitrate(bitrate: Int) {
        val clampedBitrate = bitrate.coerceIn(minBitrate, maxBitrate)
        updateBitrate(clampedBitrate)
        baseBitrate = clampedBitrate
    }

    /**
     * 获取当前码率
     */
    fun getCurrentBitrate(): Int = currentBitrate.get()

    /**
     * 释放资源
     */
    fun release() {
        stop()
        scope.cancel()
    }
}

