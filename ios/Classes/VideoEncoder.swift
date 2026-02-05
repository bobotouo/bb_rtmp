import Foundation
import VideoToolbox
import CoreMedia

class VideoEncoder {
    private let tag = "VideoEncoder"
    private var compressionSession: VTCompressionSession?
    private var width: Int32 = 0
    private var height: Int32 = 0
    private var bitrate: Int = 0
    private var fps: Int = 30
    private var isEncoding = false
    private var callback: EncoderCallback?
    
    // Save SPS/PPS to notify callback when it's set later
    private var savedSps: Data?
    private var savedPps: Data?
    private let spsPpsLock = NSLock()
    
    // Force next encoded frame to be keyframe (for resolution switch → fast recovery on playback)
    private var forceNextKeyFrame = false
    private let forceKeyFrameLock = NSLock()

    // Frame counters for logging
    private var outputFrameCount = 0
    private var emptyFrameCount = 0
    
    // FPS statistics
    private var fpsStats = FpsStats()
    
    /**
     * FPS statistics class
     */
    private class FpsStats {
        private var frameTimestamps: [Int64] = []
        private let maxSamples = 60 // Keep timestamps of last 60 frames
        private let lock = NSLock()
        
        func recordFrame() {
            lock.lock()
            defer { lock.unlock() }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            frameTimestamps.append(now)
            // Only keep frames within last 1 second
            let oneSecondAgo = now - 1000
            frameTimestamps.removeAll { $0 < oneSecondAgo }
        }
        
        func getFps() -> Float {
            lock.lock()
            defer { lock.unlock() }
            if frameTimestamps.count < 2 {
                return 0.0
            }
            let oldest = frameTimestamps.first!
            let newest = frameTimestamps.last!
            let duration = Float(newest - oldest) / 1000.0 // seconds
            if duration <= 0 {
                return 0.0
            }
            return Float(frameTimestamps.count - 1) / duration
        }
        
        func reset() {
            lock.lock()
            defer { lock.unlock() }
            frameTimestamps.removeAll()
        }
    }
    
    protocol EncoderCallback {
        func onEncodedData(data: Data, info: BufferInfo, isKeyFrame: Bool)
        func onCodecConfig(sps: Data, pps: Data)
        func onError(error: String)
    }
    
    struct BufferInfo {
        let size: Int
        let presentationTimeUs: Int64
        let flags: Int
    }
    
    func setCallback(_ callback: EncoderCallback) {
        self.callback = callback
        
        // If we already have saved SPS/PPS, notify immediately
        spsPpsLock.lock()
        if let sps = savedSps, let pps = savedPps {
            print("[\(tag)] Found saved SPS/PPS when setting callback, notifying immediately: SPS size=\(sps.count), PPS size=\(pps.count)")
            callback.onCodecConfig(sps: sps, pps: pps)
        }
        spsPpsLock.unlock()
    }
    
    /**
     * Initialize video encoder
     */
    func initialize(width: Int, height: Int, bitrate: Int, fps: Int) -> Bool {
        self.width = Int32(width)
        self.height = Int32(height)
        self.bitrate = bitrate
        self.fps = fps
        
        var status: OSStatus
        
        // Create compression session
        var session: VTCompressionSession?
        status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: self.width,
            height: self.height,
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: nil,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: compressionOutputCallback,
            refcon: Unmanaged.passUnretained(self).toOpaque(),
            compressionSessionOut: &session
        )
        
        guard status == noErr, let session = session else {
            print("[\(tag)] Failed to create compression session: \(status)")
            callback?.onError(error: "Failed to create compression session")
            return false
        }
        
        self.compressionSession = session
        
