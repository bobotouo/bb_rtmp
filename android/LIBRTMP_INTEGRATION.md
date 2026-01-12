# librtmp 集成说明

## 概述

当前实现已在 CMake 内直接编译 `rtmpdump/librtmp`（arm64-v8a，仅 16KB page）。默认使用 NDK 自带的 BoringSSL（`-lssl -lcrypto`）和 `-Wl,-z,max-page-size=16384`。

## 集成步骤

### 1. 目录要求
`rtmpdump` 已放在项目根目录 `/Users/bobo/Desktop/project/bb_rtmp/rtmpdump`，CMake 会直接引用 `rtmpdump/librtmp` 源码。

### 2. 编译说明
- 仅支持 ABI: `arm64-v8a`
- 链接参数：`-Wl,-z,max-page-size=16384`
- 依赖：NDK 自带 `ssl` / `crypto` / `z`（BoringSSL）
- 无需单独生成 `librtmp.so`，CMake 会静态编译 `librtmp` 并与插件共享库一起输出。

## FLV 封装

RTMP 推流需要将 H.264 和 AAC 数据封装为 FLV 格式：

### 视频 FLV Tag 结构

```
[TagType(1)] [DataSize(3)] [Timestamp(3)] [TimestampExtended(1)] [StreamID(3)] [Data]
```

### 音频 FLV Tag 结构

类似视频，但 TagType 不同。

## 注意事项

1. **线程安全**：确保 RTMP 操作在适当的线程中执行
2. **错误处理**：实现完善的错误处理和重连机制
3. **性能优化**：使用零拷贝技术，避免不必要的数据复制
4. **内存管理**：正确释放 RTMP 资源，避免内存泄漏

## 参考资源

- RTMP 协议规范
- FLV 格式规范
- librtmp 文档

