#import "RtmpWrapper.h"
#include "rtmp_wrapper.h"

@implementation RtmpWrapper {
    rtmp_handle_t _handle;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _handle = 0;
    }
    return self;
}

- (int)initialize:(NSString *)url {
    if (_handle != 0) {
        [self close];
    }
    
    const char *cUrl = [url UTF8String];
    _handle = rtmp_init(cUrl);
    
    return (_handle != 0) ? 0 : -1;
}

- (int)setMetadataWithWidth:(int)width
                     height:(int)height
               videoBitrate:(int)videoBitrate
                        fps:(int)fps
            audioSampleRate:(int)audioSampleRate
              audioChannels:(int)audioChannels {
    if (_handle == 0) return -1;
    
    return rtmp_set_metadata(_handle, width, height, videoBitrate, fps, audioSampleRate, audioChannels);
}

- (int)sendVideo:(NSData *)data timestamp:(long)timestamp isKeyFrame:(BOOL)isKeyFrame {
    if (_handle == 0) return -1;
    
    return rtmp_send_video(_handle, (unsigned char *)[data bytes], (int)[data length], timestamp, isKeyFrame ? 1 : 0);
}

- (int)sendAudio:(NSData *)data timestamp:(long)timestamp {
    if (_handle == 0) return -1;
    
    return rtmp_send_audio(_handle, (unsigned char *)[data bytes], (int)[data length], timestamp);
}

- (NSDictionary<NSString *, NSNumber *> *)getStats {
    if (_handle == 0) return nil;
    
    rtmp_stats stats;
    if (rtmp_get_stats(_handle, &stats) == 0) {
        return @{
            @"bytesSent": @(stats.bytes_sent),
            @"delayMs": @(stats.delay_ms),
            @"packetLossPercent": @(stats.packet_loss_percent)
        };
    }
    
    return nil;
}

- (void)close {
    if (_handle != 0) {
        rtmp_close(_handle);
        _handle = 0;
    }
}

- (void)dealloc {
    [self close];
}

@end
