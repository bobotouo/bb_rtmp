import Foundation

class RtmpStreamer {
    private let tag = "RtmpStreamer"
    private var rtmpWrapper: RtmpWrapper?
    private var rtmpUrl: String = ""
    private var isStreaming = false
    private var startTime: Int64 = 0
    
    private var videoEncoder: VideoEncoder?
    private var audioEncoder: AudioEncoder?
    private var savedSps: Data?
    private var savedPps: Data?
    
    // Frame counters for logging
    private var videoFrameCount = 0
    
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
        
        startTime = Int64(Date().timeIntervalSince1970 * 1_000_000) // microseconds
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
        print("[\(tag)] Streaming stopped")
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
        
        let timestamp = (Int64(Date().timeIntervalSince1970 * 1_000_000) - startTime) / 1000 // milliseconds
        
        videoFrameCount += 1
        if isKeyFrame || videoFrameCount % 30 == 0 {
            print("[\(tag)] Sending video data: size=\(data.count), pts=\(info.presentationTimeUs), isKeyFrame=\(isKeyFrame) (total=\(videoFrameCount))")
        }
        
        let result = wrapper.sendVideo(data, timestamp: Int(timestamp), isKeyFrame: isKeyFrame)
        
        if result != 0 {
            print("[\(tag)] Failed to send video data: \(result)")
        }
    }
    
    /**
     * Send audio data
     */
    fileprivate func sendAudioData(data: Data, info: AudioEncoder.BufferInfo) {
        guard let wrapper = rtmpWrapper, isStreaming else { return }
        
        let timestamp = (Int64(Date().timeIntervalSince1970 * 1_000_000) - startTime) / 1000 // milliseconds
        
        let result = wrapper.sendAudio(data, timestamp: Int(timestamp))
        
        if result != 0 {
            print("[\(tag)] Failed to send audio data: \(result)")
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
