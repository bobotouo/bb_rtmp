cmake_minimum_required(VERSION 3.18.1)
project("bb_rtmp")

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 仅支持 arm64-v8a
if (NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported for this build.")
endif()

# 16KB page size
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# librtmp 源路径
set(LIBRTMP_DIR ${CMAKE_SOURCE_DIR}/../rtmpdump/librtmp)

# 禁用加密支持，不需要 BoringSSL 头文件

set(LIBRTMP_SOURCES
    ${LIBRTMP_DIR}/rtmp.c
    ${LIBRTMP_DIR}/log.c
    ${LIBRTMP_DIR}/amf.c
    ${LIBRTMP_DIR}/parseurl.c
    ${LIBRTMP_DIR}/hashswf.c
)

add_library(rtmp STATIC ${LIBRTMP_SOURCES})
target_include_directories(rtmp PUBLIC ${LIBRTMP_DIR})
# 禁用加密支持（NO_CRYPTO），因为 NDK 27 可能不包含 BoringSSL 头文件
# 对于基本的 RTMP 推流，通常不需要加密功能
target_compile_definitions(rtmp PUBLIC -DRTMPDUMP_VERSION=\"v2.6\" -DNO_CRYPTO)

find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

# 禁用加密支持，不需要链接 ssl 和 crypto 库

add_library(bb_rtmp SHARED
    src/main/cpp/rtmp_jni.cpp
    src/main/cpp/rtmp_wrapper.cpp
)

target_include_directories(bb_rtmp PRIVATE
    src/main/cpp
    ${LIBRTMP_DIR}
)

target_link_libraries(bb_rtmp
    rtmp
    ${z-lib}
    ${log-lib}
    ${android-lib}
)

