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
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.dependency 'Flutter'
  s.platform = :ios, '11.0'

  # Swift and source files
  s.source_files = 'Classes/**/*.{h,m,mm,swift,cpp,c}'
  
  # Mark crypto-related headers as private to exclude from umbrella header
  # These files are only needed when CRYPTO is enabled, which we disable with NO_CRYPTO=1
  s.private_header_files = [
    'Classes/librtmp/dh.h',
    'Classes/librtmp/dhgroups.h',
    'Classes/librtmp/handshake.h'
  ]
  
  # Preserve directory structure for librtmp
  s.preserve_paths = 'Classes/librtmp/**/*'
  
  # Pod target settings
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'CLANG_CXX_LIBRARY' => 'libc++',
    'HEADER_SEARCH_PATHS' => '"${PODS_TARGET_SRCROOT}/Classes/librtmp" "${PODS_TARGET_SRCROOT}/Classes"',
    'GCC_PREPROCESSOR_DEFINITIONS' => 'NO_CRYPTO=1 USE_SIMPLE_HANDSHAKE=1',
    'CLANG_ALLOW_NON_MODULAR_INCLUDES_IN_FRAMEWORK_MODULES' => 'YES',
    'CLANG_ENABLE_MODULES' => 'NO',
    'OTHER_CFLAGS' => '-Wno-error=implicit-function-declaration -Wno-error=incompatible-pointer-types'
  }
  
  # User target settings
  s.user_target_xcconfig = {
    'HEADER_SEARCH_PATHS' => '"${PODS_ROOT}/bb_rtmp/Classes/librtmp"'
  }
  
  # Frameworks
  s.frameworks = 'VideoToolbox', 'AudioToolbox', 'AVFoundation', 'CoreMedia', 'Accelerate'
  
  s.swift_version = '5.0'
end

