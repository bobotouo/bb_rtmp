#ifndef RTMP_WRAPPER_H
#define RTMP_WRAPPER_H

#ifdef __cplusplus
extern "C" {
#endif

// RTMP 连接句柄
typedef long rtmp_handle_t;

// 统计信息结构
typedef struct {
    long bytes_sent;              // 已发送字节数
    long delay_ms;                // 延迟（毫秒）
    long packet_loss_percent;     // 丢包率（百分比）
} rtmp_stats;

/**
 * 初始化 RTMP 连接
 * @param url RTMP 推流地址
 * @return 连接句柄，失败返回 0
 */
rtmp_handle_t rtmp_init(const char *url);

/**
 * 设置元数据信息（用于 AMF0 onMetaData）
 * @param handle 连接句柄
 * @param width 视频宽度
 * @param height 视频高度
 * @param video_bitrate 视频码率（bps）
 * @param fps 帧率
 * @param audio_sample_rate 音频采样率
 * @param audio_channels 音频声道数
 * @return 成功返回 0，失败返回负数
 */
int rtmp_set_metadata(rtmp_handle_t handle, int width, int height, int video_bitrate, int fps, int audio_sample_rate, int audio_channels);

/**
 * 发送视频数据
 * @param handle 连接句柄
 * @param data 视频数据（H.264 NAL 单元）
 * @param size 数据大小
 * @param timestamp 时间戳（微秒）
 * @param isKeyFrame 是否为关键帧
 * @return 成功返回 0，失败返回负数
 */
int rtmp_send_video(rtmp_handle_t handle, unsigned char *data, int size, long timestamp, int isKeyFrame);

/**
 * 发送音频数据
 * @param handle 连接句柄
 * @param data 音频数据（AAC）
 * @param size 数据大小
 * @param timestamp 时间戳（微秒）
 * @return 成功返回 0，失败返回负数
 */
int rtmp_send_audio(rtmp_handle_t handle, unsigned char *data, int size, long timestamp);

/**
 * 获取网络统计信息
 * @param handle 连接句柄
 * @param stats 输出统计信息
 * @return 成功返回 0，失败返回负数
 */
int rtmp_get_stats(rtmp_handle_t handle, rtmp_stats *stats);

/**
 * 关闭 RTMP 连接
 * @param handle 连接句柄
 */
void rtmp_close(rtmp_handle_t handle);

#ifdef __cplusplus
}
#endif

#endif // RTMP_WRAPPER_H

