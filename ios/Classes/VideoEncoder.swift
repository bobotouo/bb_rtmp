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
    
    // Frame counters for logging
    private var outputFrameCount = 0
    private var emptyFrameCount = 0
    
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
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: (fps * 2) as CFNumber) // GOP 2 seconds
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        
        // Set bitrate limit
        let bitrateLimit = [Double(bitrate) * 1.5, 1.0] as CFArray
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
    
    /**
     * Encode a pixel buffer
     */
    func encodeFrame(pixelBuffer: CVPixelBuffer, presentationTimeUs: Int64) {
        guard let session = compressionSession, isEncoding else {
            return
        }
        
        let presentationTime = CMTime(value: presentationTimeUs, timescale: 1_000_000)
        let duration = CMTime(value: Int64(1_000_000 / fps), timescale: 1_000_000)
        
        var flags: VTEncodeInfoFlags = []
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: presentationTime,
            duration: duration,
            frameProperties: nil,
            sourceFrameRefcon: nil,
            infoFlagsOut: &flags
        )
        
        if status == -12903 {
            print("[\(tag)] Encoder not available (background?), attempting to reset session...")
            resetSession()
        } else if status != noErr {
            print("[\(tag)] Failed to encode frame: \(status)")
        }
    }
    
    private func resetSession() {
        guard isEncoding else { return }
        print("[\(tag)] Resetting VTCompressionSession...")
        
        // Invalidate old session
        if let session = compressionSession {
            VTCompressionSessionInvalidate(session)
            compressionSession = nil
        }
        
        // Re-initialize with saved properties
        _ = initialize(width: Int(width), height: Int(height), bitrate: bitrate, fps: fps)
    }
    
    /**
     * Update bitrate
     */
    func updateBitrate(_ newBitrate: Int) {
        guard let session = compressionSession else { return }
        
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: newBitrate as CFNumber)
        
        let bitrateLimit = [Double(newBitrate) * 1.5, 1.0] as CFArray
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_DataRateLimits, value: bitrateLimit)
        
        self.bitrate = newBitrate
        print("[\(tag)] Bitrate updated to: \(newBitrate)")
    }
    
    /**
     * Request key frame
     */
    func requestKeyFrame() {
        guard let session = compressionSession else { return }
        
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        print("[\(tag)] Key frame requested")
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
        
        print("[\(tag)] Video encoder released")
    }
    
    /**
     * Get current bitrate
     */
    func getCurrentBitrate() -> Int {
        return bitrate
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
        let presentationTimeUs = Int64(CMTimeGetSeconds(presentationTime) * 1_000_000)
        
        let bufferInfo = BufferInfo(
            size: annexBData.count,
            presentationTimeUs: presentationTimeUs,
            flags: isKeyFrame ? 1 : 0
        )
        
        outputFrameCount += 1
        if isKeyFrame || outputFrameCount % 30 == 0 {
            print("[\(tag)] Encoded frame: size=\(annexBData.count), pts=\(presentationTimeUs), isKeyFrame=\(isKeyFrame) (total=\(outputFrameCount))")
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
