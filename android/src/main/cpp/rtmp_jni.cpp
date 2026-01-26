#include <jni.h>
#include <string>
#include <android/log.h>
#include "rtmp_wrapper.h"
#include <android/api-level.h>
#include <android/hardware_buffer_jni.h>

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

// ----------------- NativeBridge: HardwareBuffer -> AHardwareBuffer* -----------------

JNIEXPORT jlong JNICALL
Java_com_bb_rtmp_NativeBridge_getAHardwareBufferPtr(JNIEnv *env, jclass clazz, jobject hwBufferObj) {
#if __ANDROID_API__ >= 26
    if (hwBufferObj == nullptr) {
        LOGE("HardwareBuffer 对象为空");
        return 0;
    }

    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, hwBufferObj);
    if (buffer == nullptr) {
        LOGE("从 HardwareBuffer 获取 AHardwareBuffer 失败");
        return 0;
    }

    // 增加引用计数，调用方用完后需调用 release 方法
    AHardwareBuffer_acquire(buffer);
    return reinterpret_cast<jlong>(buffer);
#else
    LOGE("AHardwareBuffer 仅支持 Android 26+");
    return 0;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_bb_rtmp_NativeBridge_lockAHardwareBuffer(JNIEnv *env, jclass clazz, jlong ptr) {
#if __ANDROID_API__ >= 26
    if (ptr == 0) {
        LOGE("AHardwareBuffer 指针为空");
        return 0;
    }
    
    AHardwareBuffer *buffer = reinterpret_cast<AHardwareBuffer *>(ptr);
    
    // 锁定 buffer 并获取虚拟地址（零拷贝）
    void *virtualAddress = nullptr;
    int result = AHardwareBuffer_lock(buffer, 
                                       AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                                       -1, // fence
                                       nullptr, // rect
                                       &virtualAddress);
    
    if (result != 0 || virtualAddress == nullptr) {
        LOGE("锁定 AHardwareBuffer 失败，错误码: %d", result);
        return 0;
    }
    
    LOGD("成功锁定 AHardwareBuffer，虚拟地址: %p", virtualAddress);
    return reinterpret_cast<jlong>(virtualAddress);
#else
    (void)ptr;
    LOGE("AHardwareBuffer 仅支持 Android 26+");
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_bb_rtmp_NativeBridge_unlockAHardwareBuffer(JNIEnv *env, jclass clazz, jlong ptr) {
#if __ANDROID_API__ >= 26
    if (ptr == 0) return;
    AHardwareBuffer *buffer = reinterpret_cast<AHardwareBuffer *>(ptr);
    int result = AHardwareBuffer_unlock(buffer, nullptr);
    if (result != 0) {
        LOGE("解锁 AHardwareBuffer 失败，错误码: %d", result);
    }
#else
    (void)ptr;
#endif
}

JNIEXPORT void JNICALL
Java_com_bb_rtmp_NativeBridge_releaseAHardwareBufferPtr(JNIEnv *env, jclass clazz, jlong ptr) {
#if __ANDROID_API__ >= 26
    if (ptr == 0) return;
    AHardwareBuffer *buffer = reinterpret_cast<AHardwareBuffer *>(ptr);
    AHardwareBuffer_release(buffer);
#else
    (void)ptr;
#endif
}

JNIEXPORT jlong JNICALL
Java_com_bb_rtmp_NativeBridge_getDirectBufferAddress(JNIEnv *env, jclass clazz, jobject buffer) {
    if (buffer == nullptr) {
        LOGE("ByteBuffer 为空");
        return 0;
    }
    
    // 使用 JNI 标准方法获取 DirectByteBuffer 的地址
    void* address = env->GetDirectBufferAddress(buffer);
    if (address == nullptr) {
        LOGE("获取 DirectByteBuffer 地址失败（可能不是 DirectByteBuffer）");
        return 0;
    }
    
    return reinterpret_cast<jlong>(address);
}

} // extern "C"

