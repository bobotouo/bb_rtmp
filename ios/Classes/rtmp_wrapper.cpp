#include "rtmp_wrapper.h"
#include "rtmp.h"
#include "amf.h"
#include <vector>
#include <map>
#include <mutex>
#include <cstring>
#include <cstdlib>
#include <arpa/inet.h>
#include <cstdio>

#define TAG "RtmpWrapper"
#define LOGD(...) printf("[%s] ", TAG); printf(__VA_ARGS__); printf("\n")
#define LOGE(...) printf("[%s ERROR] ", TAG); printf(__VA_ARGS__); printf("\n")

struct Connection {
    RTMP *rtmp = nullptr;
    bool connected = false;
    std::vector<uint8_t> sps;
    std::vector<uint8_t> pps;
    bool sent_video_config = false;
    bool sent_audio_config = false;
    bool sent_metadata = false;
    long bytes_sent = 0;
    int sample_rate = 44100;
    int channels = 1;
    int width = 0;
    int height = 0;
    int video_bitrate = 0;
    int fps = 30;
    char *url_copy = nullptr;
};

static std::map<long, Connection> g_connections;
static long g_next_handle = 1;
static std::mutex g_mutex;

static void free_connection(Connection &conn) {
    if (conn.rtmp) {
        RTMP_Close(conn.rtmp);
        RTMP_Free(conn.rtmp);
        conn.rtmp = nullptr;
    }
    if (conn.url_copy) {
        free(conn.url_copy);
        conn.url_copy = nullptr;
    }
    conn.connected = false;
}

static bool send_packet(Connection &conn, RTMPPacket *packet) {
    if (!conn.connected || conn.rtmp == nullptr) return false;
    int ret = RTMP_SendPacket(conn.rtmp, packet, 0);
    if (ret) {
        conn.bytes_sent += packet->m_nBodySize;
        return true;
    }
    return false;
}

