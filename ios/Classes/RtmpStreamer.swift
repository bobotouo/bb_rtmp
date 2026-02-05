import Foundation

class RtmpStreamer {
    private let tag = "RtmpStreamer"
    private var rtmpWrapper: RtmpWrapper?
    private var rtmpUrl: String = ""
    private var isStreaming = false
    private var isRefreshing = false
    private var startTime: Int64 = 0
    private let stateLock = NSLock()
    
    // Status callback
    typealias StatusCallback = (String, String?) -> Void
    private var statusCallback: StatusCallback?
    
    func setStatusCallback(_ callback: StatusCallback?) {
        statusCallback = callback
    }
    
    private var videoEncoders: [VideoEncoder] = []
    private var activeEncoderIndex: Int = 0
    private let activeEncoderLock = NSLock()
    
    private var audioEncoder: AudioEncoder?
    
    // Store current bitrate to preserve it during reconnection
    private var currentBitrate: Int = 2_000_000
    private var savedSps: Data?
    private var savedPps: Data?
    
    // Cached metadata for reconnection
    private var metaWidth: Int = 0
    private var metaHeight: Int = 0
    private var metaVideoBitrate: Int = 0
    private var metaFps: Int = 0
    private var metaAudioSampleRate: Int = 0
    private var metaAudioChannels: Int = 0
    
    // Heartbeat（只存当前推流路的最后一帧）
    private var lastVideoData: Data?
    private var lastVideoInfo: VideoEncoder.BufferInfo?
    private var lastVideoEncoderIndex: Int = 0
    private var heartbeatTimer: DispatchSourceTimer?
    private let heartbeatQueue = DispatchQueue(label: "com.bb_rtmp.heartbeat")
    
    // After resolution change: drop video until new keyframe sent (so playback can decode)
    private var resolutionChangePending = false
    private var resolutionChangePendingStartMs: Int64 = 0  // 超时后强制恢复发帧，避免视频长时间卡住、音频继续
    private let resolutionChangeLock = NSLock()
    private let resolutionChangePendingTimeoutMs: Int64 = 150  // 最多等 150ms，超时即发帧，减少卡帧

    func setResolutionChangePending(_ pending: Bool) {
        resolutionChangeLock.lock()
        resolutionChangePending = pending
        resolutionChangePendingStartMs = pending ? Int64(Date().timeIntervalSince1970 * 1000) : 0
        resolutionChangeLock.unlock()
        if pending { print("[\(tag)] Resolution change pending, will drop video until keyframe or \(resolutionChangePendingTimeoutMs)ms") }
    }
    
    /// 弱网/发送队列吃满时返回 true。上层应在背压时只喂当前路、不喂预热路，避免 VT 与缩放 buffer 堆积导致内存爆炸。
    /// 阈值收紧：队列 >= 3 即视为背压，减少双路编码堆积
    func isUnderBackpressure() -> Bool {
        queueSizeLock.lock()
        let size = videoQueueSize
        queueSizeLock.unlock()
        return size >= 3
    }
    
    /// 当前参与推流的那路 encoder
    func getActiveVideoEncoder() -> VideoEncoder? {
        activeEncoderLock.lock()
        let idx = activeEncoderIndex
        activeEncoderLock.unlock()
        guard idx >= 0, idx < videoEncoders.count else { return nil }
        return videoEncoders[idx]
    }
    
    func getActiveEncoderIndex() -> Int {
        activeEncoderLock.lock()
        defer { activeEncoderLock.unlock() }
        return activeEncoderIndex
    }
    
