#include "rtmp_wrapper.h"
#include <rtmp.h>
#include <log.h>
#include <string.h>
#include <stdlib.h>
#include <vector>
#include <map>
#include <mutex>
#include <stdio.h>
#include <signal.h>

#define TAG "RtmpWrapper"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGD(...) printf("[%s] ", TAG); printf(__VA_ARGS__); printf("\n")
#define LOGE(...) printf("[%s ERROR] ", TAG); printf(__VA_ARGS__); printf("\n")
#endif

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
    if (conn.sent_metadata || conn.width == 0 || conn.height == 0) return false;

    char body[1024];
    char *p = body;
    char *pend = body + sizeof(body);

    AVal method;
    method.av_val = (char *)"@setDataFrame";
    method.av_len = strlen(method.av_val);
    p = AMF_EncodeString(p, pend, &method);
    
    AVal on_metadata;
    on_metadata.av_val = (char *)"onMetaData";
    on_metadata.av_len = strlen(on_metadata.av_val);
    p = AMF_EncodeString(p, pend, &on_metadata);

    if (p + 5 >= pend) return false;
    *p++ = AMF_ECMA_ARRAY;
    uint32_t count = 12;
    *p++ = (count >> 24) & 0xFF;
    *p++ = (count >> 16) & 0xFF;
    *p++ = (count >> 8) & 0xFF;
    *p++ = count & 0xFF;

    auto encode_number = [&](const char *name, double value) -> bool {
        int name_len = strlen(name);
        if (p + 2 + name_len + 9 >= pend) return false;
        *p++ = (name_len >> 8) & 0xFF;
        *p++ = name_len & 0xFF;
        memcpy(p, name, name_len);
        p += name_len;
        p = AMF_EncodeNumber(p, pend, value);
        return p != nullptr;
    };

    auto encode_boolean = [&](const char *name, bool value) -> bool {
        int name_len = strlen(name);
        if (p + 2 + name_len + 2 >= pend) return false;
        *p++ = (name_len >> 8) & 0xFF;
        *p++ = name_len & 0xFF;
        memcpy(p, name, name_len);
        p += name_len;
        p = AMF_EncodeBoolean(p, pend, value);
        return p != nullptr;
    };

    encode_number("width", (double)conn.width);
    encode_number("height", (double)conn.height);
    encode_number("videocodecid", 7.0);
    encode_number("videodatarate", (double)conn.video_bitrate / 1000.0);
    encode_number("framerate", (double)conn.fps);
    encode_number("audiocodecid", 10.0);
    encode_number("audiodatarate", 64.0);
    encode_number("audiosamplerate", (double)conn.sample_rate);
    encode_number("audiosamplesize", 16.0);
    encode_boolean("stereo", conn.channels > 1);
    encode_number("duration", 0.0);
    encode_number("filesize", 0.0);

    if (p + 3 >= pend) return false;
    *p++ = 0x00; *p++ = 0x00; *p++ = 0x09;

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
    if (ok) conn.sent_metadata = true;
    return ok;
}

static void write_be32(uint8_t *dst, uint32_t val) {
    dst[0] = (val >> 24) & 0xFF; dst[1] = (val >> 16) & 0xFF;
    dst[2] = (val >> 8) & 0xFF; dst[3] = val & 0xFF;
}

static void parse_sps_pps(const uint8_t *data, int size, std::vector<uint8_t> &sps, std::vector<uint8_t> &pps) {
    int i = 0;
    while (i + 4 < size) {
        int start = -1; int prefix = 0;
        for (; i + 3 < size; ++i) {
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                if (data[i+2] == 0x01) { prefix = 3; start = i; break; }
                if (i + 4 < size && data[i+2] == 0x00 && data[i+3] == 0x01) { prefix = 4; start = i; break; }
            }
        }
        if (start < 0) break;
        int nal_start = start + prefix;
        int next = size;
        for (int j = nal_start; j + 3 < size; ++j) {
            if (data[j] == 0x00 && data[j+1] == 0x00) {
                if (data[j+2] == 0x01 || (j + 4 < size && data[j+2] == 0x00 && data[j+3] == 0x01)) {
                    next = j; break;
                }
            }
        }
        int nal_size = next - nal_start;
        if (nal_size <= 0) { i = next; continue; }
        uint8_t nal_type = data[nal_start] & 0x1F;
        if (nal_type == 7) sps.assign(data + nal_start, data + nal_start + nal_size);
        else if (nal_type == 8) pps.assign(data + nal_start, data + nal_start + nal_size);
        i = next;
    }
}

