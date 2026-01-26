package com.example.bb_rtmp_example

import android.content.res.AssetManager
import android.os.Handler
import android.os.HandlerThread
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.bb_rtmp_example/yolo"
    
    // 后台线程用于 YOLO 推理
    private var yoloThread: HandlerThread? = null
    private var yoloHandler: Handler? = null
    
    // 帧率限制：只处理每 N 帧中的 1 帧（设置为 1 表示处理所有帧，用于调试）
    private val frameSkipCount = AtomicInteger(0)
    private val FRAME_SKIP = 1 // 暂时设为 1，处理所有帧以调试
    
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // 创建后台线程用于 YOLO 推理
        yoloThread = HandlerThread("YoloThread").apply {
            start()
            yoloHandler = Handler(looper)
        }
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "loadModel" -> {
                    try {
                        val paramPathArg = call.argument<String>("paramPath") ?: "assets/model/yolo11n.ncnn.param"
                        val modelPathArg = call.argument<String>("modelPath") ?: "assets/model/yolo11n.ncnn.bin"
                        val useGpu = call.argument<Boolean>("useGpu") ?: false
                        
                        // 使用 FlutterLoader 获取资源在 Android assets 中的实际路径
                        val loader = FlutterInjector.instance().flutterLoader()
                        val paramPath = loader.getLookupKeyForAsset(paramPathArg)
                        val modelPath = loader.getLookupKeyForAsset(modelPathArg)
                        
                        val success = YoloNative.loadModel(assets, paramPath, modelPath, useGpu)
                        if (!success) {
                            android.util.Log.e("MainActivity", "Failed to load YOLO model")
                        }
                        result.success(success)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Exception loading YOLO model", e)
                        result.error("LOAD_MODEL_ERROR", "Failed to load model: ${e.message}", null)
                    }
                }
                "detectFromRgb" -> {
                    val rgbDataArg = call.argument<Number>("rgbData")
                    val rgbData = when (rgbDataArg) {
                        is Long -> rgbDataArg
                        is Int -> rgbDataArg.toLong()
                        is Number -> rgbDataArg.toLong()
                        else -> 0L
                    }
                    val width = call.argument<Int>("width") ?: 0
                    val height = call.argument<Int>("height") ?: 0
                    val stride = call.argument<Int>("stride") ?: 0
                    
                    if (rgbData == 0L || width <= 0 || height <= 0) {
                        result.error("INVALID_ARGUMENT", "Invalid parameters", null)
                        return@setMethodCallHandler
                    }
                    
                    // 帧率限制
                    val currentCount = frameSkipCount.incrementAndGet()
                    if (currentCount % FRAME_SKIP != 0) {
                        result.success(emptyList<Map<String, Any>>())
                        return@setMethodCallHandler
                    }
                    
                    yoloHandler?.post {
                        try {
                            val detections = YoloNative.detectFromRgb(rgbData, width, height, stride)
                            
                            val resultList = if (detections == null) {
                                emptyList<Map<String, Any>>()
                            } else {
                                detections.map { obj ->
                                    mapOf(
                                        "label" to (obj["label"]?.toString()?.toIntOrNull() ?: 0),
                                        "prob" to (obj["prob"] as? Float ?: 0.0f).toDouble(),
                                        "x" to (obj["x"] as? Float ?: 0.0f).toDouble(),
                                        "y" to (obj["y"] as? Float ?: 0.0f).toDouble(),
                                        "width" to (obj["width"] as? Float ?: 0.0f).toDouble(),
                                        "height" to (obj["height"] as? Float ?: 0.0f).toDouble()
                                    )
                                }
                            }
                            Handler(mainLooper).post {
                                result.success(resultList)
                            }
                        } catch (e: Exception) {
                            Handler(mainLooper).post {
                                result.error("DETECTION_ERROR", "Detection failed: ${e.message}", null)
                            }
                        }
                    } ?: result.error("DETECTION_ERROR", "YOLO thread not initialized", null)
                }
                "detectFromPointer" -> {
                    val pointer = call.argument<Long>("pointer") ?: 0L
                    val width = call.argument<Int>("width") ?: 0
                    val height = call.argument<Int>("height") ?: 0
                    
                    if (pointer == 0L || width <= 0 || height <= 0) {
                        result.error("INVALID_ARGUMENT", "Invalid parameters", null)
                        return@setMethodCallHandler
                    }
                    
                    val currentCount = frameSkipCount.incrementAndGet()
                    if (currentCount % FRAME_SKIP != 0) {
                        result.success(emptyList<Map<String, Any>>())
                        return@setMethodCallHandler
                    }
                    
                    yoloHandler?.post {
                        try {
                            val detections = YoloNative.detectFromPointer(pointer, width, height)
                            
                            val resultList = if (detections == null) {
                                emptyList<Map<String, Any>>()
                            } else {
                                detections.map { obj ->
                                    mapOf(
                                        "label" to (obj["label"]?.toString()?.toIntOrNull() ?: 0),
                                        "prob" to (obj["prob"] as? Float ?: 0.0f).toDouble(),
                                        "x" to (obj["x"] as? Float ?: 0.0f).toDouble(),
                                        "y" to (obj["y"] as? Float ?: 0.0f).toDouble(),
                                        "width" to (obj["width"] as? Float ?: 0.0f).toDouble(),
                                        "height" to (obj["height"] as? Float ?: 0.0f).toDouble()
                                    )
                                }
                            }
                            Handler(mainLooper).post {
                                result.success(resultList)
                            }
                        } catch (e: Exception) {
                            Handler(mainLooper).post {
                                result.error("DETECTION_ERROR", "Detection failed: ${e.message}", null)
                            }
                        }
                    } ?: result.error("DETECTION_ERROR", "YOLO thread not initialized", null)
                }
                "unloadModel" -> {
                    try {
                        YoloNative.unloadModel()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("UNLOAD_ERROR", "Failed to unload model: ${e.message}", null)
                    }
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 清理后台线程
        yoloThread?.quitSafely()
        yoloThread = null
        yoloHandler = null
    }
}
