import Foundation

class BitrateController {
    private let tag = "BitrateController"
    private var videoEncoder: VideoEncoder
    private var rtmpStreamer: RtmpStreamer
    
    private var running = false
    
    /**
     * Check if controller is running
     */
    func isRunning() -> Bool {
        return running
    }
    private var currentBitrate = 0
    private var minBitrate = 350_000  // 350kbps
    private var maxBitrate = 5_000_000 // 5Mbps
    private var baseBitrate = 2_000_000 // 2Mbps
    
    // FPS: do not change (keep 30fps for smooth experience)
    
    // Resolution management
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var originalWidth: Int = 0
    private var originalHeight: Int = 0
    
    // 三路编码：1080p -> 720p -> 480p（推流切换，不重启 encoder）
    private let resolutionLevels: [(width: Int, height: Int)] = [
        (1920, 1080), // 1080p, index 0
        (1280, 720),  // 720p,  index 1
        (854, 480)    // 480p,  index 2
    ]
    private var currentResolutionLevel: Int = 0
    
    // Callback for resolution change: (width, height) 或 推流切换 level index
    typealias ResolutionChangeCallback = (Int, Int) -> Void
    private var resolutionChangeCallback: ResolutionChangeCallback?
    
    private var monitorTimer: Timer?
    
    init(videoEncoder: VideoEncoder, rtmpStreamer: RtmpStreamer) {
        self.videoEncoder = videoEncoder
        self.rtmpStreamer = rtmpStreamer
    }
    
    /**
     * Set callback for resolution changes
     */
    func setResolutionChangeCallback(_ callback: ResolutionChangeCallback?) {
        resolutionChangeCallback = callback
    }
    
    /**
     * Initialize ABR controller
     */
    func initialize(initialBitrate: Int, width: Int, height: Int) {
        currentBitrate = initialBitrate
        baseBitrate = initialBitrate
        currentWidth = width
        currentHeight = height
        originalWidth = width
        originalHeight = height
        
        // Find initial resolution level
        currentResolutionLevel = resolutionLevels.firstIndex { $0.width == width && $0.height == height } ?? 0
        
        print("[\(tag)] ABR initialized: bitrate=\(initialBitrate), resolution=\(width)x\(height), level=\(currentResolutionLevel) (FPS fixed at 30)")
    }
    
    /**
     * Start monitoring and adjusting
     */
    func start() {
        guard !running else { return }
        
        running = true
        lastCheckTime = Int64(Date().timeIntervalSince1970 * 1000)
        lastBitrateChangeTime = lastCheckTime
        lastResolutionChangeTime = lastCheckTime
        lastDroppedFrames = rtmpStreamer.getDroppedFrames()
        
        // Start monitoring with a small initial delay to allow RTMP connection to stabilize
        // This prevents immediate bitrate adjustments before connection is ready
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self, self.running else { return }
            // Monitor with dynamic interval based on network conditions
            self.startMonitoring()
        }
        