static bool send_on_metadata(Connection &conn) {
    if (conn.sent_metadata || conn.width == 0 || conn.height == 0) {
        LOGD("跳过发送 onMetaData: sent_metadata=%d, width=%d, height=%d", 
             conn.sent_metadata, conn.width, conn.height);
        return false;
    }
    
    LOGD("准备发送 onMetaData: %dx%d (宽x高), 方向=%s", 
         conn.width, conn.height,
         conn.width < conn.height ? "竖屏" : (conn.width > conn.height ? "横屏" : "正方形"));

    char body[1024];
    char *p = body;
    char *pend = body + sizeof(body);

    // AMF0 encode: "@setDataFrame"
    AVal method;
    method.av_val = (char *)"@setDataFrame";
    method.av_len = strlen(method.av_val);
    p = AMF_EncodeString(p, pend, &method);
    if (p == nullptr) {
        LOGE("编码 @setDataFrame 失败");
        return false;
    }

    // AMF0 encode: "onMetaData"
    AVal on_metadata;
    on_metadata.av_val = (char *)"onMetaData";
    on_metadata.av_len = strlen(on_metadata.av_val);
    p = AMF_EncodeString(p, pend, &on_metadata);
    if (p == nullptr) {
        LOGE("编码 onMetaData 失败");
        return false;
    }

    // 手动编码 ECMA Array，避免使用 AMF_AddProp 导致的内存问题
    if (p + 5 >= pend) return false;
    *p++ = AMF_ECMA_ARRAY; // ECMA Array 类型
    
    // 写入属性数量（12 个）
    uint32_t count = 12;
    *p++ = (count >> 24) & 0xFF;
    *p++ = (count >> 16) & 0xFF;
    *p++ = (count >> 8) & 0xFF;
    *p++ = count & 0xFF;

    // 辅助函数：手动编码 AMF 数字属性
    auto encode_number = [&](const char *name, double value) -> bool {
        int name_len = strlen(name);
        if (p + 2 + name_len + 9 >= pend) return false;
        
        // 写入名称长度和名称
        *p++ = (name_len >> 8) & 0xFF;
        *p++ = name_len & 0xFF;
        memcpy(p, name, name_len);
        p += name_len;
        
        // 写入 AMF NUMBER 类型和值
        p = AMF_EncodeNumber(p, pend, value);
        return p != nullptr;
    };
    
    // 辅助函数：手动编码 AMF 布尔属性
    auto encode_boolean = [&](const char *name, bool value) -> bool {
        int name_len = strlen(name);
        if (p + 2 + name_len + 2 >= pend) return false;
        
        // 写入名称长度和名称
        *p++ = (name_len >> 8) & 0xFF;
        *p++ = name_len & 0xFF;
        memcpy(p, name, name_len);
        p += name_len;
        
        // 写入 AMF BOOLEAN 类型和值
        p = AMF_EncodeBoolean(p, pend, value);
        return p != nullptr;
    };

    // 编码所有属性
    if (!encode_number("width", (double)conn.width)) { LOGE("编码 width 失败"); return false; }
    if (!encode_number("height", (double)conn.height)) { LOGE("编码 height 失败"); return false; }
    if (!encode_number("videocodecid", 7.0)) { LOGE("编码 videocodecid 失败"); return false; }
    if (!encode_number("videodatarate", (double)conn.video_bitrate / 1000.0)) { LOGE("编码 videodatarate 失败"); return false; }
    if (!encode_number("framerate", (double)conn.fps)) { LOGE("编码 framerate 失败"); return false; }
    if (!encode_number("audiocodecid", 10.0)) { LOGE("编码 audiocodecid 失败"); return false; }
    if (!encode_number("audiodatarate", 64.0)) { LOGE("编码 audiodatarate 失败"); return false; }
    if (!encode_number("audiosamplerate", (double)conn.sample_rate)) { LOGE("编码 audiosamplerate 失败"); return false; }
    if (!encode_number("audiosamplesize", 16.0)) { LOGE("编码 audiosamplesize 失败"); return false; }
    if (!encode_boolean("stereo", conn.channels > 1)) { LOGE("编码 stereo 失败"); return false; }
    if (!encode_number("duration", 0.0)) { LOGE("编码 duration 失败"); return false; }
    if (!encode_number("filesize", 0.0)) { LOGE("编码 filesize 失败"); return false; }

    // 写入 ECMA Array 结束标记（3 字节：0x00 0x00 0x09）
    if (p + 3 >= pend) { LOGE("缓冲区不足"); return false; }
    *p++ = 0x00;
    *p++ = 0x00;
    *p++ = 0x09; // AMF_OBJECT_END

    int body_size = p - body;

    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, body_size);
    RTMPPacket_Reset(&packet);
    memcpy(packet.m_body, body, body_size);
    packet.m_nBodySize = body_size;
    packet.m_packetType = RTMP_PACKET_TYPE_INFO;
    packet.m_nChannel = 0x03;
    packet.m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet.m_nTimeStamp = 0;
    packet.m_hasAbsTimestamp = 1;

    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    if (ok) {
        conn.sent_metadata = true;
        LOGD("发送 onMetaData 成功: %dx%d, bitrate=%d, fps=%d", 
             conn.width, conn.height, conn.video_bitrate, conn.fps);
    }
    return ok;
}

static void write_be32(uint8_t *dst, uint32_t val) {
    dst[0] = (val >> 24) & 0xFF;
    dst[1] = (val >> 16) & 0xFF;
    dst[2] = (val >> 8) & 0xFF;
    dst[3] = val & 0xFF;
}

static void parse_sps_pps(const uint8_t *data, int size, std::vector<uint8_t> &sps, std::vector<uint8_t> &pps) {
    int i = 0;
    while (i + 4 < size) {
        // find start code
        int start = -1;
        int prefix = 0;
        for (; i + 3 < size; ++i) {
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                if (data[i+2] == 0x01) { prefix = 3; start = i; break; }
                if (i + 4 < size && data[i+2] == 0x00 && data[i+3] == 0x01) { prefix = 4; start = i; break; }
            }
        }
        if (start < 0) break;
        int nal_start = start + prefix;
        int j = nal_start;
        // find next start code
        int next = size;
        for (; j + 3 < size; ++j) {
            if (data[j] == 0x00 && data[j+1] == 0x00) {
                if (data[j+2] == 0x01 || (j + 4 < size && data[j+2] == 0x00 && data[j+3] == 0x01)) {
                    next = j;
                    break;
                }
            }
        }
        int nal_size = next - nal_start;
        if (nal_size <= 0) { i = next; continue; }
        uint8_t nal_type = data[nal_start] & 0x1F;
        if (nal_type == 7) {
            sps.assign(data + nal_start, data + nal_start + nal_size);
            LOGD("找到 SPS: size=%d", nal_size);
        } else if (nal_type == 8) {
            pps.assign(data + nal_start, data + nal_start + nal_size);
            LOGD("找到 PPS: size=%d", nal_size);
        }
        i = next;
    }
}

