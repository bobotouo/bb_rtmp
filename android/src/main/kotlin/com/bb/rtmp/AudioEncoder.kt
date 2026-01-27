package com.bb.rtmp

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class AudioEncoder {
    private val TAG = "AudioEncoder"
    private var mediaCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private val sampleRate = 44100
    private val channelCount = 1 // 单声道
    private val bitrate = 64000 // 64kbps
    private val isEncoding = AtomicBoolean(false)
    private var encoderCallback: EncoderCallback? = null
    private var recordThread: Thread? = null
    private var audioLogCount = 0

    interface EncoderCallback {
        fun onEncodedData(data: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onError(error: String)
    }

    fun setCallback(callback: EncoderCallback) {
        this.encoderCallback = callback
    }

    /**
     * 初始化音频编码器
     */
    fun initialize(): Boolean {
        try {
            // 创建 AAC 编码器
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            mediaCodec = encoder

            // 初始化 AudioRecord
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                encoder.release()
                mediaCodec = null
                encoderCallback?.onError("AudioRecord 初始化失败")
                return false
            }

            this.audioRecord = audioRecord
            
            Log.d(TAG, "音频编码器初始化成功: sampleRate=$sampleRate, bitrate=$bitrate")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化音频编码器失败", e)
            encoderCallback?.onError("初始化音频编码器失败: ${e.message}")
            return false
        }
    }

    /**
     * 录音和编码循环
     */
    private fun recordAndEncode() {
        val codec = mediaCodec ?: return
        val recorder = audioRecord ?: return

        recorder.startRecording()

        val bufferInfo = MediaCodec.BufferInfo()
        val inputBuffers = arrayOfNulls<ByteBuffer>(0) // MediaCodec.getInputBuffers() 已废弃，使用新 API

        while (isEncoding.get()) {
            val codec = mediaCodec ?: break // 如果 codec 被释放，退出循环
            try {
                // 获取输入缓冲区
                val inputBufferId = codec.dequeueInputBuffer(10000)
                if (inputBufferId >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputBufferId)
                    if (inputBuffer != null) {
                        val readSize = recorder.read(inputBuffer, inputBuffer.remaining())
                        if (readSize > 0) {
                            audioLogCount++
                            if (audioLogCount % 100 == 0) {
                                Log.d(TAG, "Audio data captured: $readSize bytes (total intervals=$audioLogCount)")
                            }
                            codec.queueInputBuffer(
                                inputBufferId,
                                0,
                                readSize,
                                System.nanoTime() / 1000,
                                0
                            )
                        }
                    }
                }

                // 获取输出缓冲区
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // 没有输出数据
                    }
                    outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        Log.d(TAG, "音频输出格式改变: $newFormat")
                    }
                    outputBufferId >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // 零拷贝：直接传递 ByteBuffer 给回调
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            // 检查是否为配置统计信息包 (ASC)，RTMP 协议单独处理它，不应该作为数据包发送
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d(TAG, "跳过音频配置帧 (Codec Config), size=${bufferInfo.size}")
                            } else {
                                // 创建只读副本
                                val data = outputBuffer.slice()
                                // 安全调用回调（检查回调是否仍然有效）
                                val callback = encoderCallback
                                if (callback != null) {
                                    try {
                                        callback.onEncodedData(data, bufferInfo)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "编码数据回调异常（可能已释放）", e)
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputBufferId, false)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "录音编码循环异常", e)
                if (isEncoding.get()) {
                    encoderCallback?.onError("录音编码异常: ${e.message}")
                }
                break
            }
        }

        try {
            recorder.stop()
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
        }
        Log.d(TAG, "录音线程已退出")
    }

    /**
     * 开始录音和编码
     */
    fun start(): Boolean {
        if (isEncoding.get()) return true
        
        if (audioRecord == null || mediaCodec == null) {
            Log.e(TAG, "尚未初始化，无法开始录音")
            return false
        }

        isEncoding.set(true)
        recordThread = Thread {
            recordAndEncode()
        }
        recordThread?.start()
        Log.d(TAG, "音频录制已开始")
        return true
    }

    /**
     * 停止录音和编码
     */
    fun stop() {
        if (!isEncoding.get()) return
        
        isEncoding.set(false)
        try {
            recordThread?.join(1000)
            recordThread = null
        } catch (e: Exception) {
            Log.e(TAG, "等待录音线程结束失败", e)
        }
        Log.d(TAG, "音频录制已停止")
    }

    /**
     * 释放编码器
     */
    fun release() {
        // 1. 先清除回调，防止回调在释放后继续执行
        encoderCallback = null
        
        // 2. 停止编码
        isEncoding.set(false)
        
        // 3. 等待录音线程结束
        try {
            recordThread?.join(1000)
            recordThread = null
        } catch (e: Exception) {
            Log.e(TAG, "等待录音线程结束失败", e)
        }

        // 4. 释放 AudioRecord
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "释放 AudioRecord 失败", e)
        }

        // 5. 释放 MediaCodec
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            Log.d(TAG, "音频编码器已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放音频编码器失败", e)
        }
    }

    /**
     * 获取采样率
     */
    fun getSampleRate(): Int = sampleRate

    /**
     * 获取声道数
     */
    fun getChannelCount(): Int = channelCount
}

