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
    private var minBitrate = 350000   // 350kbps
    private var maxBitrate = 5000000  // 5Mbps
    private var baseBitrate = 2000000 // 2Mbps
    
    private var currentWidth = 0
    private var currentHeight = 0
    private val resolutionLevels = listOf(
        Resolution(1920, 1080), // 1080p, index 0
        Resolution(1280, 720),  // 720p,  index 1
        Resolution(854, 480)     // 480p,  index 2
    )
    private var currentResolutionIndex = 0

    data class Resolution(val width: Int, val height: Int)

    private var resolutionChangeCallback: ((Int, Int) -> Unit)? = null

    fun setResolutionChangeCallback(callback: ((Int, Int) -> Unit)?) {
        resolutionChangeCallback = callback
    }

    private var lastDroppedFrames = 0
    private var lastCheckTime = 0L
    private var lastBitrateChangeTime = 0L
    private var lastResolutionChangeTime = 0L
    private var lastDowngradeTime = 0L
    private var downgradeConfirmCount = 0
    private var upgradeConfirmCount = 0
    private val requiredConfirmCount = 2
    private val minBitrateChangeIntervalMs = 15000L
    private val minResolutionChangeIntervalDownMs = 22000L   // 22s 降档间隔，避免临界频繁切换导致拉流卡
    private val minResolutionChangeIntervalUpMs = 28000L     // 28s 升档间隔
    private val minUpgradeAfterDowngradeMs = 35000L          // 降档后至少 35s 才允许升档
    private val minBitrateChangeRatio = 0.25f
    private val dropRateLight = 3.0f
    private val bitrateCap480p = 450000  // 480p 时推流码率上限，为下行留余量

    /**
     * 初始化自适应码率控制
     */
    fun initialize(initialBitrate: Int, width: Int, height: Int) {
        currentBitrate.set(initialBitrate)
        baseBitrate = initialBitrate
        currentWidth = width
        currentHeight = height
        lastCheckTime = System.currentTimeMillis()
        lastBitrateChangeTime = lastCheckTime
        lastResolutionChangeTime = lastCheckTime
        lastDowngradeTime = 0L
        downgradeConfirmCount = 0
        upgradeConfirmCount = 0
        lastDroppedFrames = rtmpStreamer.getDroppedFrames()
        resolutionLevels.forEachIndexed { index, res ->
            if (res.width == width && res.height == height) {
                currentResolutionIndex = index
            }
        }
        Log.d(TAG, "ABR 初始化: bitrate=$initialBitrate, resolution=${width}x${height}, level=$currentResolutionIndex")
    }

    /**
     * 开始监控和调整
     */
    fun start() {
        if (isRunning.getAndIncrement() > 0) {
            return
        }
        scope.launch {
            while (isRunning.get() > 0) {
                try {
                    adjustBitrate()
                    val sendErrors = rtmpStreamer.getSendErrorCount()
                    val dropped = rtmpStreamer.getDroppedFrames()
                    val hasCongestion = sendErrors > 2 || (dropped - lastDroppedFrames) > 10
                    val checkInterval = if (hasCongestion) 4000L else 6000L
                    delay(checkInterval)
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
     * 根据阻塞程度一步到位选择目标分辨率+码率（与 iOS 一致）
     */
    private suspend fun adjustBitrate() {
        val stats = rtmpStreamer.getStats() ?: return
        val delay = stats.delayMs
        val packetLoss = stats.packetLossPercent
        val fps = videoEncoder.getCurrentFps()
        val droppedFrames = rtmpStreamer.getDroppedFrames()
        val sendErrorCount = rtmpStreamer.getSendErrorCount()
        val now = System.currentTimeMillis()
        val timeSinceLastCheck = maxOf(1, now - lastCheckTime)
        lastCheckTime = now
        val droppedDelta = droppedFrames - lastDroppedFrames
        lastDroppedFrames = droppedFrames  // 下一轮用当前值算 delta
        val droppedFrameRate = droppedDelta.toFloat() / timeSinceLastCheck * 1000f

        Log.d(TAG, "Blocking: delay=${delay}ms, loss=$packetLoss%, fps=$fps, bitrate=${currentBitrate.get()}, dropped=$droppedDelta/${timeSinceLastCheck}ms, errors=$sendErrorCount")

        val timeSinceResChange = now - lastResolutionChangeTime
        val timeSinceBitrateChange = now - lastBitrateChangeTime
        val current = currentBitrate.get()

        val (targetLevel, targetBitrate) = computeTargetLevelAndBitrate(
            dropRate = droppedFrameRate,
            sendErrorCount = sendErrorCount,
            delay = delay,
            packetLoss = packetLoss,
            currentLevel = currentResolutionIndex,
            currentBitrate = current
        )

        if (targetLevel != currentResolutionIndex) {
            val isDowngrade = targetLevel > currentResolutionIndex
            val downIntervalOk = timeSinceResChange >= minResolutionChangeIntervalDownMs
            if (isDowngrade) {
                downgradeConfirmCount++
                upgradeConfirmCount = 0
                if (downIntervalOk && downgradeConfirmCount >= requiredConfirmCount) {
                    val res = resolutionLevels[targetLevel]
                    Log.d(TAG, "One-step switch (confirmed x$downgradeConfirmCount): level $currentResolutionIndex -> $targetLevel (${res.width}x${res.height}), bitrate=$targetBitrate")
                    lastDowngradeTime = now
                    downgradeConfirmCount = 0
                    resolutionChangeCallback?.invoke(res.width, res.height)
                    updateBitrate(targetBitrate)
                    lastResolutionChangeTime = now
                    lastBitrateChangeTime = now
                    rtmpStreamer.resetDroppedFrames()
                    lastDroppedFrames = 0
                    return
                }
                if (!downIntervalOk || downgradeConfirmCount < requiredConfirmCount) return
            } else {
                downgradeConfirmCount = 0
            }
        } else {
            downgradeConfirmCount = 0
        }
        if (targetBitrate != current) {
            if (timeSinceBitrateChange >= minBitrateChangeIntervalMs) {
                val ratio = kotlin.math.abs(targetBitrate - current).toFloat() / maxOf(1, current)
                if (ratio >= minBitrateChangeRatio) {
                    updateBitrate(targetBitrate)
                    lastBitrateChangeTime = now
                    rtmpStreamer.resetDroppedFrames()
                    lastDroppedFrames = 0
                }
            }
            return
        }

        // 网络良好：可升分辨率，需连续确认 + 降档冷却
        val timeSinceDowngrade = now - lastDowngradeTime
        val upgradeCooldownOk = lastDowngradeTime == 0L || timeSinceDowngrade >= minUpgradeAfterDowngradeMs
        val goodNetwork = delay < 80 && packetLoss < 1 && sendErrorCount == 0 && droppedFrameRate < 0.8f
        if (goodNetwork && currentResolutionIndex > 0) {
            upgradeConfirmCount++
            if (timeSinceResChange >= minResolutionChangeIntervalUpMs && upgradeCooldownOk && upgradeConfirmCount >= requiredConfirmCount) {
                val upLevel = currentResolutionIndex - 1
                val res = resolutionLevels[upLevel]
                val bitrateForUp = if (upLevel == 0) baseBitrate else (current + 200000)
                Log.d(TAG, "Good network -> upgrade (confirmed x$upgradeConfirmCount): level $currentResolutionIndex -> $upLevel (${res.width}x${res.height})")
                upgradeConfirmCount = 0
                resolutionChangeCallback?.invoke(res.width, res.height)
                updateBitrate(minOf(bitrateForUp, baseBitrate))
                lastResolutionChangeTime = now
                lastBitrateChangeTime = now
                rtmpStreamer.resetDroppedFrames()
                lastDroppedFrames = 0
                return
            }
        } else {
            upgradeConfirmCount = 0
        }
        // 良好：缓慢升码率
        if (delay < 100 && packetLoss < 1 && sendErrorCount == 0 && droppedFrameRate < 1.0f && current < baseBitrate) {
            if (timeSinceBitrateChange >= minBitrateChangeIntervalMs) {
                val newBitrate = increaseBitrate(0.1f)
                updateBitrate(newBitrate)
                lastBitrateChangeTime = now
            }
        }
    }

    private fun computeTargetLevelAndBitrate(
        dropRate: Float,
        sendErrorCount: Int,
        delay: Int,
        packetLoss: Int,
        currentLevel: Int,
        currentBitrate: Int
    ): Pair<Int, Int> {
        if (dropRate >= 18f || sendErrorCount >= 8 || delay >= 800 || packetLoss >= 15) {
            return Pair(2, minBitrate)
        }
        if (dropRate >= 12f || sendErrorCount >= 5 || delay >= 500 || packetLoss >= 8) {
            val targetLevel = minOf(2, currentLevel + 2)
            val bit = if (targetLevel == 2) minBitrate else maxOf(minBitrate, 500000)
            return Pair(targetLevel, bit)
        }
        if (dropRate >= dropRateLight || sendErrorCount >= 2 || delay >= 300 || packetLoss >= 5) {
            val borderline = dropRate < 10f && delay < 500 && sendErrorCount < 4
            if (borderline && currentLevel == 1) {
                return Pair(1, maxOf(minBitrate, (currentBitrate * 0.65f).toInt()))
            }
            if (currentLevel == 0 && (dropRate >= 6f || delay >= 400 || sendErrorCount >= 3)) {
                return Pair(2, minBitrate)
            }
            if (currentLevel < 2) {
                val targetLevel = currentLevel + 1
                val bit = if (targetLevel == 2) minBitrate else maxOf(minBitrate, (currentBitrate * 0.6f).toInt())
                return Pair(targetLevel, bit)
            }
            return Pair(currentLevel, maxOf(minBitrate, (currentBitrate * 0.7f).toInt()))
        }
        if (dropRate >= 1f || sendErrorCount >= 1) {
            return Pair(currentLevel, maxOf(minBitrate, (currentBitrate * 0.75f).toInt()))
        }
        return Pair(currentLevel, currentBitrate)
    }

    private fun reduceBitrate(ratio: Float): Int {
        val current = currentBitrate.get()
        val newBitrate = (current * (1 - ratio)).toInt().coerceAtLeast(minBitrate)
        val minChange = (baseBitrate * 0.2).toInt()
        return if (kotlin.math.abs(current - newBitrate) < minChange) {
            (current - minChange).coerceAtLeast(minBitrate)
        } else {
            newBitrate
        }
    }

    private fun increaseBitrate(ratio: Float): Int {
        val current = currentBitrate.get()
        val newBitrate = (current * (1 + ratio)).toInt().coerceAtMost(maxBitrate)
        val minChange = (baseBitrate * 0.2).toInt()
        return if (kotlin.math.abs(newBitrate - current) < minChange) {
            (current + minChange).coerceAtMost(maxBitrate)
        } else {
            newBitrate
        }
    }

    private fun updateBitrate(newBitrate: Int) {
        val effectiveBitrate = if (currentResolutionIndex == 2) {
            minOf(newBitrate, bitrateCap480p)
        } else {
            newBitrate
        }
        currentBitrate.set(effectiveBitrate)
        videoEncoder.updateBitrate(effectiveBitrate)
        Log.d(TAG, "码率已更新: $effectiveBitrate bps${if (currentResolutionIndex == 2 && effectiveBitrate == bitrateCap480p) " (480p cap)" else ""}")
    }

    /**
     * 分辨率切换后由外部调用，更新当前档位并应用 480p 码率上限
     */
    fun updateResolution(width: Int, height: Int) {
        currentWidth = width
        currentHeight = height
        resolutionLevels.forEachIndexed { index, res ->
            if (res.width == width && res.height == height) {
                currentResolutionIndex = index
            }
        }
        if (currentResolutionIndex == 2) {
            updateBitrate(currentBitrate.get())
        }
        Log.d(TAG, "Resolution updated: ${width}x${height}, level=$currentResolutionIndex")
    }

    fun setBitrate(bitrate: Int) {
        val clampedBitrate = bitrate.coerceIn(minBitrate, maxBitrate)
        updateBitrate(clampedBitrate)
        baseBitrate = clampedBitrate
    }

    fun getCurrentBitrate(): Int = currentBitrate.get()

    fun release() {
        stop()
        scope.cancel()
    }
}
