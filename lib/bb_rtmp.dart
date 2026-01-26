import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// 帧数据类型
enum FrameType {
  /// NV12 帧句柄（需要手动释放，或使用 autoReleaseNv12 自动释放）
  nv12Handle,

  /// FBO RGBA 数据（零拷贝，不需要释放）
  /// 注意：数据来自原生层的 buffer pool，地址在短时间内有效
  /// 建议在回调中立即使用，不要保存地址供后续使用
  fboRgba,
}

/// RTMP 推流插件主类
class BbRtmp {
  static const MethodChannel _channel = MethodChannel('com.bb.rtmp/plugin');
  static const EventChannel _frameEventChannel =
      EventChannel('com.bb.rtmp/frames');
  static const EventChannel _statusEventChannel =
      EventChannel('com.bb.rtmp/status');

  /// 初始化相机预览（不包含推流）
  ///
  /// [width] 视频宽度
  /// [height] 视频高度
  /// [fps] 帧率
  /// [isPortrait] 是否竖屏模式（true=竖屏，false=横屏）
  /// [initialCameraFacing] 初始摄像头方向 ('front' 或 'back')
  ///
  /// 返回预览纹理 ID，用于在 Flutter UI 中显示摄像头预览
  static Future<int?> initializePreview({
    required int width,
    required int height,
    int fps = 30,
    bool isPortrait = true,
    String initialCameraFacing = 'front',
  }) async {
    try {
      final result = await _channel.invokeMethod('initializePreview', {
        'width': width,
        'height': height,
        'fps': fps,
        'isPortrait': isPortrait,
        'initialCameraFacing': initialCameraFacing,
      });
      return result as int?;
    } on PlatformException catch (e) {
      throw Exception('初始化预览失败: ${e.message}');
    }
  }

  /// 停止预览并销毁 Texture
  ///
  /// 调用此方法会立即停止摄像头采集并释放原生纹理资源，防止页面销毁时的闪烁。
  /// 建议在 [Navigator.pop] 之前或 [Widget.dispose] 中调用。
  static Future<void> stopPreview() async {
    try {
      await _channel.invokeMethod('stopPreview');
    } on PlatformException catch (e) {
      debugPrint('停止预览失败: ${e.message}');
    }
  }

  /// 初始化推流器（旧接口，保持向后兼容）
  ///
  /// [rtmpUrl] RTMP 推流地址
  /// [width] 视频宽度
  /// [height] 视频高度
  /// [bitrate] 初始码率（bps）
  /// [fps] 帧率
  /// [enableAudio] 是否启用音频
  /// [isPortrait] 是否竖屏模式（true=竖屏，false=横屏）
  /// [initialCameraFacing] 初始摄像头方向 ('front' 或 'back')
  ///
  /// 返回预览纹理 ID，用于在 Flutter UI 中显示摄像头预览
  static Future<int?> initialize({
    required String rtmpUrl,
    required int width,
    required int height,
    int bitrate = 2000000,
    int fps = 30,
    bool enableAudio = true,
    bool isPortrait = true,
    String initialCameraFacing = 'front',
  }) async {
    try {
      final result = await _channel.invokeMethod('initialize', {
        'rtmpUrl': rtmpUrl,
        'width': width,
        'height': height,
        'bitrate': bitrate,
        'fps': fps,
        'enableAudio': enableAudio,
        'isPortrait': isPortrait,
        'initialCameraFacing': initialCameraFacing,
      });
      return result as int?;
    } on PlatformException catch (e) {
      throw Exception('初始化失败: ${e.message}');
    }
  }

  /// 开始推流（异步，立即返回）
  ///
  /// [rtmpUrl] RTMP 推流地址（如果之前未设置）
  /// [bitrate] 码率（bps）
  /// [enableAudio] 是否启用音频
  ///
  /// 注意：此方法立即返回，不会等待连接完成。
  /// 连接状态会通过 [listenToStreamingStatus] 回调通知。
  /// 如果预览未初始化，会抛出异常。
  static Future<void> startStreaming({
    String? rtmpUrl,
    int bitrate = 2000000,
    bool enableAudio = true,
  }) async {
    try {
      await _channel.invokeMethod('startStreaming', {
        if (rtmpUrl != null) 'rtmpUrl': rtmpUrl,
        'bitrate': bitrate,
        'enableAudio': enableAudio,
      });
    } on PlatformException catch (e) {
      throw Exception('开始推流失败: ${e.message}');
    }
  }

