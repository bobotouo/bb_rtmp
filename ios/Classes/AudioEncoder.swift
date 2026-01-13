import Foundation
import AVFoundation
import AudioToolbox

class AudioEncoder {
    private let tag = "AudioEncoder"
    private var audioConverter: AudioConverterRef?
    private var audioEngine: AVAudioEngine?
    private var sampleRate: Double = 44100
    private var channelCount: UInt32 = 1
    private let bitrate: UInt32 = 64000
    private var pcmBuffer = [Float]()
    private var isEncoding = false
    private var callback: EncoderCallback?
    
    protocol EncoderCallback {
        func onEncodedData(data: Data, info: BufferInfo)
        func onError(error: String)
    }
    
    struct BufferInfo {
        let size: Int
        let presentationTimeUs: Int64
        let flags: Int
    }
    
    func setCallback(_ callback: EncoderCallback) {
        self.callback = callback
    }
    
    /**
     * Initialize audio encoder
     */
    func initialize() -> Bool {
        // Setup audio engine for recording
        let engine = AVAudioEngine()
        self.audioEngine = engine
        
        let inputNode = engine.inputNode
        let nodeInputFormat = inputNode.outputFormat(forBus: 0)
        
        // Match hardware sample rate and keep mono for RTMP
        self.sampleRate = nodeInputFormat.sampleRate
        self.channelCount = 1
        
        // Input format for converter: Mono Float32 (Interleaved)
        // We will downmix hardware buffers into this format.
        var inputFormat = AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 4,
            mFramesPerPacket: 1,
            mBytesPerFrame: 4,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 32,
            mReserved: 0
        )
        
        // Output format (AAC)
        var outputFormat = AudioStreamBasicDescription(
            mSampleRate: sampleRate,
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: 1,
            mBitsPerChannel: 0,
            mReserved: 0
        )
        
        // Create audio converter
        var converter: AudioConverterRef?
        let status = AudioConverterNew(&inputFormat, &outputFormat, &converter)
        
        guard status == noErr, let converter = converter else {
            print("[\(tag)] Failed to create audio converter: \(status)")
            callback?.onError(error: "Failed to create audio converter")
            return false
        }
        
        self.audioConverter = converter
        
        // Set bitrate
        var bitrateValue = bitrate
        AudioConverterSetProperty(
            converter,
            kAudioConverterEncodeBitRate,
            UInt32(MemoryLayout<UInt32>.size),
            &bitrateValue
        )
        
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: nodeInputFormat) { [weak self] (buffer, time) in
            self?.encodeAudioBuffer(buffer: buffer, time: time)
        }
        
        do {
            try engine.start()
            isEncoding = true
            print("[\(tag)] Audio encoder initialized: sampleRate=\(sampleRate), bitrate=\(bitrate)")
            return true
        } catch {
            print("[\(tag)] Failed to start audio engine: \(error)")
            callback?.onError(error: "Failed to start audio engine")
            return false
        }
    }
    
    /**
     * Encode audio buffer
     */
    private func encodeAudioBuffer(buffer: AVAudioPCMBuffer, time: AVAudioTime) {
        guard let converter = audioConverter, isEncoding else { return }
        
        // 1. Downmix to Mono and accumulate into pcmBuffer
        let frameCount = buffer.frameLength
        if let floatData = buffer.floatChannelData {
            let channels = Int(buffer.format.channelCount)
            for i in 0..<Int(frameCount) {
                var sum: Float = 0
                for c in 0..<channels {
                    sum += floatData[c][i]
                }
                pcmBuffer.append(sum / Float(channels))
            }
        }
        
        // 2. Loop until we've encoded everything we can
        // AAC frame is 1024 samples
        while pcmBuffer.count >= 1024 {
            let outputBufferSize = 1024 * 2
            var outputBuffer = [UInt8](repeating: 0, count: outputBufferSize)
            var outputPacket = AudioStreamPacketDescription()
            var ioOutputDataPacketSize: UInt32 = 1
            
            var outputBufferList = AudioBufferList(
                mNumberBuffers: 1,
                mBuffers: AudioBuffer(
                    mNumberChannels: 1,
                    mDataByteSize: UInt32(outputBufferSize),
                    mData: &outputBuffer
                )
            )
            
            // Provide exactly 1024 samples for this packet
            var chunk = Array(pcmBuffer.prefix(1024))
            var inputData = (samples: chunk, consumed: false)
            
            let status = AudioConverterFillComplexBuffer(
                converter,
                audioConverterInputCallback,
                &inputData,
                &ioOutputDataPacketSize,
                &outputBufferList,
                &outputPacket
            )
            
            if status == noErr && ioOutputDataPacketSize > 0 {
                pcmBuffer.removeFirst(1024)
                let outputData = Data(bytes: outputBuffer, count: Int(outputPacket.mDataByteSize))
                
                // Calculate PTS based on sample count for accurate sync
                let presentationTimeUs = Int64(Double(time.sampleTime) / sampleRate * 1_000_000)
                let bufferInfo = BufferInfo(
                    size: outputData.count,
                    presentationTimeUs: presentationTimeUs,
                    flags: 0
                )
                
                callback?.onEncodedData(data: outputData, info: bufferInfo)
            } else {
                break
            }
        }
    }
    
    /**
     * Release encoder
     */
    func release() {
        isEncoding = false
        
        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine = nil
        
        if let converter = audioConverter {
            AudioConverterDispose(converter)
            audioConverter = nil
        }
        
        print("[\(tag)] Audio encoder released")
    }
    
    /**
     * Get sample rate
     */
    func getSampleRate() -> Int {
        return Int(sampleRate)
    }
    
    /**
     * Get channel count
     */
    func getChannelCount() -> Int {
        return Int(channelCount)
    }
}

// MARK: - Audio Converter Input Callback

private let audioConverterInputCallback: AudioConverterComplexInputDataProc = { (
    inAudioConverter: AudioConverterRef,
    ioNumberDataPackets: UnsafeMutablePointer<UInt32>,
    ioData: UnsafeMutablePointer<AudioBufferList>,
    outDataPacketDescription: UnsafeMutablePointer<UnsafeMutablePointer<AudioStreamPacketDescription>?>?,
    inUserData: UnsafeMutableRawPointer?
) -> OSStatus in
    guard let userData = inUserData else { return -1 }
    
    let context = userData.assumingMemoryBound(to: (samples: [Float], consumed: Bool).self)
    
    if context.pointee.consumed {
        ioNumberDataPackets.pointee = 0
        return -1
    }
    
    let sampleCount = UInt32(context.pointee.samples.count)
    ioNumberDataPackets.pointee = sampleCount
    
    ioData.pointee.mNumberBuffers = 1
    ioData.pointee.mBuffers.mNumberChannels = 1
    ioData.pointee.mBuffers.mDataByteSize = sampleCount * 4
    
    // Safety: AAC only asks for 1024 fragments, and we provided them in the chunk.
    context.pointee.samples.withUnsafeBufferPointer { ptr in
        ioData.pointee.mBuffers.mData = UnsafeMutableRawPointer(mutating: ptr.baseAddress)
    }
    
    context.pointee.consumed = true
    return noErr
}
