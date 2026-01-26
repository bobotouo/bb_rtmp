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
    
    private var videoEncoder: VideoEncoder?
    private var audioEncoder: AudioEncoder?
    private var savedSps: Data?
    private var savedPps: Data?
    
    // Cached metadata for reconnection
    private var metaWidth: Int = 0
    private var metaHeight: Int = 0
    private var metaVideoBitrate: Int = 0
    private var metaFps: Int = 0
    private var metaAudioSampleRate: Int = 0
    private var metaAudioChannels: Int = 0
    
    // Heartbeat
    private var lastVideoData: Data?
    private var lastVideoInfo: VideoEncoder.BufferInfo?
    private var heartbeatTimer: DispatchSourceTimer?
    private let heartbeatQueue = DispatchQueue(label: "com.bb_rtmp.heartbeat")

    func initialize(url: String, videoEncoder: VideoEncoder, audioEncoder: AudioEncoder?) -> Bool {
        self.rtmpUrl = url
        self.videoEncoder = videoEncoder
        self.audioEncoder = audioEncoder
        
        let wrapper = RtmpWrapper()
        let result = wrapper.initialize(url)
        
        guard result == 0 else { return false }
        self.rtmpWrapper = wrapper
        
        videoEncoder.setCallback(VideoEncoderCallbackImpl(streamer: self))
        audioEncoder?.setCallback(AudioEncoderCallbackImpl(streamer: self))
        return true
    }
    
    func setMetadata(width: Int, height: Int, videoBitrate: Int, fps: Int, audioSampleRate: Int, audioChannels: Int) {
        self.metaWidth = width
        self.metaHeight = height
        self.metaVideoBitrate = videoBitrate
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
        
        if let sps = savedSps, let pps = savedPps {
            sendSpsPps(sps: sps, pps: pps)
        }
    }
    
    func stop() {
        isStreaming = false
        stopHeartbeat()
    }
    
    private func getStreamTimestamp() -> Int {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return Int(now - startTime)
    }
    
    private func sendSpsPps(sps: Data, pps: Data) {
        stateLock.lock()
        let wrapper = rtmpWrapper
        stateLock.unlock()
        
        guard let wrapper = wrapper else { return }
        var data = Data()
        data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        data.append(sps)
        data.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        data.append(pps)
        _ = wrapper.sendVideo(data, timestamp: 0, isKeyFrame: true)
    }
    
    fileprivate func sendVideoData(data: Data, info: VideoEncoder.BufferInfo, isKeyFrame: Bool) {
        guard isStreaming else { return }
        lastVideoData = data
        lastVideoInfo = info
        
        stateLock.lock()
        let wrapper = rtmpWrapper
        let refreshing = isRefreshing
        stateLock.unlock()
        
        if refreshing || wrapper == nil { return }
        
        let ts = getStreamTimestamp()
        let result = wrapper!.sendVideo(data, timestamp: ts, isKeyFrame: isKeyFrame)
        if result != 0 { handleSocketError(result) }
    }
    
    fileprivate func sendAudioData(data: Data, info: AudioEncoder.BufferInfo) {
        guard isStreaming else { return }
        
        stateLock.lock()
        let wrapper = rtmpWrapper
        let refreshing = isRefreshing
        stateLock.unlock()
        
        if refreshing || wrapper == nil { return }
        
        let ts = getStreamTimestamp()
        let result = wrapper!.sendAudio(data, timestamp: ts)
        if result != 0 { handleSocketError(result) }
    }
    
    fileprivate func handleCodecConfig(sps: Data, pps: Data) {
        savedSps = sps
        savedPps = pps
        if isStreaming { sendSpsPps(sps: sps, pps: pps) }
    }
    
    func getStats() -> NetworkStats? {
        stateLock.lock()
        let wrapper = rtmpWrapper
        stateLock.unlock()
        guard let stats = wrapper?.getStats() else { return nil }
        return NetworkStats(bytesSent: stats["bytesSent"] as? Int64 ?? 0, delayMs: 0, packetLossPercent: 0)
    }
    
    func release() {
        stop()
        stateLock.lock()
        rtmpWrapper?.close()
        rtmpWrapper = nil
        stateLock.unlock()
    }
    
    func isStreamingActive() -> Bool { return isStreaming }
    
    private func handleSocketError(_ error: Int32) {
        stateLock.lock()
        if isRefreshing || !isStreaming {
            stateLock.unlock()
            return
        }
        stateLock.unlock()
        
        if error == 32 || error == 9 || error == -1 {
            refreshConnection()
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
        
        statusCallback?("connecting", nil)
        
        DispatchQueue.global().async { [weak self] in
            guard let self = self else { return }
            
            self.stateLock.lock()
            let old = self.rtmpWrapper
            self.rtmpWrapper = nil
            self.stateLock.unlock()
            old?.close()
            
            Thread.sleep(forTimeInterval: 1.5)
            
            let nw = RtmpWrapper()
            if nw.initialize(self.rtmpUrl) == 0 {
                self.stateLock.lock()
                self.rtmpWrapper = nw
                self.startTime = Int64(Date().timeIntervalSince1970 * 1000)
                self.isRefreshing = false
                self.stateLock.unlock()
                
                if let s = self.savedSps, let p = self.savedPps { self.sendSpsPps(sps: s, pps: p) }
                if self.metaWidth > 0 {
                    _ = nw.setMetadata(withWidth: Int32(self.metaWidth), height: Int32(self.metaHeight), videoBitrate: Int32(self.metaVideoBitrate), fps: Int32(self.metaFps), audioSampleRate: Int32(self.metaAudioSampleRate), audioChannels: Int32(self.metaAudioChannels))
                }
                self.statusCallback?("connected", nil)
            } else {
                self.stateLock.lock()
                self.isRefreshing = false
                self.stateLock.unlock()
                // Simple retry
                DispatchQueue.global().asyncAfter(deadline: .now() + 2.0) { self.refreshConnection() }
            }
        }
    }
    
    func startHeartbeat() {
        guard isStreaming, heartbeatTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: heartbeatQueue)
        timer.schedule(deadline: .now() + 1.0, repeating: 1.0)
        timer.setEventHandler { [weak self] in
            guard let self = self, let d = self.lastVideoData, let i = self.lastVideoInfo else { return }
            self.sendVideoData(data: d, info: i, isKeyFrame: false)
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