    /// 切换推流到指定 encoder（1080p=0, 720p=1, 480p=2）。不重启编码，只切换发送哪一路。
    func switchToEncoder(index: Int) {
        guard index >= 0, index < videoEncoders.count else { return }
        activeEncoderLock.lock()
        if activeEncoderIndex == index {
            activeEncoderLock.unlock()
            return
        }
        activeEncoderIndex = index
        let encoder = videoEncoders[index]
        activeEncoderLock.unlock()
        
        let (sps, pps) = encoder.getSpsPps()
        if let sps = sps, let pps = pps {
            savedSps = sps
            savedPps = pps
            let (w, h) = encoder.getResolution()
            metaWidth = w
            metaHeight = h
            /* 必须先 setMetadata 再 sendSpsPps：C++ 在分辨率变化时会置 sent_video_config=false，下一帧带 SPS/PPS 才会重发 AVC 序列头，SRS 才能正确切到新分辨率并解码 */
            stateLock.lock()
            let wrapper = rtmpWrapper
            stateLock.unlock()
            if isStreaming, wrapper != nil, metaVideoBitrate > 0 {
                _ = wrapper?.setMetadata(withWidth: Int32(w), height: Int32(h), videoBitrate: Int32(metaVideoBitrate), fps: Int32(metaFps), audioSampleRate: Int32(metaAudioSampleRate), audioChannels: Int32(metaAudioChannels))
            }
            if isStreaming {
                // 高到低切换：用当前流时间戳发 AVC 头，与即将的关键帧时间对齐，拉流端才能正确恢复不卡住
                _ = sendSpsPps(sps: sps, pps: pps, timestamp: getStreamTimestamp())
            }
            setResolutionChangePending(true)  // 只发关键帧直至首帧关键帧发出，播放端可恢复
            print("[\(tag)] Switched to encoder index=\(index) \(w)x\(h)")
            encoder.requestKeyFrame() // 切换后尽快出关键帧，懒编码升回 1080p 时解码正常
        } else {
            print("[\(tag)] switchToEncoder(\(index)): no SPS/PPS yet, will use on next keyframe")
        }
    }

    /// 多路编码：传入多个 encoder（如 1080p/720p/480p），推流时只发送 active 那路的输出
    func initialize(url: String, videoEncoders: [VideoEncoder], activeEncoderIndex: Int, audioEncoder: AudioEncoder?) -> Bool {
        self.rtmpUrl = url
        self.videoEncoders = videoEncoders
        self.activeEncoderIndex = min(max(0, activeEncoderIndex), videoEncoders.count - 1)
        self.audioEncoder = audioEncoder
        
        let wrapper = RtmpWrapper()
        let result = wrapper.initialize(url)
        
        guard result == 0 else { return false }
        self.rtmpWrapper = wrapper
        
        for (index, enc) in videoEncoders.enumerated() {
            enc.setCallback(MultiVideoEncoderCallbackImpl(streamer: self, encoderIndex: index))
        }
        audioEncoder?.setCallback(AudioEncoderCallbackImpl(streamer: self))
        return true
    }
    
    /// 兼容单路：单 encoder 时也可用
    func initialize(url: String, videoEncoder: VideoEncoder, audioEncoder: AudioEncoder?) -> Bool {
        return initialize(url: url, videoEncoders: [videoEncoder], activeEncoderIndex: 0, audioEncoder: audioEncoder)
    }
    
    func setMetadata(width: Int, height: Int, videoBitrate: Int, fps: Int, audioSampleRate: Int, audioChannels: Int) {
        self.metaWidth = width
        self.metaHeight = height
        self.metaVideoBitrate = videoBitrate
        self.currentBitrate = videoBitrate // Update current bitrate
        self.metaFps = fps
        self.metaAudioSampleRate = audioSampleRate
        self.metaAudioChannels = audioChannels
        
        stateLock.lock()
        let wrapper = rtmpWrapper
        stateLock.unlock()
        
        _ = wrapper?.setMetadata(withWidth: Int32(width), height: Int32(height), videoBitrate: Int32(videoBitrate), fps: Int32(fps), audioSampleRate: Int32(audioSampleRate), audioChannels: Int32(audioChannels))
    }
    
    func start() {
        guard !isStreaming else { return }
        startTime = Int64(Date().timeIntervalSince1970 * 1000)
        isStreaming = true
        
        // Reset reconnect tracking when starting
        reconnectCount = 0
        consecutiveErrors = 0
        lastErrorTime = 0
        lastReconnectTime = 0
        reconnectSuccessTime = Int64(Date().timeIntervalSince1970 * 1000) // Set initial success time
        
        if let sps = savedSps, let pps = savedPps {
            sendSpsPps(sps: sps, pps: pps)
        }
    }
    
