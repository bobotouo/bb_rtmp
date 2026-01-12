# bb_rtmp

高性能 Flutter RTMP 推流插件，支持硬件编码、零拷贝、自适应码率。

## 特性

- ✅ 硬件编码（MediaCodec）
- ✅ 零拷贝架构
- ✅ 自适应码率控制
- ✅ 摄像头切换
- ✅ AMF0

## 安装

在 `pubspec.yaml` 中添加：

```yaml
dependencies:
  bb_rtmp:
    path: ./bb_rtmp
```

## 使用方法

```dart
import 'package:bb_rtmp/bb_rtmp.dart';

// 初始化
final textureId = await BbRtmp.initialize(
  rtmpUrl: 'rtmp://your-server.com/live/stream',
  width: 1280,
  height: 720,
  bitrate: 2000000,
  fps: 30,
  enableAudio: true,
);

// 在 UI 中显示预览
CameraPreview(textureId: textureId!)

// 开始推流
await BbRtmp.startStreaming();

// 切换摄像头
await BbRtmp.switchCamera();

// 切换分辨率
await BbRtmp.changeResolution(width: 640, height: 480);

// 设置码率
await BbRtmp.setBitrate(1500000);

// 停止推流
await BbRtmp.stopStreaming();

// 释放资源
await BbRtmp.release();
```

## Android 权限

确保在 `AndroidManifest.xml` 中添加以下权限：

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

## 要求

- Flutter SDK >= 2.5.0
- Android API Level >= 21 (Android 5.0+)
- NDK 支持（用于编译 librtmp）

