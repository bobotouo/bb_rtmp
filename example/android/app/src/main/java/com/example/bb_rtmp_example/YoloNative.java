package com.example.bb_rtmp_example;

import android.content.res.AssetManager;
import java.util.Map;

/**
 * YOLO Native 桥接类（直接调用 C++，零拷贝）
 */
public class YoloNative {
    static {
        System.loadLibrary("yoloncnn");
    }

    /**
     * 加载 YOLO 模型
     * @param assetManager AssetManager（用于从 assets 加载模型文件）
     * @param paramPath 模型参数文件路径（assets 相对路径）
     * @param modelPath 模型文件路径（assets 相对路径）
     * @param useGpu 是否使用 GPU
     * @return 是否成功
     */
    public static native boolean loadModel(AssetManager assetManager, String paramPath, String modelPath, boolean useGpu);

    /**
     * 使用指针直接检测（零拷贝）
     * @param dataPointer 数据指针地址（AHardwareBuffer lock 后的虚拟地址）
     * @param width 图像宽度
     * @param height 图像高度
     * @return 检测结果数组，每个元素是 Map<String, Object>，包含 label, prob, x, y, width, height
     */
    public static native Map<String, Object>[] detectFromPointer(long dataPointer, int width, int height);
    
    /**
     * 直接从 RGB/RGBA 数据检测（用于 FBO 数据，性能最优）
     * @param rgbData RGB/RGBA 数据指针（DirectByteBuffer 地址）
     * @param width 图像宽度
     * @param height 图像高度
     * @param stride 每行字节数（0 表示 width * 3 或 width * 4，取决于格式）
     * @return 检测结果数组
     */
    public static native Map<String, Object>[] detectFromRgb(long rgbData, int width, int height, int stride);

    /**
     * 卸载模型
     */
    public static native void unloadModel();
}