    func stop() {
        isStreaming = false
        stopHeartbeat()
        
        // Reset reconnect tracking when stopping
        reconnectCount = 0
        consecutiveErrors = 0
        lastErrorTime = 0
        lastReconnectTime = 0
        reconnectSuccessTime = 0
    }
    
    private func getStreamTimestamp() -> Int {
        // Use relative timestamp from stream start
        // This ensures continuity even after reconnection
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let elapsed = now - startTime
        // Ensure timestamp is non-negative and reasonable
        return Int(max(0, elapsed))
    }
    
    /// 发送 AVC 序列头（SPS/PPS）。高到低切换时必须用当前流时间戳，否则拉流端会认为配置在 0、下一帧在 60s 导致画面卡住需刷新。
    private func sendSpsPps(sps: Data, pps: Data, timestamp: Int = 0) -> Int32 {
        stateLock.lock()
        let wrapper = rtmpWrapper
        stateLock.unlock()
        
        guard let wrapper = wrapper else { return -1 }
        var data = Data()
        data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        data.append(sps)
        data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        data.append(pps)
        return wrapper.sendVideo(data, timestamp: timestamp, isKeyFrame: true)
    }
    
    // Video send queue for async sending (avoid blocking encoder callback thread)
    // Use serial queue with limited concurrency to prevent memory buildup
    private let videoSendQueue = DispatchQueue(label: "com.bb_rtmp.video_send", qos: .userInitiated)
    private var videoQueueSize: Int = 0
    private let queueSizeLock = NSLock()
    private let maxVideoQueueSize = 5  // At most 5 frames in flight (~0.17s)
    private var droppedFrames: Int = 0
    private let droppedFramesLock = NSLock()
    
    // Hard bound: at most 5 blocks enqueued (each holds a frame copy) → prevents memory explosion
    private let enqueuePermit = DispatchSemaphore(value: 5)
    
    // Track if we're currently handling errors to prevent concurrent error handling
    private var isHandlingError = false
    private let errorHandlingLock = NSLock()
    
