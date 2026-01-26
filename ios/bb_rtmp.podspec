#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint bb_rtmp.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'bb_rtmp'
  s.version          = '0.1.0'
  s.summary          = 'High performance RTMP streaming plugin.'
  s.description      = <<-DESC
High performance RTMP streaming plugin with hardware encoding and adaptive bitrate.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :type => 'MIT', :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.dependency 'Flutter'
  s.platform = :ios, '11.0'

  # 直接使用源码编译 librtmp
  puts "使用源码编译 librtmp"
  
  # 包含所有源文件（包括 librtmp 源码）
  s.source_files = 'Classes/**/*.{h,m,mm,swift,cpp,c}'
  
  s.private_header_files = [
    'Classes/librtmp/dh.h',
    'Classes/librtmp/dhgroups.h',
    'Classes/librtmp/handshake.h'
  ]
  
  s.preserve_paths = 'Classes/librtmp/**/*'
  
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'CLANG_CXX_LIBRARY' => 'libc++',
    'HEADER_SEARCH_PATHS' => '"${PODS_TARGET_SRCROOT}/Classes/librtmp" "${PODS_TARGET_SRCROOT}/Classes"',
    'GCC_PREPROCESSOR_DEFINITIONS' => 'NO_CRYPTO=1 USE_SIMPLE_HANDSHAKE=1',
    'CLANG_ALLOW_NON_MODULAR_INCLUDES_IN_FRAMEWORK_MODULES' => 'YES',
    'OTHER_CFLAGS' => '-Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types'
  }
  
  s.user_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => '"${PODS_ROOT}/bb_rtmp/Classes/librtmp"'
  }

  # Frameworks
  s.frameworks = 'VideoToolbox', 'AudioToolbox', 'AVFoundation', 'CoreMedia', 'Accelerate'

  s.swift_version = '5.0'
end
