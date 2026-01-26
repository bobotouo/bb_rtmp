#!/bin/bash

# 解压 yolo-android AAR 并替换到 yolo_flutter
# 用法: ./extract_aar.sh

set -e

# 获取脚本所在目录（项目根目录）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

AAR_FILE="yolo-android/build/outputs/aar/yolo-android-release.aar"
TEMP_DIR=$(mktemp -d)
JAR_DEST="yolo_flutter/android/libs/yolo-android.jar"
SO_DEST="yolo_flutter/android/src/main/jniLibs/arm64-v8a/libyolo_detector.so"

echo "========================================"
echo "解压 AAR 并替换到 yolo_flutter"
echo "========================================"
echo ""

# 检查 AAR 文件是否存在
if [ ! -f "$AAR_FILE" ]; then
    echo "错误: AAR 文件不存在: $AAR_FILE"
    echo "请先编译 AAR:"
    echo "  cd yolo-android"
    echo "  ./gradlew assembleRelease"
    exit 1
fi

echo "AAR 文件: $AAR_FILE"
echo "临时目录: $TEMP_DIR"
echo ""

# 解压 AAR（AAR 实际上是一个 ZIP 文件）
echo "正在解压 AAR..."
unzip -q "$AAR_FILE" -d "$TEMP_DIR"

# 检查必要文件是否存在
if [ ! -f "$TEMP_DIR/classes.jar" ]; then
    echo "错误: classes.jar 不存在于 AAR 中"
    rm -rf "$TEMP_DIR"
    exit 1
fi

if [ ! -f "$TEMP_DIR/jni/arm64-v8a/libyolo_detector.so" ]; then
    echo "错误: libyolo_detector.so 不存在于 AAR 中"
    rm -rf "$TEMP_DIR"
    exit 1
fi

# 创建目标目录（如果不存在）
mkdir -p "$(dirname "$JAR_DEST")"
mkdir -p "$(dirname "$SO_DEST")"

# 复制 JAR
echo "复制 classes.jar -> $JAR_DEST"
cp "$TEMP_DIR/classes.jar" "$JAR_DEST"

# 复制 SO
echo "复制 libyolo_detector.so -> $SO_DEST"
cp "$TEMP_DIR/jni/arm64-v8a/libyolo_detector.so" "$SO_DEST"

# 清理临时目录
rm -rf "$TEMP_DIR"

echo ""
echo "========================================"
echo "完成！"
echo "========================================"
echo ""
echo "已更新:"
echo "  - $JAR_DEST"
echo "  - $SO_DEST"
echo ""