        print("[\(tag)] ABR started (will begin monitoring in 1 second)")
    }
    
    /**
     * Start monitoring with dynamic interval（弱网时更频繁检查，切换更及时）
     */
    private func startMonitoring() {
        monitorTimer?.invalidate()
        
        let stats = rtmpStreamer.getStats()
        let sendErrorCount = rtmpStreamer.getSendErrorCount()
        let droppedFrames = stats?.droppedFrames ?? 0
        let droppedDelta = droppedFrames - lastDroppedFrames
        // 有阻塞或错误时缩短检查间隔：4s；否则 6s（比原来 10s 更及时）
        let hasCongestion = sendErrorCount > 2 || droppedDelta > 10
        let checkInterval: TimeInterval = hasCongestion ? 4.0 : 6.0
        
        if Thread.isMainThread {
            monitorTimer = Timer.scheduledTimer(withTimeInterval: checkInterval, repeats: true) { [weak self] timer in
                guard let self = self, self.running else {
                    timer.invalidate()
                    return
                }
                self.adjustBitrate()
                self.startMonitoring()
            }
            if let timer = monitorTimer {
                RunLoop.current.add(timer, forMode: .common)
            }
        } else {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.monitorTimer = Timer.scheduledTimer(withTimeInterval: checkInterval, repeats: true) { [weak self] timer in
                    guard let self = self, self.running else {
                        timer.invalidate()
                        return
                    }
                    self.adjustBitrate()
                    self.startMonitoring()
                }
                if let timer = self.monitorTimer {
                    RunLoop.current.add(timer, forMode: .common)
                }
            }
        }
    }
    
    /**
     * Stop monitoring
     */
    func stop() {
        guard running else { return }
        
        running = false
        monitorTimer?.invalidate()
        monitorTimer = nil
        
        print("[\(tag)] ABR stopped")
    }
    
    // Track dropped frames and errors
    private var lastDroppedFrames: Int = 0
    private var lastCheckTime: Int64 = 0
    private var lastBitrateChangeTime: Int64 = 0
    private var lastResolutionChangeTime: Int64 = 0
    private let minBitrateChangeInterval: Int64 = 15000   // 15s between bitrate changes
    private let minResolutionChangeIntervalDown: Int64 = 10000 // 10s 降分辨率间隔，弱网时更快切
    private let minResolutionChangeIntervalUp: Int64 = 18000   // 18s 升分辨率间隔，恢复时切回，避免来回抖
    private let minBitrateChangeRatio: Float = 0.25       // At least 25% change
    
    // Blocking thresholds: 阻塞不多降码率，阻塞过多降分辨率
    private let dropRateLight: Float = 3.0   // 轻度阻塞: 3~15 帧/秒 丢帧 → 降码率
    private let dropRateHeavy: Float = 12.0  // 重度阻塞: >12 帧/秒 丢帧 → 降分辨率（略放宽，更及时）
    
    /**
     * 根据阻塞程度一步到位选择目标分辨率+码率，避免一档一档降导致不流畅
     */
    private func adjustBitrate() {
        guard let stats = rtmpStreamer.getStats() else { return }
        
        let delay = stats.delayMs
        let packetLoss = stats.packetLossPercent
        let fps = getCurrentFps()
        let droppedFrames = stats.droppedFrames
        let sendErrorCount = rtmpStreamer.getSendErrorCount()
        
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let timeSinceLastCheck = max(1, now - lastCheckTime)
        lastCheckTime = now
        
        let droppedFramesDelta = droppedFrames - lastDroppedFrames
        lastDroppedFrames = droppedFrames
        let droppedFrameRate = Float(droppedFramesDelta) / Float(timeSinceLastCheck) * 1000.0
        
        print("[\(tag)] Blocking: delay=\(delay)ms, loss=\(packetLoss)%, fps=\(fps), bitrate=\(currentBitrate), dropped=\(droppedFramesDelta)/\(Int(timeSinceLastCheck))s, errors=\(sendErrorCount)")
        
        let timeSinceResChange = now - lastResolutionChangeTime
        let timeSinceBitrateChange = now - lastBitrateChangeTime
        
        // ----- 一步到位：按严重程度直接选目标档位+码率 -----
        let (targetLevel, targetBitrate) = computeTargetLevelAndBitrate(
            dropRate: droppedFrameRate,
            sendErrorCount: sendErrorCount,
            delay: delay,
            packetLoss: packetLoss,
            currentLevel: currentResolutionLevel,
            currentBitrate: currentBitrate
        )
        
        if targetLevel != currentResolutionLevel {
            if timeSinceResChange >= minResolutionChangeIntervalDown {
                let (w, h) = resolutionLevels[targetLevel]
                print("[\(tag)] One-step switch: level \(currentResolutionLevel) -> \(targetLevel) (\(w)x\(h)), bitrate=\(targetBitrate)")
                resolutionChangeCallback?(w, h)
                updateBitrate(targetBitrate)
                lastResolutionChangeTime = now
                lastBitrateChangeTime = now
                rtmpStreamer.resetDroppedFrames()
                lastDroppedFrames = 0
                return
            }
        } else if targetBitrate != currentBitrate {
            if timeSinceBitrateChange >= minBitrateChangeInterval {
                let ratio = abs(Float(targetBitrate - currentBitrate)) / Float(max(1, currentBitrate))
                if ratio >= minBitrateChangeRatio {
                    updateBitrate(targetBitrate)
                    lastBitrateChangeTime = now
                    rtmpStreamer.resetDroppedFrames()
                    lastDroppedFrames = 0
                }
            }
            return
        }
        
        // ----- 网络良好：可升分辨率（弱网恢复）-----
        if delay < 120 && packetLoss < 2 && sendErrorCount == 0 && droppedFrameRate < 1.5 && currentResolutionLevel > 0 {
            if timeSinceResChange >= minResolutionChangeIntervalUp {
                let upLevel = currentResolutionLevel - 1
                let (w, h) = resolutionLevels[upLevel]
                let bitrateForUp = upLevel == 0 ? baseBitrate : (currentBitrate + 200_000)
                print("[\(tag)] Good network -> upgrade resolution: level \(currentResolutionLevel) -> \(upLevel) (\(w)x\(h))")
                resolutionChangeCallback?(w, h)
                updateBitrate(min(bitrateForUp, baseBitrate))
                lastResolutionChangeTime = now
                lastBitrateChangeTime = now
                rtmpStreamer.resetDroppedFrames()
                lastDroppedFrames = 0
                return
            }
        }
        // ----- 良好：缓慢升码率 -----
        if delay < 100 && packetLoss < 1 && sendErrorCount == 0 && droppedFrameRate < 1.0 && currentBitrate < baseBitrate {
            if timeSinceBitrateChange >= minBitrateChangeInterval {
                let newBitrate = increaseBitrate(ratio: 0.1)
                updateBitrate(newBitrate)
                lastBitrateChangeTime = now
            }
        }
    }
    
    /// 根据丢帧率、错误数、延迟等一步到位计算目标档位和码率
    private func computeTargetLevelAndBitrate(dropRate: Float, sendErrorCount: Int, delay: Int, packetLoss: Int, currentLevel: Int, currentBitrate: Int) -> (level: Int, bitrate: Int) {
        // 极差：直接 480p + 最低码率
        if dropRate >= 18.0 || sendErrorCount >= 8 || delay >= 800 || packetLoss >= 15 {
            return (2, minBitrate)
        }
        // 很差：直接 480p 或 720p（当前 1080p 则 480p，当前 720p 则 480p）
        if dropRate >= 12.0 || sendErrorCount >= 5 || delay >= 500 || packetLoss >= 8 {
            let targetLevel = min(2, currentLevel + 2)  // 最多降两档，一步到位
            let bit = targetLevel == 2 ? minBitrate : max(minBitrate, 500_000)
            return (targetLevel, bit)
        }
        // 较差：降一档或只降码率
        if dropRate >= dropRateLight || sendErrorCount >= 2 || delay >= 300 || packetLoss >= 5 {
            if currentLevel < 2 {
                let targetLevel = currentLevel + 1
                let bit = targetLevel == 2 ? minBitrate : max(minBitrate, Int(Float(currentBitrate) * 0.6))
                return (targetLevel, bit)
            }
            return (currentLevel, max(minBitrate, Int(Float(currentBitrate) * 0.7)))
        }
        // 一般：只降码率
        if dropRate >= 1.0 || sendErrorCount >= 1 {
            return (currentLevel, max(minBitrate, Int(Float(currentBitrate) * 0.75)))
        }
        return (currentLevel, currentBitrate)
    }
    
    /**
     * Reduce bitrate
     */
    private func reduceBitrate(ratio: Float) -> Int {
        let newBitrate = max(Int(Float(currentBitrate) * (1 - ratio)), minBitrate)
        // Ensure bitrate change is significant enough to avoid frequent adjustments
        // Increased minimum change to 20% of base bitrate (was 10%)
        let minChange = Int(Float(baseBitrate) * 0.2) // At least 20% of base bitrate
        let actualNewBitrate = abs(currentBitrate - newBitrate) < minChange ?
            max(currentBitrate - minChange, minBitrate) : newBitrate
        let actualReduction = Int((Float(currentBitrate - actualNewBitrate) / Float(currentBitrate)) * 100)
        print("[\(tag)] Reducing bitrate: \(currentBitrate) -> \(actualNewBitrate) (reduce \(actualReduction)%)")
        return actualNewBitrate
    }
    
    /**
     * Increase bitrate
     */
    private func increaseBitrate(ratio: Float) -> Int {
        let newBitrate = min(Int(Float(currentBitrate) * (1 + ratio)), maxBitrate)
        // Ensure bitrate change is significant enough to avoid frequent adjustments
        // Increased minimum change to 20% of base bitrate (was 10%)
        let minChange = Int(Float(baseBitrate) * 0.2) // At least 20% of base bitrate
        let actualNewBitrate = abs(newBitrate - currentBitrate) < minChange ?
            min(currentBitrate + minChange, maxBitrate) : newBitrate
        let actualIncrease = Int((Float(actualNewBitrate - currentBitrate) / Float(currentBitrate)) * 100)
        print("[\(tag)] Increasing bitrate: \(currentBitrate) -> \(actualNewBitrate) (increase \(actualIncrease)%)")
        return actualNewBitrate
    }
    
    /// 480p 时推流码率上限，为下行留余量（下行 ~674Kbps 时减少卡缓冲）
    private let bitrateCap480p = 450_000  // 450kbps @ 480p，留 ~200kbps 余量给关键帧尖峰和波动
    
    /**
     * Update bitrate on the active encoder (multi-encoder: only the one being pushed)
     * 480p 时应用码率上限，避免拉流端缓冲/卡顿
     */
    private func updateBitrate(_ newBitrate: Int) {
        let effectiveBitrate: Int
        if currentResolutionLevel == 2 {
            effectiveBitrate = min(newBitrate, bitrateCap480p)
        } else {
            effectiveBitrate = newBitrate
        }
        currentBitrate = effectiveBitrate
        rtmpStreamer.getActiveVideoEncoder()?.updateBitrate(effectiveBitrate)
        print("[\(tag)] Bitrate updated: \(effectiveBitrate) bps\(currentResolutionLevel == 2 && effectiveBitrate == bitrateCap480p ? " (480p cap)" : "")")
    }
    
    /**
     * Get current FPS (from active encoder). FPS is not changed by ABR.
     */
    private func getCurrentFps() -> Float {
        return rtmpStreamer.getActiveVideoEncoder()?.getCurrentFps() ?? 0
    }
    
    /**
     * Manually set bitrate
     */
    func setBitrate(_ bitrate: Int) {
        let clampedBitrate = max(minBitrate, min(bitrate, maxBitrate))
        updateBitrate(clampedBitrate)
        baseBitrate = clampedBitrate
    }
    
    /**
     * Get current bitrate
     */
    func getCurrentBitrate() -> Int {
        return currentBitrate
    }
    
    /**
     * Update current resolution (called after resolution change)
     * 切到 480p 时重新应用码率上限，拉流端更少卡缓冲
     */
    func updateResolution(width: Int, height: Int) {
        currentWidth = width
        currentHeight = height
        
        // Find new resolution level
        if let level = resolutionLevels.firstIndex(where: { $0.width == width && $0.height == height }) {
            currentResolutionLevel = level
        }
        
        // 切到 480p 时立即对当前码率做上限处理并应用到编码器
        if currentResolutionLevel == 2 {
            updateBitrate(currentBitrate)
        }
        
        print("[\(tag)] Resolution updated: \(width)x\(height), level=\(currentResolutionLevel)")
    }
    
    /**
     * Release resources
     */
    func release() {
        stop()
    }
}