  /// 监听推流状态变化
  ///
  /// [onStatus] 状态回调函数，接收状态信息：
  ///   - status: 状态字符串，可能的值：
  ///     - "connecting": 正在连接中
  ///     - "connected": 连接成功，开始推流
  ///     - "failed": 连接失败
  ///     - "stopped": 推流已停止
  ///   - error: 错误信息（仅在失败时提供）
  ///
  /// 返回 StreamSubscription，可以调用 cancel() 取消监听
  static StreamSubscription<dynamic> listenToStreamingStatus({
    required void Function(String status, String? error) onStatus,
  }) {
    return _statusEventChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (event is Map) {
          final status = event['status'] as String? ?? '';
          final error = event['error'] as String?;
          onStatus(status, error);
        }
      },
      onError: (error) {
        debugPrint('推流状态流错误: $error');
      },
    );
  }

  /// 停止推流
  static Future<void> stopStreaming() async {
    try {
      await _channel.invokeMethod('stopStreaming');
    } on PlatformException catch (e) {
      throw Exception('停止推流失败: ${e.message}');
    }
  }

  /// 释放资源
  static Future<void> release() async {
    try {
      await _channel.invokeMethod('release');
    } on PlatformException catch (e) {
      throw Exception('释放资源失败: ${e.message}');
    }
  }

  /// 切换摄像头（前后摄像头切换）
  static Future<void> switchCamera() async {
    try {
      await _channel.invokeMethod('switchCamera');
    } on PlatformException catch (e) {
      throw Exception('切换摄像头失败: ${e.message}');
    }
  }

  /// 切换分辨率
  ///
  /// [width] 新的视频宽度
  /// [height] 新的视频高度
  static Future<void> changeResolution({
    required int width,
    required int height,
  }) async {
    try {
      await _channel.invokeMethod('changeResolution', {
        'width': width,
        'height': height,
      });
    } on PlatformException catch (e) {
      throw Exception('切换分辨率失败: ${e.message}');
    }
  }

  /// 设置码率
  ///
  /// [bitrate] 新的码率（bps）
  static Future<void> setBitrate(int bitrate) async {
    try {
      await _channel.invokeMethod('setBitrate', {
        'bitrate': bitrate,
      });
    } on PlatformException catch (e) {
      throw Exception('设置码率失败: ${e.message}');
    }
  }

  /// 获取当前推流状态
  static Future<StreamStatus> getStatus() async {
    try {
      final result = await _channel.invokeMethod('getStatus');
      return StreamStatus.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw Exception('获取状态失败: ${e.message}');
    }
  }

  /// 启用/禁用帧数据回调
  ///
  /// [enable] 是否启用回调
  /// [skipFrame] 跳帧数，默认 0（不跳帧，每帧都回调）
  ///   - 0: 不跳帧，每帧都回调
  ///   - n: 每隔 n 帧回调一次（例如 3 表示每 3 帧回调一次）
  static Future<bool> enableFrameCallback(bool enable,
      {int skipFrame = 0}) async {
    try {
      final result = await _channel.invokeMethod('enableFrameCallback', {
        'enable': enable,
        'skipFrame': skipFrame,
      });
      return result as bool;
    } on PlatformException catch (e) {
      throw Exception('设置帧回调失败: ${e.message}');
    }
  }

  /// 监听帧数据流
  ///
  /// [types] 要监听的帧类型列表，只监听指定的类型，减少不必要的回调
  /// [onFrame] 回调函数，接收 NV12 帧句柄（如果监听了 nv12Handle）
  /// [onFboRgba] 回调函数，接收 FBO RGBA 数据（如果监听了 fboRgba）
  ///   - FBO 数据使用零拷贝传递（只传地址），数据来自原生层 buffer pool
  ///   - 建议在回调中立即使用地址，不要保存供后续使用，避免数据被覆盖
  /// [autoReleaseNv12] 是否自动释放 NV12 句柄（默认 true）
  ///   - 如果为 true，回调后自动释放，无需手动调用 releasePixelBufferHandle
  ///   - 如果为 false，需要手动调用 releasePixelBufferHandle 释放，否则会内存泄漏
  /// 返回 StreamSubscription，可以调用 cancel() 取消监听
  static StreamSubscription<dynamic> listenToFrames({
    required Set<FrameType> types,
    void Function(NV12FrameHandle frame)? onFrame,
    void Function(Map<String, dynamic> fboRgba)? onFboRgba,
    bool autoReleaseNv12 = true,
  }) {
    if (types.isEmpty) {
      throw ArgumentError('types 不能为空');
    }
    if (types.contains(FrameType.nv12Handle) && onFrame == null) {
      throw ArgumentError('监听 nv12Handle 时必须提供 onFrame 回调');
    }
    if (types.contains(FrameType.fboRgba) && onFboRgba == null) {
      throw ArgumentError('监听 fboRgba 时必须提供 onFboRgba 回调');
    }

    return _frameEventChannel.receiveBroadcastStream().listen(
      (dynamic event) async {
        if (event is Map) {
          final type = event['type'] as String?;
          if (type == 'fbo_rgba' &&
              types.contains(FrameType.fboRgba) &&
              onFboRgba != null) {
            // FBO RGBA 数据（零拷贝，不需要释放）
            onFboRgba(Map<String, dynamic>.from(event));
          } else if (type == 'nv12_handle' &&
              types.contains(FrameType.nv12Handle) &&
              onFrame != null) {
            // NV12 帧句柄
            final frame =
                NV12FrameHandle.fromMap(Map<String, dynamic>.from(event));
            onFrame(frame);
            // 如果启用自动释放，回调后立即释放
            if (autoReleaseNv12) {
              try {
                await releasePixelBufferHandle(frame.handle);
              } catch (e) {
                // 忽略释放错误，避免影响回调
              }
            }
          }
        }
      },
      onError: (error) {
        debugPrint('帧数据流错误: $error');
      },
    );
  }

  // 为了向后兼容，保留原来的方法签名
  @Deprecated(
      '使用 listenToFrames 替代，指定 types: {FrameType.nv12Handle, FrameType.fboRgba}')
  static StreamSubscription<dynamic> listenToFramesOld({
    required void Function(NV12FrameHandle frame) onFrame,
    void Function(Map<String, dynamic> fboRgba)? onFboRgba,
  }) {
    return listenToFrames(
      types: {
        FrameType.nv12Handle,
        if (onFboRgba != null) FrameType.fboRgba,
      },
      onFrame: onFrame,
      onFboRgba: onFboRgba,
      autoReleaseNv12: false, // 保持旧行为，不自动释放
    );
  }

  /// 释放句柄（重要：使用完后必须释放，否则会内存泄漏）
  ///
  /// [handle] 句柄 ID
  /// 返回是否成功释放
  static Future<bool> releasePixelBufferHandle(int handle) async {
    try {
      final result = await _channel.invokeMethod('releasePixelBufferHandle', {
        'handle': handle,
      });
      return result as bool;
    } on PlatformException catch (e) {
      throw Exception('释放句柄失败: ${e.message}');
    }
  }

  /// 获取 HardwareBuffer 的 native handle（用于传递给需要直接访问内存的插件）
  ///
  /// [handle] 句柄 ID
  /// 返回 native handle（Long 类型，可以直接作为指针使用）
  static Future<int> getHardwareBufferNativeHandle(int handle) async {
    try {
      final result =
          await _channel.invokeMethod('getHardwareBufferNativeHandle', {
        'handle': handle,
      });
      return result as int;
    } on PlatformException catch (e) {
      throw Exception('获取 native handle 失败: ${e.message}');
    }
  }

  /// 获取 Image Planes 数据（用于 YUV_420_888 格式，零拷贝）
  ///
  /// [handle] 句柄 ID
  /// 返回包含 Y、U、V 平面地址和 stride 信息的 Map
  static Future<Map<String, dynamic>> getImagePlanes(int handle) async {
    try {
      final result = await _channel.invokeMethod('getImagePlanes', {
        'handle': handle,
      });
      return Map<String, dynamic>.from(result);
    } on PlatformException catch (e) {
      throw Exception('获取 Image Planes 失败: ${e.message}');
    }
  }

  /// 获取相机 zoom 范围
  ///
  /// 返回包含 minZoom, maxZoom, currentZoom 的 Map
  static Future<ZoomRange> getZoomRange() async {
    try {
      final result = await _channel.invokeMethod('getZoomRange');
      return ZoomRange.fromMap(Map<String, dynamic>.from(result));
    } on PlatformException catch (e) {
      throw Exception('获取 zoom 范围失败: ${e.message}');
    }
  }

  /// 设置相机 zoom
  ///
  /// [zoom] zoom 值，必须在 minZoom 和 maxZoom 之间
  static Future<void> setZoom(double zoom) async {
    try {
      await _channel.invokeMethod('setZoom', {
        'zoom': zoom,
      });
    } on PlatformException catch (e) {
      throw Exception('设置 zoom 失败: ${e.message}');
    }
  }
}