    /// encoderIndex: 多路时标记来自哪路，发送前丢弃「非当前推流路」的帧，避免 0.25s 全局丢帧卡顿
    fileprivate func sendVideoData(data: Data, info: VideoEncoder.BufferInfo, isKeyFrame: Bool, encoderIndex: Int = 0) {
        guard isStreaming else { return }
        
        stateLock.lock()
        let refreshing = isRefreshing
        let wrapper = rtmpWrapper
        stateLock.unlock()
        if refreshing || wrapper == nil { return }
        
        resolutionChangeLock.lock()
        var pending = resolutionChangePending
        let pendingStart = resolutionChangePendingStartMs
        resolutionChangeLock.unlock()
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        if pending && !isKeyFrame {
            if pendingStart > 0 && (nowMs - pendingStart) >= resolutionChangePendingTimeoutMs {
                resolutionChangeLock.lock()
                resolutionChangePending = false
                resolutionChangePendingStartMs = 0
                resolutionChangeLock.unlock()
                print("[\(tag)] Resolution pending timeout, resuming video send")
            } else {
                return
            }
        }
        
        // 只把当前推流路的最后一帧留给 heartbeat
        if encoderIndex == getActiveEncoderIndex() {
            lastVideoData = data
            lastVideoInfo = info
            lastVideoEncoderIndex = encoderIndex
        }
        
        // CRITICAL: Check queue size BEFORE async dispatch to prevent memory explosion
        // If queue is full, drop frame immediately (don't even dispatch to async queue)
        queueSizeLock.lock()
        let currentQueueSize = videoQueueSize
        queueSizeLock.unlock()
        
        // Hard bound: do not enqueue more than N frames (prevents memory explosion)
        let enqueueTimeout: DispatchTime = isKeyFrame ? .now() + 0.05 : .now() + 0.001
        if enqueuePermit.wait(timeout: enqueueTimeout) != .success {
            droppedFramesLock.lock()
            droppedFrames += 1
            let totalDropped = droppedFrames
            droppedFramesLock.unlock()
            if totalDropped % 60 == 0 {
                print("[\(tag)] Send queue full, dropping frame (total dropped: \(totalDropped))")
            }
            return
        }
        
        if currentQueueSize >= maxVideoQueueSize && !isKeyFrame {
            droppedFramesLock.lock()
            droppedFrames += 1
            droppedFramesLock.unlock()
            enqueuePermit.signal()
            return
        }
        
        videoSendQueue.async { [weak self] in
            defer {
                self?.enqueuePermit.signal()
            }
            guard let self = self, self.isStreaming else { return }
            
            // 只发当前推流路的帧，队列里旧路的帧直接丢，无需 0.25s 全局丢帧
            if encoderIndex != self.getActiveEncoderIndex() {
                return
            }
            
            // Quick check if refreshing - drop immediately
            self.stateLock.lock()
            let stillRefreshing = self.isRefreshing
            let currentWrapper = self.rtmpWrapper
            self.stateLock.unlock()
            
            if stillRefreshing || currentWrapper == nil {
                return
            }
            
            // Check if we're in protection period after reconnect
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            self.stateLock.lock()
            let protectionTime = self.reconnectSuccessTime
            self.stateLock.unlock()
            
            // 重连保护期仅用于忽略发送错误、不触发再次重连；不再丢弃非关键帧，否则会导致「视频卡住、音频继续」
            // （原逻辑：保护期内只发关键帧 → 8s 内几乎无视频 → 拉流卡帧）
            
            // Check queue size again (thread-safe)
            self.queueSizeLock.lock()
            var queueSize = self.videoQueueSize
            if queueSize >= self.maxVideoQueueSize {
                // Queue is full
                if isKeyFrame {
                    // Key frame: clear queue and send key frame
                    self.videoQueueSize = 0
                    queueSize = 0
                    
                    self.droppedFramesLock.lock()
                    self.droppedFrames += self.maxVideoQueueSize
                    let totalDropped = self.droppedFrames
                    self.droppedFramesLock.unlock()
                    
                    print("[\(self.tag)] Queue full, cleared to insert key frame (dropped \(self.maxVideoQueueSize) frames, total: \(totalDropped))")
                } else {
                    // Non-key frame: drop it
                    // CRITICAL: Never drop key frames - they are essential for decoder recovery
                    self.queueSizeLock.unlock()
                    self.droppedFramesLock.lock()
                    self.droppedFrames += 1
                    let totalDropped = self.droppedFrames
                    self.droppedFramesLock.unlock()
                    
                    if totalDropped % 30 == 0 {
                        print("[\(self.tag)] Queue full, dropping non-key frame (total dropped: \(totalDropped))")
                    }
                    // CRITICAL: Release data immediately to prevent memory buildup
                    return
                }
            }
            self.videoQueueSize += 1
            queueSize = self.videoQueueSize
            self.queueSizeLock.unlock()
            
            // Double-check wrapper is still valid before sending (might have changed during async)
            self.stateLock.lock()
            let finalWrapper = self.rtmpWrapper
            let isStillRefreshing = self.isRefreshing
            self.stateLock.unlock()
            
            if isStillRefreshing || finalWrapper == nil {
                // Connection was closed during async, drop frame
                self.queueSizeLock.lock()
                self.videoQueueSize -= 1
                self.queueSizeLock.unlock()
                return
            }
            
            // Send video data
            let ts = self.getStreamTimestamp()
            let result = finalWrapper!.sendVideo(data, timestamp: ts, isKeyFrame: isKeyFrame)
            
            self.queueSizeLock.lock()
            self.videoQueueSize -= 1
            queueSize = self.videoQueueSize
            self.queueSizeLock.unlock()
            
            // 切换分辨率后发出首帧关键帧即解除 pending，播放端可正常恢复
            if isKeyFrame {
                self.resolutionChangeLock.lock()
                self.resolutionChangePending = false
                self.resolutionChangeLock.unlock()
            }
            
            if result != 0 {
                // Check if we're in protection period or refreshing
                self.stateLock.lock()
                let refreshing = self.isRefreshing
                let protectionTime = self.reconnectSuccessTime
                self.stateLock.unlock()
                
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                let inProtectionPeriod = protectionTime > 0 && (now - protectionTime) < self.reconnectProtectionPeriod
                
                // Only handle error if not refreshing and not in protection period
                if !refreshing && !inProtectionPeriod {
                    self.handleSocketError(result)
                } else if inProtectionPeriod {
                    print("[\(self.tag)] Send error \(result) in protection period, ignoring...")
                }
            }
            
            // Log queue size warning if too high
            if queueSize > 10 {
                print("[\(self.tag)] Video send queue backlog: \(queueSize) frames, possible network issue")
            }
        }
    }
    
