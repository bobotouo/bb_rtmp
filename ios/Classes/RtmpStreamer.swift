import Foundation

class RtmpStreamer {
    private let tag = "RtmpStreamer"
    private var rtmpWrapper: RtmpWrapper?
    private var rtmpUrl: String = ""
    private var isStreaming = false
    private var isRefreshing = false
    private var startTime: Int64 = 0
    
    private var videoEncoder: VideoEncoder?
    private var audioEncoder: AudioEncoder?
    private var savedSps: Data?
    private var savedPps: Data?
    
    // Heartbeat for background streaming
    private var lastVideoData: Data?
    private var lastVideoInfo: VideoEncoder.BufferInfo?
    private var heartbeatTimer: DispatchSourceTimer?
    private let heartbeatQueue = DispatchQueue(label: "com.bb_rtmp.heartbeat")
    
    // Frame counters for logging
    private var videoFrameCount = 0
    
    // Cached metadata for reconnection
    private var metaWidth: Int = 0
    private var metaHeight: Int = 0
    private var metaVideoBitrate: Int = 0
    private var metaFps: Int = 0
    private var metaAudioSampleRate: Int = 0
    private var metaAudioChannels: Int = 0
    
    /**
     * Initialize RTMP streamer
     */
    func initialize(url: String, videoEncoder: VideoEncoder, audioEncoder: AudioEncoder?) -> Bool {
        self.rtmpUrl = url
        self.videoEncoder = videoEncoder
        self.audioEncoder = audioEncoder
        
        let wrapper = RtmpWrapper()
        let result = wrapper.initialize(url)
        
        guard result == 0 else {
            print("[\(tag)] RTMP initialization failed")
            return false
        }
        
        self.rtmpWrapper = wrapper
        
        // Set encoder callbacks
        videoEncoder.setCallback(VideoEncoderCallbackImpl(streamer: self))
        audioEncoder?.setCallback(AudioEncoderCallbackImpl(streamer: self))
        
        print("[\(tag)] RTMP streamer initialized")
        return true
    }
    
    /**
     * Set metadata (for AMF0 onMetaData)
     */
    func setMetadata(width: Int, height: Int, videoBitrate: Int, fps: Int, audioSampleRate: Int, audioChannels: Int) {
        guard let wrapper = rtmpWrapper else {
            print("[\(tag)] RTMP not initialized, cannot set metadata")
            return
        }
        
        // Cache for reconnection
        self.metaWidth = width
        self.metaHeight = height
        self.metaVideoBitrate = videoBitrate
        self.metaFps = fps
        self.metaAudioSampleRate = audioSampleRate
        self.metaAudioChannels = audioChannels
        
        let result = wrapper.setMetadata(
            withWidth: Int32(width),
            height: Int32(height),
            videoBitrate: Int32(videoBitrate),
            fps: Int32(fps),
            audioSampleRate: Int32(audioSampleRate),
            audioChannels: Int32(audioChannels)
        )
        
        if result == 0 {
            let orientation = width < height ? "Portrait" : (width > height ? "Landscape" : "Square")
            print("[\(tag)] Metadata set: \(width)x\(height) (\(orientation)), bitrate=\(videoBitrate), fps=\(fps)")
        } else {
            print("[\(tag)] Failed to set metadata: \(result)")
        }
    }
    
    /**
     * Start streaming
     */
    func start() {
        guard !isStreaming else {
            print("[\(tag)] Already streaming")
            return
        }
        
        startTime = Int64(Date().timeIntervalSince1970 * 1000) // milliseconds
        isStreaming = true
        print("[\(tag)] Streaming started")
        
        // Send saved SPS/PPS if available
        if let sps = savedSps, let pps = savedPps {
            print("[\(tag)] Sending saved SPS/PPS")
            sendSpsPps(sps: sps, pps: pps)
        } else {
            print("[\(tag)] No SPS/PPS available, waiting for encoder")
        }
    }
    
    /**
     * Stop streaming
     */
    func stop() {
        guard isStreaming else { return }
        
        isStreaming = false
        stopHeartbeat()
        print("[\(tag)] Streaming stopped")
    }
    
