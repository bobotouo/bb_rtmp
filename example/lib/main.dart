import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:bb_rtmp/bb_rtmp.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:yolo_flutter/yolo_flutter.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  int? _textureId;
  bool _isStreaming = false;
  bool _isInitialized = false;
  String _statusMessage = '正在初始化...';
  StreamStatus? _streamStatus;
  final TextEditingController _urlController = TextEditingController(
    text: 'rtmp://10.240.107.156/live/livestream',
  );
  int _currentWidth = 1080;
  int _currentHeight = 1920;
  int _currentBitrate = 4000000; // 竖屏 1080p 建议 4Mbps
  double _previewAspectRatio = 9.0 / 16.0; // 默认竖屏宽高比
  int _previewWidth = 1080;
  int _previewHeight = 1920;
  bool _isPortrait = true; // 是否竖屏模式（默认竖屏，符合抖音等主流平台）
  bool _isOrientationLocked = false; // 是否锁定屏幕方向
  Orientation? _currentOrientation; // 当前屏幕方向
  String _initialCameraFacing = 'front'; // 初始摄像头方向

  StreamSubscription<dynamic>? _frameSubscription;
  // YOLO Flutter 插件
  final YoloFlutter _yolo = YoloFlutter();
  final ikunLabels = ['basketball', 'player', 'referee', 'hoop', 'backboard'];

  // 检测结果
  List<Map<String, dynamic>> _detections = [];
  int _detectionSourceWidth = 1920;
  int _detectionSourceHeight = 1080;

  @override
  void initState() {
    super.initState();
    _requestPermissions();
    // 监听屏幕方向变化
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _updateOrientation();
    });
    // 先初始化模型
    initOurModel();
  }

  initOurModel() async {
    try {
      // 初始化 YOLO 插件
      await _yolo.initialize();

      // 加载模型（与 rootBundle.load 相同的 asset 路径，原生层用 FlutterLoader 解析）
      final result = await _yolo.loadModel(
        paramPath: 'assets/model/yolo11n.ncnn.param',
        binPath: 'assets/model/yolo11n.ncnn.bin',
        useGpu: false, // 使用 GPU 推理
        numThreads: 4,
      );
      if (!result) {
        debugPrint('YOLO 模型初始化失败');
      }
    } catch (e) {
      debugPrint('YOLO 模型初始化异常: $e');
    }
  }

  /// 请求摄像头和麦克风权限
  Future<bool> _requestPermissions() async {
    setState(() {
      _statusMessage = '正在请求权限...';
    });

    // Request camera permission
    var cameraStatus = await Permission.camera.request();
    if (cameraStatus.isDenied) {
      _showMessage('需要摄像头权限才能使用此功能');
      setState(() {
        _statusMessage = '摄像头权限被拒绝';
      });
      return false;
    }

    // Request microphone permission on mobile
    var microphoneStatus = await Permission.microphone.request();
    if (microphoneStatus.isDenied) {
      _showMessage('需要麦克风权限才能使用此功能');
      setState(() {
        _statusMessage = '麦克风权限被拒绝';
      });
      return false;
    }

    if (cameraStatus.isGranted && microphoneStatus.isGranted) {
      setState(() {
        _statusMessage = '权限已授予';
      });
      return true;
    }

    return false;
  }

  /// Check if running on mobile platform

  Future<void> _initialize() async {
    if (_isInitialized) {
      _showMessage('已经初始化，请先释放资源');
      return;
    }

    // 检查并请求权限
    final hasPermissions = await _requestPermissions();
    if (!hasPermissions) {
      _showMessage('需要摄像头和麦克风权限才能初始化');
      return;
    }

    final url = _urlController.text.trim();
    if (url.isEmpty || !url.startsWith('rtmp://')) {
      _showMessage('请输入有效的 RTMP 地址');
      return;
    }

    setState(() {
      _statusMessage = '正在初始化...';
    });

    try {
      // 横屏推流使用 16:9 比例（如 1280x720、1920x1080）
      // GOP 已在编码器中设置为 2 秒
      int finalWidth = _currentWidth;
      int finalHeight = _currentHeight;

      final textureId = await BbRtmp.initialize(
        rtmpUrl: url,
        width: finalWidth,
        height: finalHeight,
        bitrate: _currentBitrate,
        fps: 30,
        enableAudio: true,
        isPortrait: _isPortrait,
        initialCameraFacing: _initialCameraFacing,
      );

      if (textureId != null && textureId >= 0) {
        setState(() {
          _textureId = textureId;
          _isInitialized = true;
          _statusMessage =
              '初始化成功 - ${_isPortrait ? "竖屏" : "横屏"}模式 ${finalWidth}x${finalHeight}';
        });
        // 获取实际预览分辨率并更新宽高比
        await _updateStatus();
        if (_streamStatus != null &&
            _streamStatus!.width > 0 &&
            _streamStatus!.height > 0) {
          setState(() {
            _previewAspectRatio = _streamStatus!.width / _streamStatus!.height;
          });
        }

        // 打开原始帧回调
        await BbRtmp.enableFrameCallback(true);
        // 只监听 FBO RGBA 数据（用于 YOLO 检测），自动释放 NV12 句柄
        _frameSubscription = BbRtmp.listenToFrames(
          types: {FrameType.fboRgba},
          onFboRgba: (Map<String, dynamic> fboRgba) {
            // 处理 FBO RGBA 数据（用于 YOLO 检测）
            _processFboRgba(fboRgba);
          },
          autoReleaseNv12: true, // 自动释放 NV12 句柄（虽然我们不监听，但设置以防万一）
        );
      } else {
        setState(() {
          _statusMessage = '初始化失败：未返回纹理 ID';
        });
      }
    } catch (e) {
      setState(() {
        _statusMessage = '初始化失败: $e';
      });
      _showMessage('初始化失败: $e');
    }
  }

  // 并发控制：限制同时处理的检测任务数量，避免堆积
  bool _isProcessingDetection = false;
  int _pendingDetections = 0;
  static const int maxPendingDetections = 2; // 最多 2 个待处理任务

  void _processFboRgba(Map<String, dynamic> eventData) async {
    try {
      if (eventData['type'] != 'fbo_rgba') return;

      // 并发限制：如果已有太多待处理任务，丢弃当前帧，避免堆积
      if (_pendingDetections >= maxPendingDetections ||
          _isProcessingDetection) {
        return; // 丢弃帧，避免内存和性能问题
      }

      // 指针可能由 MethodChannel 以 Int/Number 传来，需保留 64 位
      final addressValue = eventData['address'];
      final address = addressValue is int
          ? addressValue
          : (addressValue is num ? addressValue.round() : null);
      final width = eventData['width'] as int?;
      final height = eventData['height'] as int?;
      final stride = eventData['stride'] as int?;

      if (address == null ||
          width == null ||
          height == null ||
          stride == null) {
        return;
      }

      // 标记为处理中
      _isProcessingDetection = true;
      _pendingDetections++;

      // 使用 yolo_flutter 插件进行检测
      // Android: flipVertical=false - glReadPixels 数据是上下颠倒的（OpenGL 坐标系左下角为原点）
      //          C++ 层会在返回前将 y 坐标从颠倒图像坐标系转为正常图像坐标系
      // iOS: flipVertical=false - CVPixelBuffer 原点是左上角（Top-Left），数据是正常方向
      //      原生层（yolo_bridge.mm）会在返回前翻转 y 坐标以匹配 UI 坐标系
      // 坐标转换已在各自的原生层处理，Dart 层统一使用 flipVertical=false
      final detections = await _yolo.detectRgbaPointer(
        address,
        width,
        height,
        stride: stride,
        flipVertical: false, // Android 和 iOS 都使用 false，坐标转换在各自原生层处理
      );

      // 更新检测结果（坐标转换已在原生层处理，直接使用）
      if (mounted) {
        if (detections.isNotEmpty) {
          setState(() {
            _detections = detections
                .map((d) => {
                      'x': d.x,
                      'y': d.y,
                      'width': d.width,
                      'height': d.height,
                      'label': d.label,
                      'prob': d.confidence,
                    })
                .toList();
            _detectionSourceWidth = width;
            _detectionSourceHeight = height;
          });
        } else {
          setState(() {
            _detections = [];
          });
        }
      }
    } catch (e) {
      // 记录错误但不阻塞
      debugPrint('检测错误: $e');
    } finally {
      // 确保状态重置
      _isProcessingDetection = false;
      _pendingDetections = _pendingDetections > 0 ? _pendingDetections - 1 : 0;
    }
  }

  Future<void> _startStreaming() async {
    if (!_isInitialized) {
      _showMessage('请先初始化');
      return;
    }

    if (_isStreaming) {
      _showMessage('推流已在进行中');
      return;
    }

    setState(() {
      _statusMessage = '正在开始推流...';
    });

    try {
      await BbRtmp.startStreaming();
      setState(() {
        _isStreaming = true;
        _statusMessage = '推流中...';
      });
      _updateStatus();
      _showMessage('推流已开始');
    } catch (e) {
      setState(() {
        _statusMessage = '开始推流失败: $e';
      });
      _showMessage('开始推流失败: $e');
    }
  }

  Future<void> _stopStreaming() async {
    if (!_isStreaming) {
      return;
    }

    setState(() {
      _statusMessage = '正在停止推流...';
    });

    try {
      await BbRtmp.stopStreaming();
      setState(() {
        _isStreaming = false;
        _statusMessage = '推流已停止';
      });
      _showMessage('推流已停止');
    } catch (e) {
      setState(() {
        _statusMessage = '停止推流失败: $e';
      });
      _showMessage('停止推流失败: $e');
    }
  }

  Future<void> _switchCamera() async {
    if (!_isInitialized) {
      _showMessage('请先初始化');
      return;
    }

    try {
      await BbRtmp.switchCamera();
      _showMessage('摄像头已切换');
      _updateStatus();
    } catch (e) {
      _showMessage('切换摄像头失败: $e');
    }
  }

  Future<void> _setBitrate(int bitrate) async {
    if (!_isInitialized) {
      _showMessage('请先初始化');
      return;
    }

    try {
      await BbRtmp.setBitrate(bitrate);
      setState(() {
        _currentBitrate = bitrate;
        _statusMessage = '码率已设置: ${bitrate ~/ 1000} kbps';
      });
      _showMessage('码率已设置: ${bitrate ~/ 1000} kbps');
      _updateStatus();
    } catch (e) {
      _showMessage('设置码率失败: $e');
    }
  }

  Future<void> _updateStatus() async {
    if (!_isInitialized) return;

    try {
      final status = await BbRtmp.getStatus();
      setState(() {
        _streamStatus = status;
        // 更新预览宽高比（使用实际预览分辨率，从实际帧获取）
        if (status.previewWidth > 0 && status.previewHeight > 0) {
          _previewWidth = status.previewWidth;
          _previewHeight = status.previewHeight;
          _previewAspectRatio = _previewWidth / _previewHeight;
        } else if (status.width > 0 && status.height > 0) {
          // 兼容旧版本，如果没有 previewWidth/previewHeight，使用 width/height
          _previewWidth = status.width;
          _previewHeight = status.height;
          _previewAspectRatio = _previewWidth / _previewHeight;
        }
        if (status.isStreaming) {
          _statusMessage =
              '推流中 - ${status.width}x${status.height} @ ${status.currentBitrate ~/ 1000} kbps';
        }
      });
    } catch (e) {
      // 忽略状态获取错误
    }
  }

  Future<void> _release() async {
    if (!_isInitialized) {
      return;
    }

    if (_isStreaming) {
      await _stopStreaming();
    }

    // 释放帧回调
    _frameSubscription?.cancel();
    _frameSubscription = null;
    await BbRtmp.enableFrameCallback(false);

    try {
      await BbRtmp.release();
      setState(() {
        _isInitialized = false;
        _textureId = null;
        _isStreaming = false;
        _statusMessage = '已释放资源';
        _streamStatus = null;
      });
      _showMessage('资源已释放');
    } catch (e) {
      _showMessage('释放资源失败: $e');
    }
  }

  void _showMessage(String message) {
    // 使用 GlobalKey 或者直接使用 BuildContext
    // 注意：这里需要在 build 方法中获取 context
    try {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(message),
          duration: const Duration(seconds: 2),
        ),
      );
    } catch (e) {
      // 如果 ScaffoldMessenger 不可用，静默忽略
    }
  }

  /// 更新屏幕方向并应用
  void _updateOrientation() {
    final orientation = MediaQuery.of(context).orientation;
    if (_currentOrientation != orientation) {
      setState(() {
        _currentOrientation = orientation;
        final wasPortrait = _isPortrait;
        _isPortrait = orientation == Orientation.portrait;

        // 如果方向改变且已初始化，需要重新初始化推流
        if (_isInitialized && wasPortrait != _isPortrait) {
          _handleOrientationChange();
        } else if (!_isInitialized) {
          // 如果未初始化，自动交换宽高以匹配新方向
          final temp = _currentWidth;
          _currentWidth = _currentHeight;
          _currentHeight = temp;
          _previewAspectRatio = _isPortrait ? 9.0 / 16.0 : 16.0 / 9.0;
        }
      });
    }
  }

  /// 处理屏幕方向改变（重新初始化推流）
  Future<void> _handleOrientationChange() async {
    if (!_isInitialized) return;

    final wasStreaming = _isStreaming;

    // 如果正在推流，先停止
    if (wasStreaming) {
      await _stopStreaming();
    }

    // 释放资源
    await _release();

    // 自动交换宽高以匹配新方向
    final temp = _currentWidth;
    _currentWidth = _currentHeight;
    _currentHeight = temp;
    _previewAspectRatio = _isPortrait ? 9.0 / 16.0 : 16.0 / 9.0;

    // 重新初始化
    await _initialize();

    // 如果之前正在推流，自动重新开始
    if (wasStreaming) {
      await Future.delayed(const Duration(milliseconds: 500));
      await _startStreaming();
    }

    _showMessage('屏幕方向已切换为${_isPortrait ? "竖屏" : "横屏"}');
  }

  /// 切换屏幕方向锁定
  Future<void> _toggleOrientationLock() async {
    setState(() {
      _isOrientationLocked = !_isOrientationLocked;
    });

    if (_isOrientationLocked) {
      // 锁定当前方向
      final orientations = _isPortrait
          ? [DeviceOrientation.portraitUp]
          : [DeviceOrientation.landscapeLeft, DeviceOrientation.landscapeRight];
      await SystemChrome.setPreferredOrientations(orientations);
      _showMessage('屏幕方向已锁定为${_isPortrait ? "竖屏" : "横屏"}');
    } else {
      // 解锁，允许所有方向
      await SystemChrome.setPreferredOrientations([
        DeviceOrientation.portraitUp,
        DeviceOrientation.portraitDown,
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      _showMessage('屏幕方向已解锁');
    }
  }

  @override
  void dispose() {
    // 恢复屏幕方向设置
    SystemChrome.setPreferredOrientations([
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
    _release();
    _urlController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 更新屏幕方向
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _updateOrientation();
    });

    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(
          title: const Text('RTMP 推流测试'),
          actions: [
            IconButton(
              icon: const Icon(Icons.info_outline),
              onPressed: _updateStatus,
              tooltip: '刷新状态',
            ),
          ],
        ),
        body: SingleChildScrollView(
          child: Column(
            children: [
              // 状态栏
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12.0),
                color:
                    _isStreaming ? Colors.green.shade100 : Colors.grey.shade200,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _statusMessage,
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: _isStreaming
                            ? Colors.green.shade900
                            : Colors.grey.shade800,
                      ),
                    ),
                    if (_streamStatus != null) ...[
                      const SizedBox(height: 4),
                      Text(
                        '分辨率: ${_streamStatus!.width}x${_streamStatus!.height} | '
                        '码率: ${_streamStatus!.currentBitrate ~/ 1000} kbps | '
                        '帧率: ${_streamStatus!.fps.toStringAsFixed(1)} fps',
                        style: TextStyle(
                            fontSize: 12, color: Colors.grey.shade700),
                      ),
                    ],
                  ],
                ),
              ),

              // 预览区域（叠加检测框）
              Container(
                width: double.infinity,
                color: Colors.black,
                child: _textureId != null &&
                        _previewWidth > 0 &&
                        _previewHeight > 0
                    ? Center(
                        child: AspectRatio(
                          aspectRatio: _previewAspectRatio,
                          child: LayoutBuilder(
                            builder: (context, constraints) {
                              return Stack(
                                children: [
                                  // 摄像头预览
                                  CameraPreview(textureId: _textureId!),
                                  // 检测框叠加层
                                  if (_detections.isNotEmpty)
                                    CustomPaint(
                                      size: Size(constraints.maxWidth,
                                          constraints.maxHeight),
                                      painter: DetectionPainter(
                                        detections: _detections,
                                        sourceWidth: _detectionSourceWidth,
                                        sourceHeight: _detectionSourceHeight,
                                        labels: ikunLabels,
                                      ),
                                    ),
                                ],
                              );
                            },
                          ),
                        ),
                      )
                    : const Center(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            CircularProgressIndicator(),
                            SizedBox(height: 16),
                            Text(
                              '等待初始化...',
                              style: TextStyle(color: Colors.white),
                            ),
                          ],
                        ),
                      ),
              ),

              // 控制面板
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // RTMP 地址输入
                    TextField(
                      controller: _urlController,
                      decoration: const InputDecoration(
                        labelText: 'RTMP 推流地址',
                        hintText: 'rtmp://your-server.com/live/stream',
                        border: OutlineInputBorder(),
                        prefixIcon: Icon(Icons.link),
                      ),
                      enabled: !_isInitialized,
                    ),
                    const SizedBox(height: 16),

                    // 初始摄像头选择
                    DropdownButtonFormField<String>(
                      value: _initialCameraFacing,
                      decoration: const InputDecoration(
                        labelText: '初始摄像头',
                        border: OutlineInputBorder(),
                        contentPadding:
                            EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                      ),
                      items: const [
                        DropdownMenuItem(value: 'front', child: Text('前置摄像头')),
                        DropdownMenuItem(value: 'back', child: Text('后置摄像头')),
                      ],
                      onChanged: _isInitialized
                          ? null
                          : (value) {
                              if (value != null) {
                                setState(() {
                                  _initialCameraFacing = value;
                                });
                              }
                            },
                    ),
                    const SizedBox(height: 16),

                    // 初始化按钮
                    ElevatedButton.icon(
                      onPressed: _isInitialized ? null : _initialize,
                      icon: const Icon(Icons.play_arrow),
                      label: const Text('初始化'),
                      style: ElevatedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                      ),
                    ),
                    const SizedBox(height: 16),

                    // 推流控制
                    Row(
                      children: [
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: _isInitialized && !_isStreaming
                                ? _startStreaming
                                : null,
                            icon: const Icon(Icons.videocam),
                            label: const Text('开始推流'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.green,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 16),
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: ElevatedButton.icon(
                            onPressed: _isStreaming ? _stopStreaming : null,
                            icon: const Icon(Icons.stop),
                            label: const Text('停止推流'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.red,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 16),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),

                    // 摄像头控制
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _isInitialized ? _switchCamera : null,
                            icon: const Icon(Icons.cameraswitch),
                            label: const Text('切换摄像头'),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _isInitialized ? _release : null,
                            icon: const Icon(Icons.close),
                            label: const Text('释放资源'),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),

                    // 屏幕方向控制
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _toggleOrientationLock,
                            icon: Icon(_isOrientationLocked
                                ? Icons.lock
                                : Icons.lock_open),
                            label:
                                Text(_isOrientationLocked ? '方向已锁定' : '锁定方向'),
                            style: OutlinedButton.styleFrom(
                              backgroundColor: _isOrientationLocked
                                  ? Colors.amber.shade50
                                  : Colors.grey.shade50,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: () {
                              // 手动切换方向（旋转设备或点击按钮）
                              if (_isOrientationLocked) {
                                _showMessage('请先解锁屏幕方向');
                                return;
                              }
                              // 强制切换方向
                              final newOrientation = _isPortrait
                                  ? DeviceOrientation.landscapeLeft
                                  : DeviceOrientation.portraitUp;
                              SystemChrome.setPreferredOrientations(
                                  [newOrientation]);
                            },
                            icon: Icon(_isPortrait
                                ? Icons.screen_rotation
                                : Icons.screen_rotation),
                            label: Text(_isPortrait ? '切换到横屏' : '切换到竖屏'),
                            style: OutlinedButton.styleFrom(
                              backgroundColor: _isPortrait
                                  ? Colors.blue.shade50
                                  : Colors.orange.shade50,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),

                    // 当前方向显示
                    Container(
                      padding: const EdgeInsets.all(12.0),
                      decoration: BoxDecoration(
                        color: _isPortrait
                            ? Colors.blue.shade50
                            : Colors.orange.shade50,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(
                          color: _isPortrait
                              ? Colors.blue.shade200
                              : Colors.orange.shade200,
                        ),
                      ),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(
                            _isPortrait
                                ? Icons.stay_current_portrait
                                : Icons.stay_current_landscape,
                            color: _isPortrait
                                ? Colors.blue.shade700
                                : Colors.orange.shade700,
                          ),
                          const SizedBox(width: 8),
                          Text(
                            '当前: ${_isPortrait ? "竖屏 (9:16)" : "横屏 (16:9)"} | '
                            '分辨率: ${_currentWidth}x$_currentHeight',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: _isPortrait
                                  ? Colors.blue.shade700
                                  : Colors.orange.shade700,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),

                    // 分辨率选择（根据竖屏/横屏模式动态显示）
                    Text(
                      '分辨率 (${_isPortrait ? "竖屏 9:16" : "横屏 16:9"})',
                      style: const TextStyle(
                          fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: _isPortrait
                          ? [
                              _buildResolutionButton(720, 1280, '720p'),
                              _buildResolutionButton(1080, 1920, '1080p 推荐'),
                              _buildResolutionButton(1440, 2560, '2K'),
                            ]
                          : [
                              _buildResolutionButton(1280, 720, '720p'),
                              _buildResolutionButton(1920, 1080, '1080p 推荐'),
                              _buildResolutionButton(2560, 1440, '2K'),
                            ],
                    ),
                    const SizedBox(height: 24),

                    // 码率选择（1080p@30fps 推荐 4Mbps，@60fps 推荐 6Mbps）
                    const Text(
                      '码率 (1080p@30fps 建议 4Mbps)',
                      style:
                          TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        _buildBitrateButton(2000000, '2 Mbps'),
                        _buildBitrateButton(3000000, '3 Mbps'),
                        _buildBitrateButton(4000000, '4 Mbps 推荐'),
                        _buildBitrateButton(5000000, '5 Mbps'),
                        _buildBitrateButton(6000000, '6 Mbps'),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildResolutionButton(int width, int height, String label) {
    final isSelected = _currentWidth == width && _currentHeight == height;
    return OutlinedButton(
      // 只允许在初始化前设置分辨率
      onPressed: !_isInitialized
          ? () {
              setState(() {
                _currentWidth = width;
                _currentHeight = height;
              });
            }
          : null,
      style: OutlinedButton.styleFrom(
        backgroundColor: isSelected ? Colors.blue.shade50 : null,
        side: BorderSide(
          color: isSelected ? Colors.blue : Colors.grey,
          width: isSelected ? 2 : 1,
        ),
      ),
      child: Text(label),
    );
  }

  Widget _buildBitrateButton(int bitrate, String label) {
    final isSelected = _currentBitrate == bitrate;
    return OutlinedButton(
      onPressed: _isInitialized ? () => _setBitrate(bitrate) : null,
      style: OutlinedButton.styleFrom(
        backgroundColor: isSelected ? Colors.blue.shade50 : null,
        side: BorderSide(
          color: isSelected ? Colors.blue : Colors.grey,
          width: isSelected ? 2 : 1,
        ),
      ),
      child: Text(label),
    );
  }
}

/// 检测框绘制器
class DetectionPainter extends CustomPainter {
  final List<Map<String, dynamic>> detections;
  final int sourceWidth;
  final int sourceHeight;
  final List<String> labels;

  DetectionPainter({
    required this.detections,
    required this.sourceWidth,
    required this.sourceHeight,
    required this.labels,
  });

  // 不同类别的颜色
  static const List<Color> colors = [
    Colors.red,
    Colors.green,
    Colors.blue,
    Colors.orange,
    Colors.purple,
    Colors.cyan,
    Colors.pink,
    Colors.amber,
  ];

  @override
  void paint(Canvas canvas, Size size) {
    final scaleX = size.width / sourceWidth;
    final scaleY = size.height / sourceHeight;

    for (final det in detections) {
      final x = (det['x'] as num).toDouble() * scaleX;
      final y = (det['y'] as num).toDouble() * scaleY;
      final w = (det['width'] as num).toDouble() * scaleX;
      final h = (det['height'] as num).toDouble() * scaleY;
      final labelIdx = (det['label'] as num).toInt();
      final prob = (det['prob'] as num).toDouble();

      // 获取颜色和标签
      final color = colors[labelIdx % colors.length];
      final labelText =
          labelIdx < labels.length ? labels[labelIdx] : 'class$labelIdx';

      // 绘制边框
      final paint = Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2.0;

      canvas.drawRect(Rect.fromLTWH(x, y, w, h), paint);

      // 绘制标签背景
      final textSpan = TextSpan(
        text: '$labelText ${(prob * 100).toStringAsFixed(0)}%',
        style: const TextStyle(
          color: Colors.white,
          fontSize: 12,
          fontWeight: FontWeight.bold,
        ),
      );
      final textPainter = TextPainter(
        text: textSpan,
        textDirection: TextDirection.ltr,
      );
      textPainter.layout();

      final bgPaint = Paint()..color = color;
      final bgRect = Rect.fromLTWH(
        x,
        y - textPainter.height - 4,
        textPainter.width + 8,
        textPainter.height + 4,
      );
      canvas.drawRect(bgRect, bgPaint);

      // 绘制文字
      textPainter.paint(canvas, Offset(x + 4, y - textPainter.height - 2));
    }
  }

  @override
  bool shouldRepaint(DetectionPainter oldDelegate) {
    return detections != oldDelegate.detections;
  }
}