static bool send_avc_sequence_header(Connection &conn, uint32_t timestamp_ms) {
    if (conn.sps.empty() || conn.pps.empty()) return false;
    const size_t body_size = 5 + 1 + 2 + conn.sps.size() + 1 + 2 + conn.pps.size();
    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, body_size + 5);
    RTMPPacket_Reset(&packet);
    uint8_t *body = reinterpret_cast<uint8_t *>(packet.m_body);
    size_t idx = 0;
    body[idx++] = 0x17; body[idx++] = 0x00;
    body[idx++] = 0x00; body[idx++] = 0x00; body[idx++] = 0x00;
    body[idx++] = 0x01; body[idx++] = conn.sps[1]; body[idx++] = conn.sps[2]; body[idx++] = conn.sps[3];
    body[idx++] = 0xFF; body[idx++] = 0xE1;
    body[idx++] = (conn.sps.size() >> 8) & 0xFF; body[idx++] = conn.sps.size() & 0xFF;
    memcpy(body + idx, conn.sps.data(), conn.sps.size()); idx += conn.sps.size();
    body[idx++] = 0x01;
    body[idx++] = (conn.pps.size() >> 8) & 0xFF; body[idx++] = conn.pps.size() & 0xFF;
    memcpy(body + idx, conn.pps.data(), conn.pps.size()); idx += conn.pps.size();
    packet.m_packetType = RTMP_PACKET_TYPE_VIDEO;
    packet.m_nBodySize = idx;
    packet.m_nTimeStamp = timestamp_ms;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet.m_hasAbsTimestamp = 1;
    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    if (ok) conn.sent_video_config = true;
    return ok;
}

static bool send_video_frame(Connection &conn, const uint8_t *data, int size, uint32_t timestamp_ms, bool is_key) {
    if (!conn.sent_video_config) return true;
    std::vector<uint8_t> body;
    body.reserve(size + 9);
    body.push_back(is_key ? 0x17 : 0x27);
    body.push_back(0x01);
    body.push_back(0x00); body.push_back(0x00); body.push_back(0x00);
    int i = 0;
    while (i + 4 <= size) {
        int start = -1; int prefix = 0;
        for (; i + 3 < size; ++i) {
            if (data[i] == 0x00 && data[i+1] == 0x00) {
                if (data[i+2] == 0x01) { start = i; prefix = 3; break; }
                if (i + 4 < size && data[i+2] == 0x00 && data[i+3] == 0x01) { start = i; prefix = 4; break; }
            }
        }
        if (start < 0) break;
        int nal_start = start + prefix;
        int next = size;
        for (int j = nal_start; j + 3 < size; ++j) {
            if (data[j] == 0x00 && data[j+1] == 0x00) {
                if (data[j+2] == 0x01 || (j + 4 < size && data[j+2] == 0x00 && data[j+3] == 0x01)) {
                    next = j; break;
                }
            }
        }
        int nal_size = next - nal_start;
        if (nal_size <= 0) { i = next; continue; }
        uint8_t nal_type = data[nal_start] & 0x1F;
        if (nal_type == 7 || nal_type == 8) { i = next; continue; }
        body.resize(body.size() + 4);
        write_be32(body.data() + body.size() - 4, (uint32_t)nal_size);
        body.insert(body.end(), data + nal_start, data + nal_start + nal_size);
        i = next;
    }
    if (body.size() <= 5) return true;
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
    return ok;
}

static int aac_sample_rate_index(int sample_rate) {
    switch (sample_rate) {
        case 96000: return 0; case 88200: return 1; case 64000: return 2; case 48000: return 3;
        case 44100: return 4; case 32000: return 5; case 24000: return 6; case 22050: return 7;
        case 16000: return 8; case 12000: return 9; case 11025: return 10; case 8000:  return 11;
        case 7350:  return 12; default:    return 4;
    }
}

static bool send_aac_sequence_header(Connection &conn) {
    int sample_index = aac_sample_rate_index(conn.sample_rate);
    uint8_t audio_header = (10 << 4) | (sample_index >= 6 ? 0x2 : 0x3) << 2;
    audio_header |= 0x2; audio_header |= (conn.channels == 1 ? 0x0 : 0x1);
    uint8_t asc[2];
    int profile = 2;
    asc[0] = (profile << 3) | ((sample_index & 0x0E) >> 1);
    asc[1] = ((sample_index & 0x01) << 7) | (conn.channels << 3);
    uint8_t body[4];
    body[0] = audio_header; body[1] = 0x00; body[2] = asc[0]; body[3] = asc[1];
    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, 4);
    RTMPPacket_Reset(&packet);
    memcpy(packet.m_body, body, 4);
    packet.m_nBodySize = 4;
    packet.m_packetType = RTMP_PACKET_TYPE_AUDIO;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet.m_nTimeStamp = 0;
    packet.m_hasAbsTimestamp = 1;
    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    if (ok) conn.sent_audio_config = true;
    return ok;
}

