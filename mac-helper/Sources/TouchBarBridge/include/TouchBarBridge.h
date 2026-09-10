#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^WPTouchBarFrameHandler)(NSData *pngData, uint16_t width, uint16_t height);

/// Runtime-safe wrapper around macOS's private DFR Touch Bar simulator API.
/// All private symbols are resolved dynamically; unsupported systems return NO.
@interface WPTouchBarBridge : NSObject

@property(nonatomic, readonly, getter=isRunning) BOOL running;

- (BOOL)startWithFrameHandler:(WPTouchBarFrameHandler)handler;
- (void)stop;

/// phase: 0 = down, 1 = dragged, 2 = up. Coordinates span 0...65535
/// with an Android-style top-left origin.
- (void)postTouchPhase:(uint8_t)phase
       normalizedX:(uint16_t)x
       normalizedY:(uint16_t)y;

@end

NS_ASSUME_NONNULL_END