        // Set properties
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: fps as CFNumber)
        // 全档位缩短 GOP，关键帧更频繁，拉流端丢包/卡顿后更快恢复，减少「视频卡住、音频继续」
        let isLowRes = height <= 480
        let gopFrames: Int
        if isLowRes { gopFrames = fps / 2 }           // 480p: 0.5s GOP
        else { gopFrames = fps * 1 }                  // 720p/1080p: 1s GOP（原 2s 易导致卡帧）
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: gopFrames as CFNumber)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        
        // 480p 时收紧码率峰值 (1.2x)，减少瞬时尖峰导致拉流缓冲
        let peakMultiplier = isLowRes ? 1.2 : 1.5
        let bitrateLimit = [Double(bitrate) * peakMultiplier, 1.0] as CFArray
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: bitrateLimit)
        
        // Prepare to encode
        status = VTCompressionSessionPrepareToEncodeFrames(session)
        guard status == noErr else {
            print("[\(tag)] Failed to prepare compression session: \(status)")
            callback?.onError(error: "Failed to prepare compression session")
            return false
        }
        
        isEncoding = true
        print("[\(tag)] Video encoder initialized: \(width)x\(height), bitrate=\(bitrate), fps=\(fps)")
        return true
    }
    
    // Track frame count for warmup
    private var encodedFrameCount: Int = 0
    private let warmupFrameCount = 10 // Skip first 10 frames to warm up encoder
    
    /**
     * Encode a pixel buffer
     */
    func encodeFrame(pixelBuffer: CVPixelBuffer, presentationTimeUs: Int64) {
        guard let session = compressionSession, isEncoding else {
            return
        }
        
        // Skip first few frames to warm up encoder and avoid stutter at start
        encodedFrameCount += 1
        if encodedFrameCount <= warmupFrameCount {
            // During warmup, encode but don't count towards FPS stats
            // This allows encoder to stabilize before we start tracking performance
        }
        
        // Adjust presentation time to ensure continuity after reset
        let adjustedTimeUs: Int64
        if resetTimeUs > 0 {
            // After reset: use relative time from reset point (start from 0)
            let elapsedUs = Int64(Date().timeIntervalSince1970 * 1_000_000) - resetTimeUs
            adjustedTimeUs = max(elapsedUs, lastPresentationTimeUs + Int64(1_000_000 / fps))
        } else {
            // Normal case: use provided time, but ensure it's increasing
            adjustedTimeUs = max(presentationTimeUs, lastPresentationTimeUs + Int64(1_000_000 / fps))
        }
        
        lastPresentationTimeUs = adjustedTimeUs
        
        let presentationTime = CMTime(value: adjustedTimeUs, timescale: 1_000_000)
        let duration = CMTime(value: Int64(1_000_000 / fps), timescale: 1_000_000)
        
        // Log input buffer size for debugging (only occasionally to avoid spam)
        let inputWidth = CVPixelBufferGetWidth(pixelBuffer)
        let inputHeight = CVPixelBufferGetHeight(pixelBuffer)
        if encodedFrameCount % 30 == 0 {
            print("[\(tag)] Encoding frame: input=\(inputWidth)x\(inputHeight), encoder=\(width)x\(height)")
        }
        
        var frameProperties: CFDictionary?
        forceKeyFrameLock.lock()
        if forceNextKeyFrame {
            forceNextKeyFrame = false
            forceKeyFrameLock.unlock()
            frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue] as CFDictionary
        } else {
            forceKeyFrameLock.unlock()
        }

        var flags: VTEncodeInfoFlags = []
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: presentationTime,
            duration: duration,
            frameProperties: frameProperties,
            sourceFrameRefcon: nil,
            infoFlagsOut: &flags
        )
        
        if status == -12903 {
            print("[\(tag)] Encoder not available (background?), attempting to reset session...")
            resetSession()
            encodedFrameCount = 0 // Reset frame count after reset
        } else if status != noErr {
            print("[\(tag)] Failed to encode frame: \(status), input=\(inputWidth)x\(inputHeight), encoder=\(width)x\(height)")
        }
    }
    
    // Track last presentation time to ensure continuity after reset
    private var lastPresentationTimeUs: Int64 = 0
    private var resetTimeUs: Int64 = 0
    
    private func resetSession() {
        guard isEncoding else { return }
        print("[\(tag)] Resetting VTCompressionSession...")
        
        // Save reset time to adjust timestamps
        resetTimeUs = Int64(Date().timeIntervalSince1970 * 1_000_000)
        
        // Invalidate old session
        if let session = compressionSession {
            VTCompressionSessionInvalidate(session)
            compressionSession = nil
        }
        
        // Reset FPS stats and frame count
        fpsStats.reset()
        encodedFrameCount = 0 // Reset frame count for warmup
        
        // Re-initialize with saved properties
        _ = initialize(width: Int(width), height: Int(height), bitrate: bitrate, fps: fps)
        
        // Reset last presentation time - timestamps will restart from reset point
        lastPresentationTimeUs = 0
        print("[\(tag)] Encoder reset, timestamps will restart, warmup frames: \(warmupFrameCount)")
    }
    
    /**
     * Update bitrate
     */
    func updateBitrate(_ newBitrate: Int) {
        guard let session = compressionSession else { return }
        
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: newBitrate as CFNumber)
        
        let isLowRes = height <= 480
        let peakMultiplier = isLowRes ? 1.2 : 1.5
        let bitrateLimit = [Double(newBitrate) * peakMultiplier, 1.0] as CFArray
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: bitrateLimit)
        
        self.bitrate = newBitrate
        let gopFrames: Int = isLowRes ? (fps / 2) : (fps * 1)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: gopFrames as CFNumber)
        print("[\(tag)] Bitrate updated to: \(newBitrate), GOP: \(gopFrames) frames")
    }
    
    /**
     * Update FPS (for adaptive frame rate when bitrate is at minimum)
     */
    func updateFps(_ newFps: Int) {
        guard newFps > 0 && newFps <= 30 else { return }
        guard let session = compressionSession else { return }
        fps = newFps
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: fps as CFNumber)
        // Low bitrate: shorter GOP (1s) for faster recovery in weak network
        let gopFrames = height <= 480 ? (fps / 2) : (fps * 1)
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: gopFrames as CFNumber)
        print("[\(tag)] FPS updated to: \(fps), GOP: \(gopFrames) frames")
    }
    
    /**
     * Get current FPS setting
     */
    func getFps() -> Int {
        return fps
    }
    
    /**
     * Get current resolution
     */
    func getResolution() -> (width: Int, height: Int) {
        return (Int(width), Int(height))
    }
    
    /**
     * Get current SPS/PPS (for multi-encoder switch; caller uses for RTMP config)
     */
    func getSpsPps() -> (sps: Data?, pps: Data?) {
        spsPpsLock.lock()
        defer { spsPpsLock.unlock() }
        return (savedSps, savedPps)
    }
    
    /**
     * Update resolution (requires session reset, but smoother than full restart)
     */
    func updateResolution(width: Int, height: Int, bitrate: Int, fps: Int) -> Bool {
        guard isEncoding else { return false }
        
        print("[\(tag)] Updating encoder resolution: \(self.width)x\(self.height) -> \(width)x\(height)")
        
        // Save reset time
        resetTimeUs = Int64(Date().timeIntervalSince1970 * 1_000_000)
        
        // Invalidate old session
        if let session = compressionSession {
            VTCompressionSessionInvalidate(session)
            compressionSession = nil
        }
        
        // Reset FPS stats and frame count
        fpsStats.reset()
        encodedFrameCount = 0
        lastPresentationTimeUs = 0
        
        // Re-initialize with new resolution
        let success = initialize(width: width, height: height, bitrate: bitrate, fps: fps)
        
        if success {
            print("[\(tag)] Encoder resolution updated successfully: \(width)x\(height)")
        } else {
            print("[\(tag)] Failed to update encoder resolution")
        }
        
        return success
    }
    
    /**
     * Request key frame: flush pending frames and force next encoded frame to be I-frame.
     * 切换分辨率后拉流端需尽快收到关键帧才能恢复画面，仅 flush 不够，必须强制下一帧为 I 帧。
     */
    func requestKeyFrame() {
        guard let session = compressionSession else { return }
        forceKeyFrameLock.lock()
        forceNextKeyFrame = true
        forceKeyFrameLock.unlock()
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        print("[\(tag)] Key frame requested (next frame will be I-frame)")
    }
    
    /**
     * Release encoder
     */
    func release() {
        isEncoding = false
        
        if let session = compressionSession {
            VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
            VTCompressionSessionInvalidate(session)
            compressionSession = nil
        }
        
        spsPpsLock.lock()
        savedSps = nil
        savedPps = nil
        spsPpsLock.unlock()
        
        fpsStats.reset()
        
        print("[\(tag)] Video encoder released")
    }
    
    /**
     * Get current bitrate
     */
    func getCurrentBitrate() -> Int {
        return bitrate
    }
    
    /**
     * Get current encoding FPS
     */
    func getCurrentFps() -> Float {
        return fpsStats.getFps()
    }
    
    // MARK: - Compression Callback
    
    private let compressionOutputCallback: VTCompressionOutputCallback = { (
        outputCallbackRefCon: UnsafeMutableRawPointer?,
        sourceFrameRefCon: UnsafeMutableRawPointer?,
        status: OSStatus,
        infoFlags: VTEncodeInfoFlags,
        sampleBuffer: CMSampleBuffer?
    ) in
        guard status == noErr, let sampleBuffer = sampleBuffer else {
            print("[VideoEncoder] Compression callback error: \(status)")
            return
        }
        
        guard let encoder = outputCallbackRefCon else { return }
        let videoEncoder = Unmanaged<VideoEncoder>.fromOpaque(encoder).takeUnretainedValue()
        
        videoEncoder.handleEncodedFrame(sampleBuffer: sampleBuffer)
    }
    
    private func handleEncodedFrame(sampleBuffer: CMSampleBuffer) {
        guard let callback = callback else { return }
        
        // Check if this is a key frame
        var isKeyFrame = true
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]] {
            if let attachment = attachments.first {
                if let notSync = attachment[kCMSampleAttachmentKey_NotSync] as? Bool, notSync {
                    isKeyFrame = false
                }
            }
        }
        
        // Get format description (contains SPS/PPS)
        if let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer) {
            extractSPSPPS(from: formatDescription)
        }
        
        // Get data block buffer
        guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            print("[\(tag)] No data buffer in sample")
            return
        }
        
        var length: Int = 0
        var dataPointer: UnsafeMutablePointer<Int8>?
        let status = CMBlockBufferGetDataPointer(dataBuffer, atOffset: 0, lengthAtOffsetOut: nil, totalLengthOut: &length, dataPointerOut: &dataPointer)
        
        guard status == noErr, let dataPointer = dataPointer, length > 0 else {
            emptyFrameCount += 1
            if emptyFrameCount % 30 == 0 {
                print("[\(tag)] Empty frame (count: \(emptyFrameCount))")
            }
            return
        }
        
        // Convert AVCC to Annex-B format
        let annexBData = convertToAnnexB(data: dataPointer, length: length)
        
        // Get presentation time
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        var presentationTimeUs = Int64(CMTimeGetSeconds(presentationTime) * 1_000_000)
        
        // Adjust timestamp if reset happened to ensure continuity
        // After reset, use relative time from reset point (start from 0)
        if resetTimeUs > 0 {
            // Calculate elapsed time since reset
            let nowUs = Int64(Date().timeIntervalSince1970 * 1_000_000)
            let elapsedUs = nowUs - resetTimeUs
            
            // Use elapsed time, but ensure it's increasing
            presentationTimeUs = max(elapsedUs, lastPresentationTimeUs + Int64(1_000_000 / fps))
        } else {
            // Normal case: ensure timestamp is increasing
            presentationTimeUs = max(presentationTimeUs, lastPresentationTimeUs + Int64(1_000_000 / fps))
        }
        
        lastPresentationTimeUs = presentationTimeUs
        
        let bufferInfo = BufferInfo(
            size: annexBData.count,
            presentationTimeUs: presentationTimeUs,
            flags: isKeyFrame ? 1 : 0
        )
        
        // Record frame for FPS statistics (skip warmup frames)
        if encodedFrameCount > warmupFrameCount {
            fpsStats.recordFrame()
        }
        
        outputFrameCount += 1
        if isKeyFrame || outputFrameCount % 30 == 0 {
            let warmupInfo = encodedFrameCount <= warmupFrameCount ? " (warmup)" : ""
            print("[\(tag)] Encoded frame: size=\(annexBData.count), pts=\(presentationTimeUs), isKeyFrame=\(isKeyFrame) (total=\(outputFrameCount)\(warmupInfo))")
        }
        
        callback.onEncodedData(data: annexBData, info: bufferInfo, isKeyFrame: isKeyFrame)
    }
    
    private func extractSPSPPS(from formatDescription: CMFormatDescription) {
        var spsSize: Int = 0
        var spsCount: Int = 0
        var ppsSize: Int = 0
        var ppsCount: Int = 0
        var spsPointer: UnsafePointer<UInt8>?
        var ppsPointer: UnsafePointer<UInt8>?
        
        // Get SPS
        var status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription,
            parameterSetIndex: 0,
            parameterSetPointerOut: &spsPointer,
            parameterSetSizeOut: &spsSize,
            parameterSetCountOut: &spsCount,
            nalUnitHeaderLengthOut: nil
        )
        
        guard status == noErr, let sps = spsPointer else { return }
        
        // Get PPS
        status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription,
            parameterSetIndex: 1,
            parameterSetPointerOut: &ppsPointer,
            parameterSetSizeOut: &ppsSize,
            parameterSetCountOut: &ppsCount,
            nalUnitHeaderLengthOut: nil
        )
        
        guard status == noErr, let pps = ppsPointer else { return }
        
        let spsData = Data(bytes: sps, count: spsSize)
        let ppsData = Data(bytes: pps, count: ppsSize)
        
        // Check if SPS/PPS has changed (avoid duplicate notifications)
        spsPpsLock.lock()
        let hasChanged = savedSps != spsData || savedPps != ppsData
        
        if hasChanged {
            savedSps = spsData
            savedPps = ppsData
            print("[\(tag)] Extracted SPS/PPS: SPS size=\(spsSize), PPS size=\(ppsSize)")
            
            // Notify callback only if changed
            if let callback = callback {
                spsPpsLock.unlock()
                callback.onCodecConfig(sps: spsData, pps: ppsData)
            } else {
                spsPpsLock.unlock()
            }
        } else {
            spsPpsLock.unlock()
        }
    }
    
    /**
     * Convert AVCC format to Annex-B format
     * AVCC: [length][NALU][length][NALU]...
     * Annex-B: [00 00 00 01][NALU][00 00 00 01][NALU]...
     */
    private func convertToAnnexB(data: UnsafeMutablePointer<Int8>, length: Int) -> Data {
        var annexBData = Data()
        var offset = 0
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        
        while offset < length {
            // Read NALU length (4 bytes, big endian)
            guard offset + 4 <= length else { break }
            
            // Convert Int8 to UInt8 safely using bitPattern to avoid negative value crash
            let naluLength = Int(
                (UInt32(UInt8(bitPattern: data[offset])) << 24) |
                (UInt32(UInt8(bitPattern: data[offset + 1])) << 16) |
                (UInt32(UInt8(bitPattern: data[offset + 2])) << 8) |
                UInt32(UInt8(bitPattern: data[offset + 3]))
            )
            
            offset += 4
            
            guard offset + naluLength <= length else { break }
            
            // Add start code
            annexBData.append(contentsOf: startCode)
            
            // Add NALU data
            let naluData = Data(bytes: data.advanced(by: offset), count: naluLength)
            annexBData.append(naluData)
            
            offset += naluLength
        }
        
        return annexBData
    }
}