    fileprivate func sendAudioData(data: Data, info: AudioEncoder.BufferInfo) {
        guard isStreaming else { return }
        
        stateLock.lock()
        let wrapper = rtmpWrapper
        let refreshing = isRefreshing
        stateLock.unlock()
        
        if refreshing || wrapper == nil { return }
        
        // Check if still refreshing before sending
        stateLock.lock()
        let stillRefreshing = isRefreshing
        stateLock.unlock()
        
        if stillRefreshing { return }
        
        let ts = getStreamTimestamp()
        let result = wrapper!.sendAudio(data, timestamp: ts)
        
        // Check if we're in protection period or refreshing
        if result != 0 {
            stateLock.lock()
            let refreshing = isRefreshing
            let protectionTime = reconnectSuccessTime
            stateLock.unlock()
            
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let inProtectionPeriod = protectionTime > 0 && (now - protectionTime) < reconnectProtectionPeriod
            
            // Only handle error if not refreshing and not in protection period
            if !refreshing && !inProtectionPeriod {
                handleSocketError(result)
            } else if inProtectionPeriod {
                print("[\(tag)] Audio send error \(result) in protection period, ignoring...")
            }
        }
    }
    
    fileprivate func handleCodecConfig(sps: Data, pps: Data) {
        savedSps = sps
        savedPps = pps
        if isStreaming {
            _ = sendSpsPps(sps: sps, pps: pps)
        }
        // Clear resolution-change pending so next keyframe (and video) can be sent
        resolutionChangeLock.lock()
        resolutionChangePending = false
        resolutionChangeLock.unlock()
    }
    
    func getStats() -> NetworkStats? {
        stateLock.lock()
        let wrapper = rtmpWrapper
        stateLock.unlock()
        guard let stats = wrapper?.getStats() else { return nil }
        // 使用真实的网络统计（从 C++ wrapper 获取）
        let delayMs = stats["delayMs"] as? Int ?? 0
        let packetLossPercent = stats["packetLossPercent"] as? Int ?? 0
        
        // Get dropped frames count
        droppedFramesLock.lock()
        let dropped = droppedFrames
        droppedFramesLock.unlock()
        
        return NetworkStats(
            bytesSent: stats["bytesSent"] as? Int64 ?? 0,
            delayMs: delayMs,
            packetLossPercent: packetLossPercent,
            droppedFrames: dropped
        )
    }
    
    /**
     * Get dropped frames count (for bitrate controller)
     */
    func getDroppedFrames() -> Int {
        droppedFramesLock.lock()
        defer { droppedFramesLock.unlock() }
        return droppedFrames
    }
    
    /**
     * Reset dropped frames count (called periodically by bitrate controller)
     */
    func resetDroppedFrames() {
        droppedFramesLock.lock()
        droppedFrames = 0
        droppedFramesLock.unlock()
    }
    
    // Track send errors for bitrate adjustment
    private var sendErrorCount: Int = 0
    private var sendErrorLock = NSLock()
    private var lastErrorResetTime: Int64 = 0
    
    /**
     * Record send error (called from handleSocketError)
     */
    fileprivate func recordSendError() {
        sendErrorLock.lock()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // Reset error count every 5 seconds
        if now - lastErrorResetTime > 5000 {
            sendErrorCount = 0
            lastErrorResetTime = now
        }
        sendErrorCount += 1
        let count = sendErrorCount
        sendErrorLock.unlock()
    }
    
    /**
     * Get send error count in last 5 seconds
     */
    func getSendErrorCount() -> Int {
        sendErrorLock.lock()
        defer { sendErrorLock.unlock() }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // Reset if more than 5 seconds passed
        if now - lastErrorResetTime > 5000 {
            sendErrorCount = 0
            lastErrorResetTime = now
        }
        return sendErrorCount
    }
    
