package com.bb.rtmp;

public class RtmpNative {
    static {
        System.loadLibrary("bb_rtmp");
    }

    /**
     * 初始化 RTMP 连接
     * @param url RTMP 推流地址
     * @return 返回连接句柄，失败返回 0
     */
    public static native long init(String url);

    /**
     * 设置元数据信息（用于 AMF0 onMetaData）
     * @param handle 连接句柄
     * @param width 视频宽度
     * @param height 视频高度
     * @param videoBitrate 视频码率（bps）
     * @param fps 帧率
     * @param audioSampleRate 音频采样率
     * @param audioChannels 音频声道数
     * @return 成功返回 0，失败返回负数
     */
    public static native int setMetadata(long handle, int width, int height, int videoBitrate, int fps, int audioSampleRate, int audioChannels);

    /**
     * 发送视频数据
     * @param handle 连接句柄
     * @param data 视频数据（H.264 NAL 单元）
     * @param size 数据大小
     * @param timestamp 时间戳（微秒）
     * @param isKeyFrame 是否为关键帧
     * @return 成功返回 0，失败返回负数
     */
    public static native int sendVideo(long handle, byte[] data, int size, long timestamp, boolean isKeyFrame);

    /**
     * 发送视频数据（使用 ByteBuffer，零拷贝）
     * @param handle 连接句柄
     * @param buffer 直接 ByteBuffer
     * @param offset 数据偏移
     * @param size 数据大小
     * @param timestamp 时间戳（微秒）
     * @param isKeyFrame 是否为关键帧
     * @return 成功返回 0，失败返回负数
     */
    public static native int sendVideoBuffer(long handle, long buffer, int offset, int size, long timestamp, boolean isKeyFrame);

    /**
     * 发送音频数据
     * @param handle 连接句柄
     * @param data 音频数据（AAC）
     * @param size 数据大小
     * @param timestamp 时间戳（微秒）
     * @return 成功返回 0，失败返回负数
     */
    public static native int sendAudio(long handle, byte[] data, int size, long timestamp);

    /**
     * 发送音频数据（使用 ByteBuffer，零拷贝）
     * @param handle 连接句柄
     * @param buffer 直接 ByteBuffer
     * @param offset 数据偏移
     * @param size 数据大小
     * @param timestamp 时间戳（微秒）
     * @return 成功返回 0，失败返回负数
     */
    public static native int sendAudioBuffer(long handle, long buffer, int offset, int size, long timestamp);

    /**
     * 获取网络统计信息
     * @param handle 连接句柄
     * @return 统计信息数组 [发送字节数, 延迟(ms), 丢包率(%)]
     */
    public static native long[] getStats(long handle);

    /**
     * 关闭 RTMP 连接
     * @param handle 连接句柄
     */
    public static native void close(long handle);
}