static bool send_avc_sequence_header(Connection &conn, uint32_t timestamp_ms) {
    if (conn.sps.empty() || conn.pps.empty()) {
        LOGE("无法发送 AVC sequence header: SPS size=%zu, PPS size=%zu", conn.sps.size(), conn.pps.size());
        return false;
    }

    LOGD("准备发送 AVC sequence header: SPS size=%zu, PPS size=%zu", conn.sps.size(), conn.pps.size());

    const size_t body_size = 5 + 1 + 2 + conn.sps.size() + 1 + 2 + conn.pps.size();
    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, body_size + 5);
    RTMPPacket_Reset(&packet);

    uint8_t *body = reinterpret_cast<uint8_t *>(packet.m_body);
    size_t idx = 0;
    body[idx++] = 0x17; // keyframe + AVC
    body[idx++] = 0x00; // AVC sequence header
    body[idx++] = 0x00; body[idx++] = 0x00; body[idx++] = 0x00; // composition time

    // AVCDecoderConfigurationRecord
    body[idx++] = 0x01; // version
    body[idx++] = conn.sps[1]; // profile
    body[idx++] = conn.sps[2]; // compat
    body[idx++] = conn.sps[3]; // level
    body[idx++] = 0xFF; // 4 bytes length

    body[idx++] = 0xE1; // 1 sps
    body[idx++] = (conn.sps.size() >> 8) & 0xFF;
    body[idx++] = conn.sps.size() & 0xFF;
    memcpy(body + idx, conn.sps.data(), conn.sps.size());
    idx += conn.sps.size();

    body[idx++] = 0x01; // 1 pps
    body[idx++] = (conn.pps.size() >> 8) & 0xFF;
    body[idx++] = conn.pps.size() & 0xFF;
    memcpy(body + idx, conn.pps.data(), conn.pps.size());
    idx += conn.pps.size();

    packet.m_packetType = RTMP_PACKET_TYPE_VIDEO;
    packet.m_nBodySize = idx;
    packet.m_nTimeStamp = timestamp_ms;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet.m_hasAbsTimestamp = 1;

    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    if (ok) {
        conn.sent_video_config = true;
        LOGD("AVC sequence header 发送成功，sent_video_config 已设置为 true");
    } else {
        LOGE("AVC sequence header 发送失败");
    }
    return ok;
}