static bool send_aac_frame(Connection &conn, const uint8_t *data, int size, uint32_t timestamp_ms) {
    if (size <= 0) return false;
    int offset = 0;
    if (size > 7 && data[0] == 0xFF && (data[1] & 0xF0) == 0xF0) offset = 7;
    int sample_index = aac_sample_rate_index(conn.sample_rate);
    uint8_t audio_header = (10 << 4) | (sample_index >= 6 ? 0x2 : 0x3) << 2;
    audio_header |= 0x2; audio_header |= (conn.channels == 1 ? 0x0 : 0x1);
    RTMPPacket packet;
    RTMPPacket_Alloc(&packet, size - offset + 2);
    RTMPPacket_Reset(&packet);
    uint8_t *body = (uint8_t *)packet.m_body;
    body[0] = audio_header; body[1] = 0x01;
    memcpy(body + 2, data + offset, size - offset);
    packet.m_nBodySize = size - offset + 2;
    packet.m_packetType = RTMP_PACKET_TYPE_AUDIO;
    packet.m_nChannel = 0x04;
    packet.m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet.m_nTimeStamp = timestamp_ms;
    packet.m_hasAbsTimestamp = 1;
    bool ok = send_packet(conn, &packet);
    RTMPPacket_Free(&packet);
    return ok;
}

rtmp_handle_t rtmp_init(const char *url) {
    char *url_copy = strdup(url);
    if (!url_copy) return 0;
    std::lock_guard<std::mutex> lock(g_mutex);
    RTMP *rtmp = RTMP_Alloc();
    if (!rtmp) { free(url_copy); return 0; }
    RTMP_Init(rtmp);
    RTMP_SetBufferMS(rtmp, 10000); // 10 秒缓冲区
    rtmp->Link.timeout = 10;
    if (!RTMP_SetupURL(rtmp, url_copy)) { RTMP_Free(rtmp); free(url_copy); return 0; }
    RTMP_EnableWrite(rtmp);
    if (!RTMP_Connect(rtmp, nullptr)) { RTMP_Close(rtmp); RTMP_Free(rtmp); free(url_copy); return 0; }
    if (!RTMP_ConnectStream(rtmp, 0)) { RTMP_Close(rtmp); RTMP_Free(rtmp); free(url_copy); return 0; }
    long handle = g_next_handle++;
    Connection conn;
    conn.rtmp = rtmp; conn.connected = true; conn.url_copy = url_copy;
    g_connections[handle] = conn;
    return handle;
}

int rtmp_send_video(rtmp_handle_t handle, unsigned char *data, int size, long timestamp, int isKeyFrame) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) return -1;
    Connection &conn = it->second;
    if (data == nullptr || size <= 0) return 0;
    parse_sps_pps(data, size, conn.sps, conn.pps);
    if (!conn.sent_video_config && !conn.sps.empty() && !conn.pps.empty()) {
        /* 使用传入的 timestamp，高到低切换时与关键帧时间对齐，拉流端才能正确恢复 */
        if (!send_avc_sequence_header(conn, (uint32_t)timestamp)) return -1;
    }
    if (!conn.sent_metadata && conn.width > 0 && conn.height > 0 && conn.sent_video_config) {
        send_on_metadata(conn);
    }
    return send_video_frame(conn, data, size, (uint32_t)timestamp, isKeyFrame != 0) ? 0 : -1;
}

int rtmp_send_audio(rtmp_handle_t handle, unsigned char *data, int size, long timestamp) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) return -1;
    Connection &conn = it->second;
    if (data == nullptr || size <= 0) return 0;
    if (!conn.sent_audio_config) send_aac_sequence_header(conn);
    if (!conn.sent_metadata && conn.width > 0 && conn.sample_rate > 0) send_on_metadata(conn);
    return send_aac_frame(conn, data, size, (uint32_t)timestamp) ? 0 : -1;
}

int rtmp_set_metadata(rtmp_handle_t handle, int width, int height, int video_bitrate, int fps, int audio_sample_rate, int audio_channels) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) return -1;
    Connection &conn = it->second;
    int old_w = conn.width, old_h = conn.height;
    conn.width = width;
    conn.height = height;
    conn.video_bitrate = video_bitrate;
    conn.fps = fps;
    conn.sample_rate = audio_sample_rate;
    conn.channels = audio_channels;
    /* 分辨率变化时需再次发送 AVC 序列头 + onMetaData，否则 SRS 仍显示旧分辨率且无视频 */
    if (old_w != width || old_h != height) {
        conn.sent_metadata = false;
        conn.sent_video_config = false;  /* 下一帧带 SPS/PPS 时会重发 AVC sequence header */
    }
    return 0;
}

int rtmp_get_stats(rtmp_handle_t handle, rtmp_stats *stats) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it == g_connections.end() || !it->second.connected) return -1;
    stats->bytes_sent = it->second.bytes_sent; stats->delay_ms = 0; stats->packet_loss_percent = 0;
    return 0;
}

void rtmp_close(rtmp_handle_t handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_connections.find(handle);
    if (it != g_connections.end()) {
        free_connection(it->second);
        g_connections.erase(it);
    }
}