    func startHeartbeat() {
        guard isStreaming, heartbeatTimer == nil else { return }
        print("[\(tag)] Starting background video heartbeat")
        
        let timer = DispatchSource.makeTimerSource(queue: heartbeatQueue)
        timer.schedule(deadline: .now() + 1.0, repeating: 1.0)
        timer.setEventHandler { [weak self] in
            self?.sendHeartbeatFrame()
        }
        timer.resume()
        heartbeatTimer = timer
    }
    
    func stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        print("[\(tag)] Stopped background video heartbeat")
    }
    
    private func sendHeartbeatFrame() {
        guard let data = lastVideoData, let info = lastVideoInfo else { return }
        // Send heartbeat as non-keyframe but with updated stream timestamp
        // This keeps the timeline moving without needing a full GOP refresh
        sendVideoData(data: data, info: info, isKeyFrame: false)
    }
    
    private func getStreamTimestamp() -> Int {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return Int(now - startTime)
    }
    
    /**
     * Send SPS/PPS to RTMP
     */
    private func sendSpsPps(sps: Data, pps: Data) {
        guard let wrapper = rtmpWrapper else { return }
        
        // Combine SPS/PPS in Annex-B format
        var spsPpsData = Data()
        spsPpsData.append(contentsOf: [0x00, 0x00, 0x00, 0x01]) // Start code
        spsPpsData.append(sps)
        spsPpsData.append(contentsOf: [0x00, 0x00, 0x00, 0x01]) // Start code
        spsPpsData.append(pps)
        
        let result = wrapper.sendVideo(spsPpsData, timestamp: 0, isKeyFrame: true)
        
        if result == 0 {
            print("[\(tag)] SPS/PPS sent successfully")
        } else {
            print("[\(tag)] Failed to send SPS/PPS: \(result)")
        }
    }
    
    /**
     * Send video data
     */
    fileprivate func sendVideoData(data: Data, info: VideoEncoder.BufferInfo, isKeyFrame: Bool) {
        guard let wrapper = rtmpWrapper, isStreaming else {
            videoFrameCount += 1
            if videoFrameCount % 30 == 0 {
                print("[\(tag)] Received encoded data but not streaming (count: \(videoFrameCount))")
            }
            return
        }
        
        // Cache for heartbeat
        lastVideoData = data
        lastVideoInfo = info
        
        let timestamp = getStreamTimestamp()
        
        videoFrameCount += 1
        if isKeyFrame || videoFrameCount % 30 == 0 {
            print("[\(tag)] Sending video data: size=\(data.count), pts=\(info.presentationTimeUs), isKeyFrame=\(isKeyFrame) (total=\(videoFrameCount))")
        }
        
        let result = wrapper.sendVideo(data, timestamp: Int(timestamp), isKeyFrame: isKeyFrame)
        
        if result != 0 {
            print("[\(tag)] Failed to send video data: \(result)")
            handleSocketError(result)
        }
    }
    
    /**
     * Send audio data
     */
    fileprivate func sendAudioData(data: Data, info: AudioEncoder.BufferInfo) {
        guard let wrapper = rtmpWrapper, isStreaming else { return }
        
        let timestamp = getStreamTimestamp()
        
        let result = wrapper.sendAudio(data, timestamp: timestamp)
        
        if result != 0 {
            print("[\(tag)] Failed to send audio data: \(result)")
            handleSocketError(result)
        }
    }
    
    /**
     * Handle codec config (SPS/PPS)
     */
    fileprivate func handleCodecConfig(sps: Data, pps: Data) {
        print("[\(tag)] Received SPS/PPS: SPS size=\(sps.count), PPS size=\(pps.count), isStreaming=\(isStreaming)")
        
        savedSps = sps
        savedPps = pps
        
        if isStreaming {
            sendSpsPps(sps: sps, pps: pps)
        }
    }
    
    /**
     * Get network stats
     */
    func getStats() -> NetworkStats? {
        guard let wrapper = rtmpWrapper else { return nil }
        guard let statsDict = wrapper.getStats() else { return nil }
        
        return NetworkStats(
            bytesSent: statsDict["bytesSent"] as? Int64 ?? 0,
            delayMs: statsDict["delayMs"] as? Int ?? 0,
            packetLossPercent: statsDict["packetLossPercent"] as? Int ?? 0
        )
    }
    
    /**
     * Release resources
     */
    func release() {
        stop()
        rtmpWrapper?.close()
        rtmpWrapper = nil
        videoEncoder = nil
        audioEncoder = nil
        print("[\(tag)] RTMP streamer released")
    }
    
    /**
     * Check if streaming
     */
    func isStreamingActive() -> Bool {
        return isStreaming
    }
    
    private func handleSocketError(_ error: Int32) {
        // Error 32: Broken pipe, Error 9: Bad file descriptor (socket reclaimed)
        if (error == 32 || error == 9) && !isRefreshing {
            print("[\(tag)] Critical socket error detected (\(error)). Stream is likely dead.")
            // Trigger a refresh if we are currently trying to stream
            if isStreaming {
                DispatchQueue.global().asyncAfter(deadline: .now() + 1.0) { [weak self] in
                    self?.refreshConnection()
                }
            }
        }
    }
    
    /**
     * Refresh the RTMP connection (e.g., after background transition socket loss)
     */
    func refreshConnection() {
        guard isStreaming, let wrapper = rtmpWrapper, !isRefreshing else { return }
        print("[\(tag)] Refreshing RTMP connection...")
        isRefreshing = true
        
        // Use a background queue for reconnection to avoid blocking
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            
            defer { self.isRefreshing = false }
            
            // 1. Close old connection
            wrapper.close()
            
            // 2. Wait a bit for server to clean up (e.g. SRS "disposing" state)
            Thread.sleep(forTimeInterval: 1.5)
            
            // 3. Re-initialize
            let result = wrapper.initialize(self.rtmpUrl)
            
            if result == 0 {
                print("[\(self.tag)] RTMP connection refreshed successfully")
                // Re-send SPS/PPS and set metadata
                if let sps = self.savedSps, let pps = self.savedPps {
                    self.sendSpsPps(sps: sps, pps: pps)
                }
                
                // Re-apply metadata
                if self.metaWidth > 0 {
                    _ = wrapper.setMetadata(
                        withWidth: Int32(self.metaWidth),
                        height: Int32(self.metaHeight),
                        videoBitrate: Int32(self.metaVideoBitrate),
                        fps: Int32(self.metaFps),
                        audioSampleRate: Int32(self.metaAudioSampleRate),
                        audioChannels: Int32(self.metaAudioChannels)
                    )
                }
            } else {
                print("[\(self.tag)] Failed to refresh RTMP connection: \(result)")
            }
        }
    }
}