static bool send_video_frame(Connection &conn, const uint8_t *data, int size, uint32_t timestamp_ms, bool is_key) {
    // 只有发送了 video config 后才能发送视频帧
    if (!conn.sent_video_config) {
        LOGD("跳过视频帧（未发送 video config）");
        return true; // 返回 true 避免报错
    }
    
    // 调试：打印前几个字节，用于验证数据格式
    static int debug_frame_count = 0;
    if (debug_frame_count++ % 30 == 0) {
        char hex_buf[64] = {0};
        int hex_len = size < 16 ? size : 16;
        for (int i = 0; i < hex_len; i++) {
            sprintf(hex_buf + i * 3, "%02x ", data[i]);
        }
        LOGD("视频帧数据 (前%d字节): %s, size=%d, isKey=%d", hex_len, hex_buf, size, is_key);
    }
    
    // Build body
    std::vector<uint8_t> body;
    body.reserve(size + 9);
    body.push_back(is_key ? 0x17 : 0x27); // frame type + codec
    body.push_back(0x01); // AVC NALU
    body.push_back(0x00);
    body.push_back(0x00);
    body.push_back(0x00); // composition time

    // convert annex-b to length-prefixed, skip sps/pps
    int i = 0;
    int nalu_count = 0;
    while (i + 4 <= size) {
        int start = -1;
        int prefix = 0;
        for (; i + 3 < size; ++i) {
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                if (data[i+2] == 0x01) { start = i; prefix = 3; break; }
                if (i + 4 < size && data[i+2] == 0x00 && data[i+3] == 0x01) { start = i; prefix = 4; break; }
            }
        }
        if (start < 0) break;
        int nal_start = start + prefix;
        int j = nal_start;
        int next = size;
        for (; j + 3 < size; ++j) {
            if (data[j] == 0x00 && data[j+1] == 0x00) {
                if (data[j+2] == 0x01 || (j + 4 < size && data[j+2] == 0x00 && data[j+3] == 0x01)) {
                    next = j;
                    break;
                }
            }
        }
        int nal_size = next - nal_start;
        if (nal_size <= 0) { i = next; continue; }
        uint8_t nal_type = data[nal_start] & 0x1F;
        if (nal_type == 7 || nal_type == 8) { 
            LOGD("跳过 SPS/PPS NALU (type=%d)", nal_type);
            i = next; 
            continue; 
        } // skip sps/pps

        body.resize(body.size() + 4);
        write_be32(body.data() + body.size() - 4, static_cast<uint32_t>(nal_size));
        body.insert(body.end(), data + nal_start, data + nal_start + nal_size);
        nalu_count++;
        i = next;
    }

    if (body.size() <= 5) {
        LOGD("视频帧无有效 NALU（可能只有 SPS/PPS）");
        return true; // 返回 true 避免报错
    }
    
    // 记录日志（每隔 30 帧记录一次，避免日志过多）
    static int frame_count = 0;
    if (frame_count++ % 30 == 0) {
        LOGD("发送视频帧: timestamp=%u, isKey=%d, nalu_count=%d, body_size=%zu", 
             timestamp_ms, is_key, nalu_count, body.size());
    }

    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, body.size());
    RTMPPacket_Reset(&packet);
    memcpy(packet.m_body, body.data(), body.size());
    packet.m_nBodySize = body.size();
    packet.m_packetType = RTMP_PACKET_TYPE_VIDEO;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet.m_nTimeStamp = timestamp_ms;
    packet.m_hasAbsTimestamp = 1;

    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    if (!ok) {
        LOGE("RTMP_SendPacket 失败");
    }
    return ok;
}

static int aac_sample_rate_index(int sample_rate) {
    switch (sample_rate) {
        case 96000: return 0;
        case 88200: return 1;
        case 64000: return 2;
        case 48000: return 3;
        case 44100: return 4;
        case 32000: return 5;
        case 24000: return 6;
        case 22050: return 7;
        case 16000: return 8;
        case 12000: return 9;
        case 11025: return 10;
        case 8000:  return 11;
        case 7350:  return 12;
        default:    return 4; // default 44.1k
    }
}

static bool send_aac_sequence_header(Connection &conn) {
    uint8_t audio_header = 0;
    int sample_index = aac_sample_rate_index(conn.sample_rate);
    // SoundFormat(4)=10(AAC), SoundRate(2), SoundSize(1)=1(16bit), SoundType(1)=mono/stereo
    audio_header = (10 << 4) | (sample_index >= 6 ? 0x2 : 0x3) << 2; // rate encoded below
    audio_header |= 0x2; // 16 bit
    audio_header |= (conn.channels == 1 ? 0x0 : 0x1);

    uint8_t asc[2];
    int profile = 2; // AAC LC
    asc[0] = (profile << 3) | ((sample_index & 0x0E) >> 1);
    asc[1] = ((sample_index & 0x01) << 7) | (conn.channels << 3);

    uint8_t body[4 + 2];
    size_t idx = 0;
    body[idx++] = audio_header;
    body[idx++] = 0x00; // AAC sequence header
    body[idx++] = asc[0];
    body[idx++] = asc[1];

    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, idx);
    RTMPPacket_Reset(&packet);
    memcpy(packet.m_body, body, idx);
    packet.m_nBodySize = idx;
    packet.m_packetType = RTMP_PACKET_TYPE_AUDIO;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_MEDIUM;
    packet.m_nTimeStamp = 0;
    packet.m_hasAbsTimestamp = 1;

    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    if (ok) conn.sent_audio_config = true;
    return ok;
}