    func release() {
        stop()
        stateLock.lock()
        rtmpWrapper?.close()
        rtmpWrapper = nil
        stateLock.unlock()
    }
    
    func isStreamingActive() -> Bool { return isStreaming }
    
    // Weak network: avoid frequent reconnect (industry = tolerate errors, drop frames, reduce bitrate)
    private var consecutiveErrors: Int = 0
    private let maxConsecutiveErrors = 20   // Only reconnect after many consecutive errors
    private var lastErrorTime: Int64 = 0
    private let minReconnectInterval: Int64 = 5000
    private var reconnectCount: Int = 0
    private let maxReconnectAttempts = 5
    private var lastReconnectTime: Int64 = 0
    private let minReconnectDelay: Int64 = 8000  // 8s between reconnect attempts (reduce disconnect)
    private var reconnectSuccessTime: Int64 = 0
    private let reconnectProtectionPeriod: Int64 = 8000  // 8s protection after reconnect
    
    private func handleSocketError(_ error: Int32) {
        // Prevent concurrent error handling
        errorHandlingLock.lock()
        if isHandlingError {
            errorHandlingLock.unlock()
            return
        }
        isHandlingError = true
        errorHandlingLock.unlock()
        
        defer {
            errorHandlingLock.lock()
            isHandlingError = false
            errorHandlingLock.unlock()
        }
        
        stateLock.lock()
        if isRefreshing || !isStreaming {
            stateLock.unlock()
            return
        }
        stateLock.unlock()
        
        // Error 32 = EPIPE (Broken pipe), Error 9 = EBADF (Bad file descriptor)
        // Error -1 = General error (but might be temporary, need to check more carefully)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        
        // Check if we're in the protection period after reconnect
        stateLock.lock()
        let protectionTime = reconnectSuccessTime
        stateLock.unlock()
        
        if protectionTime > 0 && (now - protectionTime) < reconnectProtectionPeriod {
            // In protection period, ignore errors (connection might still be stabilizing)
            print("[\(tag)] Socket error \(error) in protection period after reconnect (\(now - protectionTime)ms), ignoring...")
            return
        }
        
        // For error -1, be more cautious - it might be temporary
        // Increase threshold significantly to avoid frequent reconnections in poor network
        if error == -1 {
            consecutiveErrors += 1
            // Only reconnect if we have MANY consecutive -1 errors (increased threshold to 10)
            // In poor network, -1 errors are common and shouldn't trigger immediate reconnection
            if consecutiveErrors < maxConsecutiveErrors {
                print("[\(tag)] Socket error -1 (temporary?), consecutive errors: \(consecutiveErrors), waiting...")
                return
            }
        }
        
        // For error 32 and 9, only reconnect after many consecutive errors (reduce disconnect)
        if error == 32 || error == 9 || (error == -1 && consecutiveErrors >= maxConsecutiveErrors) {
            // Check if enough time has passed since last reconnect attempt
            if now - lastReconnectTime < minReconnectDelay {
                consecutiveErrors += 1
                print("[\(tag)] Socket error \(error), but too soon since last reconnect, waiting... (consecutive: \(consecutiveErrors))")
                return
            }
            
            // Check if we've exceeded max reconnect attempts
            if reconnectCount >= maxReconnectAttempts {
                print("[\(tag)] Max reconnection attempts (\(maxReconnectAttempts)) reached, stopping stream")
                statusCallback?("failed", "网络连接失败，已达到最大重连次数")
                stop()
                return
            }
            
            // Before reconnecting, check queue size - if queue is very large, it might be causing issues
            queueSizeLock.lock()
            let queueSize = videoQueueSize
            queueSizeLock.unlock()
            
            if queueSize > 20 {
                print("[\(tag)] Queue size (\(queueSize)) is very large, clearing queue before reconnection")
                // Clear queue to prevent memory issues
                queueSizeLock.lock()
                videoQueueSize = 0
                queueSizeLock.unlock()
            }
            
            // Record error for bitrate adjustment
            recordSendError()
            
            // Reset consecutive errors for this reconnect attempt
            consecutiveErrors = 0
            lastErrorTime = now
            lastReconnectTime = now
            reconnectCount += 1
            
            print("[\(tag)] Socket error \(error) detected, triggering reconnection... (attempt \(reconnectCount)/\(maxReconnectAttempts), queue size: \(queueSize))")
            refreshConnection()
        } else {
            // Reset consecutive errors for other errors
            consecutiveErrors = 0
        }
    }
    
