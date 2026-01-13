import Foundation

class BitrateController {
    private let tag = "BitrateController"
    private var videoEncoder: VideoEncoder
    private var rtmpStreamer: RtmpStreamer
    
    private var isRunning = false
    private var currentBitrate = 0
    private var minBitrate = 500_000  // 500kbps
    private var maxBitrate = 5_000_000 // 5Mbps
    private var baseBitrate = 2_000_000 // 2Mbps
    
    private var monitorTimer: Timer?
    
    init(videoEncoder: VideoEncoder, rtmpStreamer: RtmpStreamer) {
        self.videoEncoder = videoEncoder
        self.rtmpStreamer = rtmpStreamer
    }
    
    /**
     * Initialize ABR controller
     */
    func initialize(initialBitrate: Int, width: Int, height: Int) {
        currentBitrate = initialBitrate
        baseBitrate = initialBitrate
        print("[\(tag)] ABR initialized: bitrate=\(initialBitrate), resolution=\(width)x\(height)")
    }
    
    /**
     * Start monitoring and adjusting
     */
    func start() {
        guard !isRunning else { return }
        
        isRunning = true
        
        // Monitor every 3 seconds
        monitorTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { [weak self] _ in
            self?.adjustBitrate()
        }
        
        print("[\(tag)] ABR started")
    }
    
    /**
     * Stop monitoring
     */
    func stop() {
        guard isRunning else { return }
        
        isRunning = false
        monitorTimer?.invalidate()
        monitorTimer = nil
        
        print("[\(tag)] ABR stopped")
    }
    
    /**
     * Adjust bitrate based on network stats
     */
    private func adjustBitrate() {
        guard let stats = rtmpStreamer.getStats() else { return }
        
        let delay = stats.delayMs
        let packetLoss = stats.packetLossPercent
        let fps = getCurrentFps() // Simplified, should get from encoder
        
        print("[\(tag)] Network stats: delay=\(delay)ms, loss=\(packetLoss)%, fps=\(fps)")
        
        var newBitrate = currentBitrate
        
        // Poor network: high delay or packet loss
        if delay > 500 || packetLoss > 5 {
            newBitrate = reduceBitrate(ratio: 0.2) // Reduce 20%
        }
        // Low FPS: may be due to high bitrate or device performance
        else if fps < 20 {
            newBitrate = reduceBitrate(ratio: 0.15) // Reduce 15%
        }
        // Good network and stable FPS: can increase bitrate
        else if delay < 100 && packetLoss < 1 && fps >= 28 && currentBitrate < baseBitrate {
            newBitrate = increaseBitrate(ratio: 0.1) // Increase 10%
        }
        
        if newBitrate != currentBitrate {
            updateBitrate(newBitrate)
        }
    }
    
    /**
     * Reduce bitrate
     */
    private func reduceBitrate(ratio: Float) -> Int {
        let newBitrate = max(Int(Float(currentBitrate) * (1 - ratio)), minBitrate)
        print("[\(tag)] Reducing bitrate: \(currentBitrate) -> \(newBitrate) (reduce \(Int(ratio * 100))%)")
        return newBitrate
    }
    
    /**
     * Increase bitrate
     */
    private func increaseBitrate(ratio: Float) -> Int {
        let newBitrate = min(Int(Float(currentBitrate) * (1 + ratio)), maxBitrate)
        print("[\(tag)] Increasing bitrate: \(currentBitrate) -> \(newBitrate) (increase \(Int(ratio * 100))%)")
        return newBitrate
    }
    
    /**
     * Update bitrate
     */
    private func updateBitrate(_ newBitrate: Int) {
        currentBitrate = newBitrate
        videoEncoder.updateBitrate(newBitrate)
        print("[\(tag)] Bitrate updated: \(newBitrate) bps")
    }
    
    /**
     * Get current FPS (simplified implementation)
     */
    private func getCurrentFps() -> Float {
        // TODO: Implement actual FPS tracking from encoder
        return 30.0
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
     * Release resources
     */
    func release() {
        stop()
    }
}
