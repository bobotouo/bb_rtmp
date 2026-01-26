//
//  BbRtmpPlugin.h
//  bb_rtmp
//
//  Header for BbRtmpPlugin Swift class
//

#import <Flutter/Flutter.h>

// Import the Swift module to make BbRtmpPlugin available
// This ensures the Swift class can be used from Objective-C
#if __has_feature(modules) && __has_include(<bb_rtmp/bb_rtmp-Swift.h>)
#import <bb_rtmp/bb_rtmp-Swift.h>
#elif __has_feature(modules) && __has_include("bb_rtmp-Swift.h")
#import "bb_rtmp-Swift.h"
#else
// Fallback: forward declaration (may not work for class methods)
@class BbRtmpPlugin;
#endif