/// 推流状态信息
class StreamStatus {
  final bool isStreaming;
  final int currentBitrate;
  final double fps;
  final int width;
  final int height;
  final int previewWidth;
  final int previewHeight;
  final String? cameraId;

  StreamStatus({
    required this.isStreaming,
    required this.currentBitrate,
    required this.fps,
    required this.width,
    required this.height,
    required this.previewWidth,
    required this.previewHeight,
    this.cameraId,
  });

  factory StreamStatus.fromMap(Map<String, dynamic> map) {
    return StreamStatus(
      isStreaming: map['isStreaming'] ?? false,
      currentBitrate: map['currentBitrate'] ?? 0,
      fps: (map['fps'] ?? 0.0).toDouble(),
      width: map['width'] ?? 0,
      height: map['height'] ?? 0,
      previewWidth: map['previewWidth'] ?? map['width'] ?? 0,
      previewHeight: map['previewHeight'] ?? map['height'] ?? 0,
      cameraId: map['cameraId'],
    );
  }
}

/// 摄像头预览 Widget
class CameraPreview extends StatelessWidget {
  final int textureId;
  final BoxFit fit;

  const CameraPreview({
    Key? key,
    required this.textureId,
    this.fit = BoxFit.contain, // 使用 contain 避免裁剪和拉伸
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Texture(textureId: textureId);
  }
}

/// Zoom 范围信息
class ZoomRange {
  final double minZoom;
  final double maxZoom;
  final double currentZoom;