    func refreshConnection() {
        stateLock.lock()
        if isRefreshing || !isStreaming {
            stateLock.unlock()
            return
        }
        isRefreshing = true
        stateLock.unlock()
        
        print("[\(tag)] Refreshing RTMP connection... (attempt \(reconnectCount)/\(maxReconnectAttempts))")
        statusCallback?("reconnecting", nil)
        
        // CRITICAL: Clear all queues BEFORE closing connection to prevent memory buildup
        // This must be done synchronously to prevent new data from being queued
        queueSizeLock.lock()
        let droppedCount = videoQueueSize
        videoQueueSize = 0
        queueSizeLock.unlock()
        
        if droppedCount > 0 {
            print("[\(tag)] Cleared \(droppedCount) frames from queue before reconnection")
        }
        
        DispatchQueue.global().async { [weak self] in
            guard let self = self else { return }
            
            // Close old connection
            self.stateLock.lock()
            let old = self.rtmpWrapper
            self.rtmpWrapper = nil
            self.stateLock.unlock()
            old?.close()
            
            // Wait for server cleanup (longer wait for better stability)
            Thread.sleep(forTimeInterval: 2.0)
            
            // Re-initialize connection
            let nw = RtmpWrapper()
            if nw.initialize(self.rtmpUrl) == 0 {
                // Wait a bit more to ensure connection is stable
                Thread.sleep(forTimeInterval: 0.5)
                self.stateLock.lock()
                self.rtmpWrapper = nw
                self.startTime = Int64(Date().timeIntervalSince1970 * 1000)
                self.isRefreshing = false
                self.stateLock.unlock()
                
                // Reset error tracking on successful reconnect
                self.consecutiveErrors = 0
                self.lastErrorTime = 0
                self.reconnectCount = 0 // Reset reconnect count on success
                self.reconnectSuccessTime = Int64(Date().timeIntervalSince1970 * 1000) // Record success time
                
                // IMPORTANT: Do NOT reset bitrate - keep current bitrate to avoid stutter
                // The bitrate controller will adjust based on network conditions
                // Preserve current bitrate from active encoder
                if let encoder = self.getActiveVideoEncoder() {
                    self.currentBitrate = encoder.getCurrentBitrate()
                    print("[\(self.tag)] Preserving bitrate during reconnect: \(self.currentBitrate)")
                }
                
                // Set metadata first - use preserved bitrate instead of metaVideoBitrate
                if self.metaWidth > 0 {
                    // Use current bitrate (preserved) instead of metaVideoBitrate to avoid reset
                    // This prevents bitrate from being reset to base value during reconnection
                    let preservedBitrate = self.currentBitrate
                    print("[\(self.tag)] Using preserved bitrate for metadata: \(preservedBitrate)")
                    _ = nw.setMetadata(withWidth: Int32(self.metaWidth), height: Int32(self.metaHeight), videoBitrate: Int32(preservedBitrate), fps: Int32(self.metaFps), audioSampleRate: Int32(self.metaAudioSampleRate), audioChannels: Int32(self.metaAudioChannels))
                }
                
                // Wait longer before sending data to ensure connection is fully ready
                Thread.sleep(forTimeInterval: 1.0)
                
                // Send SPS/PPS (this is critical, must succeed)
                if let s = self.savedSps, let p = self.savedPps {
                    let spsPpsResult = self.sendSpsPps(sps: s, pps: p)
                    if spsPpsResult != 0 {
                        print("[\(self.tag)] Failed to send SPS/PPS after reconnect: \(spsPpsResult), will retry")
                        // If SPS/PPS fails, wait a bit more and try again
                        Thread.sleep(forTimeInterval: 0.5)
                        _ = self.sendSpsPps(sps: s, pps: p)
                    }
                }
                
                // Clear send queue to avoid sending old data
                self.queueSizeLock.lock()
                let dropped = self.videoQueueSize
                self.videoQueueSize = 0
                self.queueSizeLock.unlock()
                
                if dropped > 0 {
                    print("[\(self.tag)] Cleared \(dropped) frames from queue during reconnection")
                }
                
                print("[\(self.tag)] RTMP connection refreshed successfully, protection period started")
                self.statusCallback?("connected", nil)
                
                // Request keyframe from active encoder after a delay (to ensure connection is stable)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    self.getActiveVideoEncoder()?.requestKeyFrame()
                }
            } else {
                self.stateLock.lock()
                self.isRefreshing = false
                self.stateLock.unlock()
                
                print("[\(self.tag)] Failed to refresh RTMP connection (attempt \(self.reconnectCount)/\(self.maxReconnectAttempts))")
                
                // Check if we should retry
                if self.reconnectCount < self.maxReconnectAttempts {
                    // Retry after delay (exponential backoff)
                    let delay = min(5.0, Double(self.reconnectCount) * 1.0)
                    DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
                        self?.refreshConnection()
                    }
                } else {
                    // Max attempts reached, stop streaming
                    print("[\(self.tag)] Max reconnection attempts reached, stopping stream")
                    self.statusCallback?("failed", "网络连接失败，无法重连")
                    self.stop()
                }
            }
        }
    }
    
    func startHeartbeat() {
        guard isStreaming, heartbeatTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: heartbeatQueue)
        timer.schedule(deadline: .now() + 1.0, repeating: 1.0)
        timer.setEventHandler { [weak self] in
            guard let self = self, let d = self.lastVideoData, let i = self.lastVideoInfo else { return }
            self.sendVideoData(data: d, info: i, isKeyFrame: false, encoderIndex: self.lastVideoEncoderIndex)
        }
        timer.resume()
        heartbeatTimer = timer
    }
    
    func stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
    }
}

