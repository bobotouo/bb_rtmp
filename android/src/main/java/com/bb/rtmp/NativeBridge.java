package com.bb.rtmp;

import android.hardware.HardwareBuffer;

/**
 * 用于获取 AHardwareBuffer* 指针的桥接类（零拷贝）
 */
public class NativeBridge {
    static {
        System.loadLibrary("bb_rtmp");
    }

    /**
     * 从 HardwareBuffer 获取底层 AHardwareBuffer* 指针（调用方需负责 release）
     */
    public static native long getAHardwareBufferPtr(HardwareBuffer buffer);

    /**
     * 锁定 AHardwareBuffer 并获取虚拟地址（零拷贝，可直接访问内存）
     * @param ptr AHardwareBuffer* 指针
     * @return 虚拟地址（可直接访问的内存指针），失败返回 0
     */
    public static native long lockAHardwareBuffer(long ptr);

    /**
     * 解锁 AHardwareBuffer（对应 lockAHardwareBuffer）
     * @param ptr AHardwareBuffer* 指针
     */
    public static native void unlockAHardwareBuffer(long ptr);

    /**
     * 释放 AHardwareBuffer* 指针（对应 getAHardwareBufferPtr 返回的指针）
     */
    public static native void releaseAHardwareBufferPtr(long ptr);
    
    /**
     * 获取 DirectByteBuffer 的 native 地址（用于零拷贝传递数据）
     * @param buffer DirectByteBuffer
     * @return native 地址，失败返回 0
     */
    public static native long getDirectBufferAddress(java.nio.ByteBuffer buffer);
}