  ZoomRange({
    required this.minZoom,
    required this.maxZoom,
    required this.currentZoom,
  });

  factory ZoomRange.fromMap(Map<String, dynamic> map) {
    return ZoomRange(
      minZoom: (map['minZoom'] ?? 1.0).toDouble(),
      maxZoom: (map['maxZoom'] ?? 1.0).toDouble(),
      currentZoom: (map['currentZoom'] ?? 1.0).toDouble(),
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'minZoom': minZoom,
      'maxZoom': maxZoom,
      'currentZoom': currentZoom,
    };
  }

  @override
  String toString() {
    return 'ZoomRange(min: $minZoom, max: $maxZoom, current: $currentZoom)';
  }
}

/// NV12 帧句柄
class NV12FrameHandle {
  final int handle; // 句柄指针（Int64）
  final int width;
  final int height;
  final int timestamp; // 微秒
  final int pixelFormat; // 像素格式类型

  NV12FrameHandle({
    required this.handle,
    required this.width,
    required this.height,
    required this.timestamp,
    required this.pixelFormat,
  });

  factory NV12FrameHandle.fromMap(Map<String, dynamic> map) {
    return NV12FrameHandle(
      handle: map['handle'] ?? 0,
      width: map['width'] ?? 0,
      height: map['height'] ?? 0,
      timestamp: map['timestamp'] ?? 0,
      pixelFormat: map['pixelFormat'] ?? 0,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'handle': handle,
      'width': width,
      'height': height,
      'timestamp': timestamp,
      'pixelFormat': pixelFormat,
    };
  }

  @override
  String toString() {
    return 'NV12FrameHandle(handle: $handle, size: ${width}x${height}, timestamp: $timestamp, format: $pixelFormat)';
  }
}