struct NetworkStats {
    let bytesSent: Int64
    let delayMs: Int
    let packetLossPercent: Int
    let droppedFrames: Int
}

/// 多路编码回调：只有当前 active 的那路才转发到推流
private class MultiVideoEncoderCallbackImpl: VideoEncoder.EncoderCallback {
    weak var streamer: RtmpStreamer?
    let encoderIndex: Int
    init(streamer: RtmpStreamer, encoderIndex: Int) {
        self.streamer = streamer
        self.encoderIndex = encoderIndex
    }
    private func isActive() -> Bool {
        guard let s = streamer else { return false }
        return s.getActiveEncoderIndex() == encoderIndex
    }
    func onEncodedData(data: Data, info: VideoEncoder.BufferInfo, isKeyFrame: Bool) {
        guard isActive() else { return }
        streamer?.sendVideoData(data: data, info: info, isKeyFrame: isKeyFrame, encoderIndex: encoderIndex)
    }
    func onCodecConfig(sps: Data, pps: Data) {
        guard isActive() else { return }
        streamer?.handleCodecConfig(sps: sps, pps: pps)
    }
    func onError(error: String) { print("Video encoder error (index=\(encoderIndex)): \(error)") }
}

private class VideoEncoderCallbackImpl: VideoEncoder.EncoderCallback {
    weak var streamer: RtmpStreamer?
    init(streamer: RtmpStreamer) { self.streamer = streamer }
    func onEncodedData(data: Data, info: VideoEncoder.BufferInfo, isKeyFrame: Bool) { streamer?.sendVideoData(data: data, info: info, isKeyFrame: isKeyFrame) }
    func onCodecConfig(sps: Data, pps: Data) { streamer?.handleCodecConfig(sps: sps, pps: pps) }
    func onError(error: String) { print("Video encoder error: \(error)") }
}

private class AudioEncoderCallbackImpl: AudioEncoder.EncoderCallback {
    weak var streamer: RtmpStreamer?
    init(streamer: RtmpStreamer) { self.streamer = streamer }
    func onEncodedData(data: Data, info: AudioEncoder.BufferInfo) { streamer?.sendAudioData(data: data, info: info) }
    func onError(error: String) { print("Audio encoder error: \(error)") }
}
