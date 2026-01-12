import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// RTMP 推流插件主类
class BbRtmp {
  static const MethodChannel _channel = MethodChannel('com.bb.rtmp/plugin');

  /// 初始化推流器
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

  /// 开始推流
  static Future<void> startStreaming() async {
    try {
      await _channel.invokeMethod('startStreaming');
    } on PlatformException catch (e) {
      throw Exception('开始推流失败: ${e.message}');
    }
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
