#include <jni.h>
#include <string>
#include <android/log.h>
#include "rtmp_wrapper.h"

#define TAG "RtmpJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bb_rtmp_RtmpNative_init(JNIEnv *env, jclass clazz, jstring url) {
    const char *urlStr = env->GetStringUTFChars(url, nullptr);
    if (urlStr == nullptr) {
        LOGE("获取 URL 字符串失败");
        return 0;
    }

    long handle = rtmp_init(urlStr);
    env->ReleaseStringUTFChars(url, urlStr);

    if (handle == 0) {
        LOGE("RTMP 初始化失败");
        return 0;
    }

    LOGD("RTMP 初始化成功，handle: %ld", handle);
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_bb_rtmp_RtmpNative_sendVideo(JNIEnv *env, jclass clazz, jlong handle,
                                       jbyteArray data, jint size, jlong timestamp,
                                       jboolean isKeyFrame) {
    if (data == nullptr || size <= 0) {
        LOGE("无效的视频数据");
        return -1;
    }

    jbyte *dataPtr = env->GetByteArrayElements(data, nullptr);
    if (dataPtr == nullptr) {
        LOGE("获取视频数据指针失败");
        return -1;
    }

    int result = rtmp_send_video(handle, (unsigned char *) dataPtr, size, timestamp, isKeyFrame);
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_bb_rtmp_RtmpNative_sendVideoBuffer(JNIEnv *env, jclass clazz, jlong handle,
                                             jlong buffer, jint offset, jint size,
                                             jlong timestamp, jboolean isKeyFrame) {
    if (buffer == 0 || size <= 0) {
        LOGE("无效的视频缓冲区");
        return -1;
    }

    unsigned char *dataPtr = (unsigned char *) buffer + offset;
    int result = rtmp_send_video(handle, dataPtr, size, timestamp, isKeyFrame);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_bb_rtmp_RtmpNative_sendAudio(JNIEnv *env, jclass clazz, jlong handle,
                                       jbyteArray data, jint size, jlong timestamp) {
    if (data == nullptr || size <= 0) {
        LOGE("无效的音频数据");
        return -1;
    }

    jbyte *dataPtr = env->GetByteArrayElements(data, nullptr);
    if (dataPtr == nullptr) {
        LOGE("获取音频数据指针失败");
        return -1;
    }

    int result = rtmp_send_audio(handle, (unsigned char *) dataPtr, size, timestamp);
    env->ReleaseByteArrayElements(data, dataPtr, JNI_ABORT);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_bb_rtmp_RtmpNative_sendAudioBuffer(JNIEnv *env, jclass clazz, jlong handle,
                                             jlong buffer, jint offset, jint size,
                                             jlong timestamp) {
    if (buffer == 0 || size <= 0) {
        LOGE("无效的音频缓冲区");
        return -1;
    }

    unsigned char *dataPtr = (unsigned char *) buffer + offset;
    int result = rtmp_send_audio(handle, dataPtr, size, timestamp);

    return result;
}

JNIEXPORT jint JNICALL
Java_com_bb_rtmp_RtmpNative_setMetadata(JNIEnv *env, jclass clazz, jlong handle,
                                         jint width, jint height, jint videoBitrate, jint fps,
                                         jint audioSampleRate, jint audioChannels) {
    int result = rtmp_set_metadata(handle, width, height, videoBitrate, fps, audioSampleRate, audioChannels);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_com_bb_rtmp_RtmpNative_getStats(JNIEnv *env, jclass clazz, jlong handle) {
    rtmp_stats stats;
    if (rtmp_get_stats(handle, &stats) != 0) {
        return nullptr;
    }

    jlongArray result = env->NewLongArray(3);
    if (result == nullptr) {
        return nullptr;
    }

    jlong values[3] = {stats.bytes_sent, stats.delay_ms, stats.packet_loss_percent};
    env->SetLongArrayRegion(result, 0, 3, values);

    return result;
}

JNIEXPORT void JNICALL
Java_com_bb_rtmp_RtmpNative_close(JNIEnv *env, jclass clazz, jlong handle) {
    rtmp_close(handle);
    LOGD("RTMP 连接已关闭，handle: %ld", handle);
}

} // extern "C"