static bool send_aac_frame(Connection &conn, const uint8_t *data, int size, uint32_t timestamp_ms) {
    if (size <= 0) return false;

    // skip ADTS header if present
    int offset = 0;
    if (size > 7 && data[0] == 0xFF && (data[1] & 0xF0) == 0xF0) {
        offset = 7;
    }

    uint8_t audio_header = 0;
    int sample_index = aac_sample_rate_index(conn.sample_rate);
    audio_header = (10 << 4) | (sample_index >= 6 ? 0x2 : 0x3) << 2;
    audio_header |= 0x2;
    audio_header |= (conn.channels == 1 ? 0x0 : 0x1);

    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, size - offset + 2);
    RTMPPacket_Reset(&packet);
    uint8_t *body = reinterpret_cast<uint8_t *>(packet.m_body);
    size_t idx = 0;
    body[idx++] = audio_header;
    body[idx++] = 0x01; // AAC raw
    memcpy(body + idx, data + offset, size - offset);
    idx += size - offset;

    packet.m_nBodySize = idx;
    packet.m_packetType = RTMP_PACKET_TYPE_AUDIO;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_MEDIUM;
    packet.m_nTimeStamp = timestamp_ms;
    packet.m_hasAbsTimestamp = 1;

    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    return ok;
}

rtmp_handle_t rtmp_init(const char *url) {
    LOGD("开始初始化 RTMP，URL: %s", url);

    // RTMP_SetupURL can modify the string, so we must provide a mutable copy.
    // Also, librtmp may point to this memory for hostname/playpath etc.
    char *url_copy = strdup(url);
    if (!url_copy) return 0;

    std::lock_guard<std::mutex> lock(g_mutex);
    RTMP *rtmp = RTMP_Alloc();
    if (!rtmp) {
        LOGE("RTMP_Alloc 失败");
        free(url_copy);
        return 0;
    }
    RTMP_Init(rtmp);
    
    // 设置缓冲区和超时
    RTMP_SetBufferMS(rtmp, 3600 * 1000);
    rtmp->Link.timeout = 10; // 10 秒超时
    
    LOGD("调用 RTMP_SetupURL");
    if (!RTMP_SetupURL(rtmp, url_copy)) {
        LOGE("RTMP_SetupURL 失败，URL 可能格式错误: %s", url);
        RTMP_Free(rtmp);
        free(url_copy);
        return 0;
    }
    
    // 打印解析后的连接信息（用于调试）
    LOGD("解析后的连接信息:");
    LOGD("  hostname: %.*s", rtmp->Link.hostname.av_len, rtmp->Link.hostname.av_val);
    LOGD("  app: %.*s", rtmp->Link.app.av_len, rtmp->Link.app.av_val);
    LOGD("  playpath: %.*s", rtmp->Link.playpath.av_len, rtmp->Link.playpath.av_val);
    LOGD("  tcUrl: %.*s", rtmp->Link.tcUrl.av_len, rtmp->Link.tcUrl.av_val);
    LOGD("  port: %d", rtmp->Link.port);
    
    LOGD("RTMP_SetupURL 成功，调用 RTMP_EnableWrite");
    RTMP_EnableWrite(rtmp);
    
    LOGD("尝试连接 RTMP 服务器...");
    if (!RTMP_Connect(rtmp, nullptr)) {
        LOGE("RTMP_Connect 失败，无法连接到服务器: %s", url);
        LOGE("  可能原因: 1) 服务器地址或端口错误 2) 网络不通 3) 服务器未启动");
        RTMP_Close(rtmp);
        RTMP_Free(rtmp);
        return 0;
    }
    
    LOGD("RTMP_Connect 成功，尝试连接流...");
    if (!RTMP_ConnectStream(rtmp, 0)) {
        LOGE("RTMP_ConnectStream 失败，无法连接到流: %s", url);
        LOGE("  可能原因: 1) 流路径不正确 2) 服务器拒绝推流 3) 需要认证");
        LOGE("  请检查: app=%.*s, playpath=%.*s", 
             rtmp->Link.app.av_len, rtmp->Link.app.av_val,
             rtmp->Link.playpath.av_len, rtmp->Link.playpath.av_val);
        RTMP_Close(rtmp);
        RTMP_Free(rtmp);
        return 0;
    }

    long handle = g_next_handle++;
    Connection conn;
    conn.rtmp = rtmp;
    conn.connected = true;
    conn.url_copy = url_copy;
    g_connections[handle] = conn;

    LOGD("RTMP 初始化成功 handle=%ld (AMF0 支持已启用)", handle);
    return handle;
}

