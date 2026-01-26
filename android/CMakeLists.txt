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

# 优先使用预编译的 librtmp 静态库，如果不存在则回退到源码编译（用于开发调试）
set(PREBUILT_LIBRTMP_DIR ${CMAKE_SOURCE_DIR}/../prebuilt/android/${ANDROID_ABI})
set(LIBRTMP_SOURCE_DIR ${CMAKE_SOURCE_DIR}/../rtmpdump/librtmp)

# 检查预编译库是否存在
if(EXISTS "${PREBUILT_LIBRTMP_DIR}/librtmp.a")
    message(STATUS "使用预编译的 librtmp: ${PREBUILT_LIBRTMP_DIR}/librtmp.a")
    
    # 使用预编译的静态库
    add_library(rtmp STATIC IMPORTED)
    set_target_properties(rtmp PROPERTIES
        IMPORTED_LOCATION "${PREBUILT_LIBRTMP_DIR}/librtmp.a"
    )
    # 预编译库的头文件在 include/librtmp/ 目录下
    target_include_directories(rtmp INTERFACE "${PREBUILT_LIBRTMP_DIR}/include")
else()
    message(WARNING "预编译库不存在，使用源码编译（开发模式）: ${LIBRTMP_SOURCE_DIR}")
    message(WARNING "运行 ./build_librtmp.sh 可以生成预编译库")
    
    # 回退到源码编译（用于开发调试）
    set(LIBRTMP_SOURCES
        ${LIBRTMP_SOURCE_DIR}/rtmp.c
        ${LIBRTMP_SOURCE_DIR}/log.c
        ${LIBRTMP_SOURCE_DIR}/amf.c
        ${LIBRTMP_SOURCE_DIR}/parseurl.c
        ${LIBRTMP_SOURCE_DIR}/hashswf.c
    )
    
    add_library(rtmp STATIC ${LIBRTMP_SOURCES})
    # 源码编译时，头文件直接在 librtmp 目录下，需要添加父目录以便使用 librtmp/rtmp.h
    get_filename_component(LIBRTMP_PARENT_DIR ${LIBRTMP_SOURCE_DIR} DIRECTORY)
    target_include_directories(rtmp PUBLIC ${LIBRTMP_PARENT_DIR})
    target_compile_definitions(rtmp PUBLIC -DRTMPDUMP_VERSION=\"v2.6\" -DNO_CRYPTO)
endif()

find_library(log-lib log)
find_library(android-lib android)
find_library(z-lib z)

add_library(bb_rtmp SHARED
    src/main/cpp/rtmp_jni.cpp
    src/main/cpp/rtmp_wrapper.cpp
)

target_include_directories(bb_rtmp PRIVATE
    src/main/cpp
)

# 确保 bb_rtmp 可以访问 librtmp 的头文件（通过链接 rtmp target）
target_link_libraries(bb_rtmp
    rtmp
    ${z-lib}
    ${log-lib}
    ${android-lib}
)
