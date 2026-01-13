#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RtmpWrapper : NSObject

- (instancetype)init;

/**
 * Initialize RTMP connection
 * @param url RTMP URL
 * @return 0 on success, negative on failure
 */
- (int)initialize:(NSString *)url;

/**
 * Set metadata
 */
- (int)setMetadataWithWidth:(int)width
                     height:(int)height
               videoBitrate:(int)videoBitrate
                        fps:(int)fps
            audioSampleRate:(int)audioSampleRate
              audioChannels:(int)audioChannels NS_SWIFT_NAME(setMetadata(withWidth:height:videoBitrate:fps:audioSampleRate:audioChannels:));

/**
 * Send video data
 * @param data H.264 data
 * @param timestamp Timestamp in microseconds
 * @param isKeyFrame YES if keyframe
 */
- (int)sendVideo:(NSData *)data timestamp:(long)timestamp isKeyFrame:(BOOL)isKeyFrame;

/**
 * Send audio data
 * @param data AAC data
 * @param timestamp Timestamp in microseconds
 */
- (int)sendAudio:(NSData *)data timestamp:(long)timestamp;

/**
 * Get network stats
 * @return Dictionary with keys: bytesSent, delayMs, packetLossPercent
 */
- (NSDictionary<NSString *, NSNumber *> * _Nullable)getStats;

/**
 * Close connection
 */
- (void)close;

@end

NS_ASSUME_NONNULL_END