int rtmp_send_video(rtmp_handle_t handle, unsigned char *data, int size, long timestamp, int isKeyFrame) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) {
        LOGE("无效的句柄: %ld", handle);
        return -1;
    }
    Connection &conn = it->second;
    if (data == nullptr || size <= 0) {
        LOGE("无效的视频数据: size=%d", size);
        return -1;
    }

    // 解析 SPS/PPS
    size_t old_sps_size = conn.sps.size();
    size_t old_pps_size = conn.pps.size();
    parse_sps_pps(data, size, conn.sps, conn.pps);
    
    // 如果找到了新的 SPS/PPS，记录日志
    if (conn.sps.size() != old_sps_size || conn.pps.size() != old_pps_size) {
        LOGD("找到 SPS/PPS: sps_size=%zu, pps_size=%zu", conn.sps.size(), conn.pps.size());
    }
    
    // 发送 AVC sequence header（包含 SPS/PPS）
    if (!conn.sent_video_config && !conn.sps.empty() && !conn.pps.empty()) {
        LOGD("发送 AVC sequence header");
        bool config_ok = send_avc_sequence_header(conn, 0);
        if (config_ok) {
            LOGD("AVC sequence header 发送成功");
        } else {
            LOGE("AVC sequence header 发送失败");
            return -1;
        }
    }

    // 发送 onMetaData（如果还没有发送且已有视频配置信息）
    if (!conn.sent_metadata && conn.width > 0 && conn.height > 0 && conn.sent_video_config) {
        LOGD("发送 onMetaData: %dx%d (宽x高)", conn.width, conn.height);
        send_on_metadata(conn);
    }

    // 发送视频帧
    bool ok = send_video_frame(conn, data, size, (uint32_t) timestamp, isKeyFrame != 0);
    if (!ok) {
        LOGE("发送视频帧失败: timestamp=%u, isKey=%d, size=%d", (uint32_t)timestamp, isKeyFrame, size);
    }
    return ok ? 0 : -1;
}

int rtmp_send_audio(rtmp_handle_t handle, unsigned char *data, int size, long timestamp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) {
        LOGE("无效的句柄: %ld", handle);
        return -1;
    }
    Connection &conn = it->second;
    if (data == nullptr || size <= 0) {
        LOGE("无效的音频数据");
        return -1;
    }

    if (!conn.sent_audio_config) {
        send_aac_sequence_header(conn);
    }
    
    // 发送 onMetaData（如果还没有发送且已有基本信息）
    // 在纯音频推流（如后台）时，这确保了 Metadata 能被发送
    if (!conn.sent_metadata && conn.width > 0 && conn.sample_rate > 0) {
        LOGD("音频推送触发发送 onMetaData");
        send_on_metadata(conn);
    }
    bool ok = send_aac_frame(conn, data, size, (uint32_t) timestamp);
    return ok ? 0 : -1;
}

int rtmp_set_metadata(rtmp_handle_t handle, int width, int height, int video_bitrate, int fps, int audio_sample_rate, int audio_channels) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) {
        LOGE("无效的句柄: %ld", handle);
        return -1;
    }
    Connection &conn = it->second;
    conn.width = width;
    conn.height = height;
    conn.video_bitrate = video_bitrate;
    conn.fps = fps;
    conn.sample_rate = audio_sample_rate;
    conn.channels = audio_channels;
    LOGD("设置元数据: %dx%d (宽x高), bitrate=%d, fps=%d, audio=%dHz/%dch", 
         width, height, video_bitrate, fps, audio_sample_rate, audio_channels);
    LOGD("元数据方向: %s (宽%s高)", 
         width < height ? "竖屏" : (width > height ? "横屏" : "正方形"),
         width < height ? "<" : (width > height ? ">" : "=="));
    
    // 如果元数据已完整且还未发送，尝试发送（需要先有视频配置）
    // 注意：onMetaData 通常在发送第一个视频帧时发送，这里只是设置参数
    return 0;
}

int rtmp_get_stats(rtmp_handle_t handle, rtmp_stats *stats) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (stats == nullptr) {
        LOGE("统计信息指针为空");
        return -1;
    }
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) {
        LOGE("无效的句柄: %ld", handle);
        return -1;
    }
    stats->bytes_sent = it->second.bytes_sent;
    stats->delay_ms = 0;
    stats->packet_loss_percent = 0;
    return 0;
}

void rtmp_close(rtmp_handle_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it != g_connections.end()) {
        free_connection(it->second);
        g_connections.erase(it);
        LOGD("关闭 RTMP 连接: handle=%ld", handle);
    }
}

