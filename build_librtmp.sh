#!/bin/bash
# 构建 librtmp 静态库脚本
# 用于将 librtmp 编译为静态库，供 Android 和 iOS 使用

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RTMPDUMP_DIR="${SCRIPT_DIR}/rtmpdump"
LIBRTMP_DIR="${RTMPDUMP_DIR}/librtmp"
BUILD_DIR="${SCRIPT_DIR}/prebuilt"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}开始构建 librtmp 静态库...${NC}"

# 检查源码目录
if [ ! -d "${LIBRTMP_DIR}" ]; then
    echo -e "${RED}错误: librtmp 源码目录不存在: ${LIBRTMP_DIR}${NC}"
    exit 1
fi

# 创建构建目录
mkdir -p "${BUILD_DIR}"

# ========== Android 构建 ==========
echo -e "${YELLOW}构建 Android (arm64-v8a) 静态库...${NC}"

ANDROID_BUILD_DIR="${BUILD_DIR}/android/arm64-v8a"
mkdir -p "${ANDROID_BUILD_DIR}"

# 检查 NDK 路径（从环境变量或常见位置）
if [ -z "${ANDROID_NDK}" ]; then
    if [ -d "${HOME}/Library/Android/sdk/ndk" ]; then
        NDK_DIR=$(find "${HOME}/Library/Android/sdk/ndk" -maxdepth 1 -type d | sort -V | tail -1)
    elif [ -d "${ANDROID_HOME}/ndk" ]; then
        NDK_DIR=$(find "${ANDROID_HOME}/ndk" -maxdepth 1 -type d | sort -V | tail -1)
    else
        echo -e "${RED}错误: 未找到 Android NDK，请设置 ANDROID_NDK 环境变量${NC}"
        exit 1
    fi
else
    NDK_DIR="${ANDROID_NDK}"
fi

echo "使用 NDK: ${NDK_DIR}"

# 设置工具链
TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/darwin-x86_64"
API_LEVEL=26
ARCH=arm64
TARGET=aarch64-linux-android${API_LEVEL}
CC="${TOOLCHAIN}/bin/${TARGET}-clang"
AR="${TOOLCHAIN}/bin/llvm-ar"

# 编译选项
CFLAGS="-O2 -Wall -fPIC -DRTMPDUMP_VERSION=\"v2.6\" -DNO_CRYPTO"
INCLUDES="-I${LIBRTMP_DIR}"

# 编译源文件
cd "${LIBRTMP_DIR}"
SOURCES="rtmp.c log.c amf.c parseurl.c hashswf.c"

echo "编译 Android 源文件..."
for src in ${SOURCES}; do
    obj="${ANDROID_BUILD_DIR}/${src%.c}.o"
    echo "  编译: ${src} -> ${obj}"
    "${CC}" ${CFLAGS} ${INCLUDES} -c "${src}" -o "${obj}" || {
        echo -e "${RED}编译失败: ${src}${NC}"
        exit 1
    }
done

# 创建静态库
echo "创建静态库: ${ANDROID_BUILD_DIR}/librtmp.a"
"${AR}" rcs "${ANDROID_BUILD_DIR}/librtmp.a" "${ANDROID_BUILD_DIR}"/*.o || {
    echo -e "${RED}创建静态库失败${NC}"
    exit 1
}

# 复制头文件
mkdir -p "${ANDROID_BUILD_DIR}/include/librtmp"
cp "${LIBRTMP_DIR}/rtmp.h" "${LIBRTMP_DIR}/amf.h" "${LIBRTMP_DIR}/log.h" \
   "${LIBRTMP_DIR}/http.h" "${LIBRTMP_DIR}/rtmp_sys.h" \
   "${ANDROID_BUILD_DIR}/include/librtmp/"

echo -e "${GREEN}Android 构建完成: ${ANDROID_BUILD_DIR}/librtmp.a${NC}"

# ========== iOS 构建 ==========
echo -e "${YELLOW}构建 iOS 静态库...${NC}"

IOS_BUILD_DIR="${BUILD_DIR}/ios"
mkdir -p "${IOS_BUILD_DIR}"

# iOS 使用 Xcode 工具链
XCODE_DEVELOPER=$(xcode-select -p)
SDK_PATH=$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || echo "${XCODE_DEVELOPER}/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk")
CC=$(xcrun --sdk iphoneos --find clang 2>/dev/null || echo "${XCODE_DEVELOPER}/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang")
AR=$(xcrun --sdk iphoneos --find ar 2>/dev/null || echo "${XCODE_DEVELOPER}/Toolchains/XcodeDefault.xctoolchain/usr/bin/ar")

# 检查 SDK
if [ ! -d "${SDK_PATH}" ]; then
    echo -e "${RED}错误: 未找到 iOS SDK: ${SDK_PATH}${NC}"
    exit 1
fi

# 编译选项
IOS_CFLAGS="-O2 -Wall -fPIC -DRTMPDUMP_VERSION=\"v2.6\" -DNO_CRYPTO=1 -DUSE_SIMPLE_HANDSHAKE=1"
IOS_CFLAGS="${IOS_CFLAGS} -arch arm64 -isysroot ${SDK_PATH} -miphoneos-version-min=11.0"
IOS_INCLUDES="-I${LIBRTMP_DIR}"

# 编译源文件
cd "${LIBRTMP_DIR}"
echo "编译 iOS 源文件..."
for src in ${SOURCES}; do
    obj="${IOS_BUILD_DIR}/${src%.c}.o"
    echo "  编译: ${src} -> ${obj}"
    "${CC}" ${IOS_CFLAGS} ${IOS_INCLUDES} -c "${src}" -o "${obj}" || {
        echo -e "${RED}编译失败: ${src}${NC}"
        exit 1
    }
done

# 创建静态库
echo "创建静态库: ${IOS_BUILD_DIR}/librtmp.a"
"${AR}" rcs "${IOS_BUILD_DIR}/librtmp.a" "${IOS_BUILD_DIR}"/*.o || {
    echo -e "${RED}创建静态库失败${NC}"
    exit 1
}

# 复制头文件
mkdir -p "${IOS_BUILD_DIR}/include/librtmp"
cp "${LIBRTMP_DIR}/rtmp.h" "${LIBRTMP_DIR}/amf.h" "${LIBRTMP_DIR}/log.h" \
   "${LIBRTMP_DIR}/http.h" "${LIBRTMP_DIR}/rtmp_sys.h" \
   "${IOS_BUILD_DIR}/include/librtmp/"

echo -e "${GREEN}iOS 构建完成: ${IOS_BUILD_DIR}/librtmp.a${NC}"

echo -e "${GREEN}所有构建完成！${NC}"
echo -e "预编译库位置:"
echo -e "  Android: ${ANDROID_BUILD_DIR}/librtmp.a"
echo -e "  iOS: ${IOS_BUILD_DIR}/librtmp.a"