// MARK: - Network Stats

struct NetworkStats {
    let bytesSent: Int64
    let delayMs: Int
    let packetLossPercent: Int
}

// MARK: - Encoder Callbacks

private class VideoEncoderCallbackImpl: VideoEncoder.EncoderCallback {
    weak var streamer: RtmpStreamer?
    
    init(streamer: RtmpStreamer) {
        self.streamer = streamer
    }
    
    func onEncodedData(data: Data, info: VideoEncoder.BufferInfo, isKeyFrame: Bool) {
        streamer?.sendVideoData(data: data, info: info, isKeyFrame: isKeyFrame)
    }
    
    func onCodecConfig(sps: Data, pps: Data) {
        streamer?.handleCodecConfig(sps: sps, pps: pps)
    }
    
    func onError(error: String) {
        print("[RtmpStreamer] Video encoder error: \(error)")
    }
}

private class AudioEncoderCallbackImpl: AudioEncoder.EncoderCallback {
    weak var streamer: RtmpStreamer?
    
    init(streamer: RtmpStreamer) {
        self.streamer = streamer
    }
    
    func onEncodedData(data: Data, info: AudioEncoder.BufferInfo) {
        streamer?.sendAudioData(data: data, info: info)
    }
    
    func onError(error: String) {
        print("[RtmpStreamer] Audio encoder error: \(error)")
    }
}
