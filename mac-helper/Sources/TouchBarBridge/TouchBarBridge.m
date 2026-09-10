#import "TouchBarBridge.h"

#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#import <CoreImage/CoreImage.h>
#import <IOSurface/IOSurface.h>
#import <dlfcn.h>
#import <mach/mach_time.h>

typedef id (*WPSimulatorCreateFn)(int, id, int);
typedef id (*WPSimulatorGetTouchBarFn)(id);
typedef CGDisplayStreamRef (*WPTouchBarCreateStreamFn)(
    id, int, dispatch_queue_t, CGDisplayStreamFrameAvailableHandler);
typedef BOOL (*WPSimulatorPostEventFn)(id, NSEventType, NSPoint);
typedef void (*WPSimulatorInvalidateFn)(id);
typedef CGError (*WPDisplayStreamStartFn)(CGDisplayStreamRef);
typedef CGError (*WPDisplayStreamStopFn)(CGDisplayStreamRef);

@interface WPTouchBarBridge () {
    void *_dfrHandle;
    id _simulator;
    CGDisplayStreamRef _stream;
    dispatch_queue_t _captureQueue;
    WPTouchBarFrameHandler _frameHandler;
    uint64_t _lastEncodedAt;
    mach_timebase_info_data_t _timebase;
    WPSimulatorCreateFn _createSimulator;
    WPSimulatorGetTouchBarFn _getTouchBar;
    WPTouchBarCreateStreamFn _createStream;
    WPSimulatorPostEventFn _postEvent;
    WPSimulatorInvalidateFn _invalidateSimulator;
    WPDisplayStreamStartFn _startStream;
    WPDisplayStreamStopFn _stopStream;
}
@property(nonatomic, readwrite, getter=isRunning) BOOL running;
@end

@implementation WPTouchBarBridge

- (instancetype)init {
    self = [super init];
    if (self) {
        _captureQueue = dispatch_queue_create("com.xiaoxi.vibepad.touchbar.capture", DISPATCH_QUEUE_SERIAL);
        mach_timebase_info(&_timebase);
    }
    return self;
}

- (void)dealloc {
    [self stop];
}

- (BOOL)resolveSymbols {
    if (_dfrHandle) {
        return _createSimulator && _getTouchBar && _createStream && _postEvent &&
               _invalidateSimulator && _startStream && _stopStream;
    }
    _dfrHandle = dlopen("/System/Library/PrivateFrameworks/DFRFoundation.framework/DFRFoundation",
                       RTLD_LAZY | RTLD_LOCAL);
    if (!_dfrHandle) return NO;

#define WP_DFR_SYMBOL(name, type) (type)dlsym(_dfrHandle, name)
    _createSimulator = WP_DFR_SYMBOL("DFRTouchBarSimulatorCreate", WPSimulatorCreateFn);
    _getTouchBar = WP_DFR_SYMBOL("DFRTouchBarSimulatorGetTouchBar", WPSimulatorGetTouchBarFn);
    _createStream = WP_DFR_SYMBOL("DFRTouchBarCreateDisplayStream", WPTouchBarCreateStreamFn);
    _postEvent = WP_DFR_SYMBOL("DFRTouchBarSimulatorPostEventWithMouseActivity", WPSimulatorPostEventFn);
    _invalidateSimulator = WP_DFR_SYMBOL("DFRTouchBarSimulatorInvalidate", WPSimulatorInvalidateFn);
#undef WP_DFR_SYMBOL
    _startStream = (WPDisplayStreamStartFn)dlsym(RTLD_DEFAULT, "CGDisplayStreamStart");
    _stopStream = (WPDisplayStreamStopFn)dlsym(RTLD_DEFAULT, "CGDisplayStreamStop");
    BOOL complete = _createSimulator && _getTouchBar && _createStream && _postEvent &&
                    _invalidateSimulator && _startStream && _stopStream;
    if (!complete) {
        dlclose(_dfrHandle);
        _dfrHandle = NULL;
        _createSimulator = NULL;
        _getTouchBar = NULL;
        _createStream = NULL;
        _postEvent = NULL;
        _invalidateSimulator = NULL;
        _startStream = NULL;
        _stopStream = NULL;
    }
    return complete;
}

- (BOOL)startWithFrameHandler:(WPTouchBarFrameHandler)handler {
    if (!handler) return NO;
    if (![NSThread isMainThread]) {
        __block BOOL result = NO;
        dispatch_sync(dispatch_get_main_queue(), ^{ result = [self startWithFrameHandler:handler]; });
        return result;
    }
    if (self.running) {
        _frameHandler = [handler copy];
        return YES;
    }
    if (![self resolveSymbols]) return NO;

    _frameHandler = [handler copy];
    _lastEncodedAt = 0;
    _simulator = _createSimulator(3, nil, 3);
    id touchBar = _simulator ? _getTouchBar(_simulator) : nil;
    if (!touchBar) {
        [self cleanupOnMainThread];
        return NO;
    }

    __weak WPTouchBarBridge *weakSelf = self;
    _stream = _createStream(touchBar, 0, _captureQueue,
        ^(CGDisplayStreamFrameStatus status, uint64_t displayTime,
          IOSurfaceRef surface, CGDisplayStreamUpdateRef update) {
            (void)displayTime;
            (void)update;
            WPTouchBarBridge *self = weakSelf;
            if (!self || !self.running || status != kCGDisplayStreamFrameStatusFrameComplete || !surface) return;
            [self encodeSurface:surface];
        });
    if (!_stream) {
        [self cleanupOnMainThread];
        return NO;
    }
    // CGDisplayStream may deliver its first complete frame before Start returns.
    // Publish the running state first so that frame cannot be discarded.
    self.running = YES;
    if (_startStream(_stream) != kCGErrorSuccess) {
        [self cleanupOnMainThread];
        return NO;
    }
    return YES;
}

- (void)encodeSurface:(IOSurfaceRef)surface {
    // Throttle before PNG work. The serial capture queue naturally drops/coalesces
    // display updates while encoding, so frames cannot backlog behind input traffic.
    uint64_t now = mach_absolute_time();
    uint64_t elapsedTicks = now - _lastEncodedAt;
    double elapsedNs = (double)elapsedTicks * _timebase.numer / _timebase.denom;
    if (_lastEncodedAt && elapsedNs < (NSEC_PER_SEC / 12.0)) return;
    _lastEncodedAt = now;

    @autoreleasepool {
        size_t width = IOSurfaceGetWidth(surface);
        size_t height = IOSurfaceGetHeight(surface);
        if (!width || !height || width > UINT16_MAX || height > UINT16_MAX) return;
        CIImage *image = [CIImage imageWithIOSurface:surface];
        if (!image) return;
        NSBitmapImageRep *bitmap = [[NSBitmapImageRep alloc] initWithCIImage:image];
        NSData *png = [bitmap representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
        WPTouchBarFrameHandler handler = _frameHandler;
        if (png.length && handler && self.running) {
            handler(png, (uint16_t)width, (uint16_t)height);
        }
    }
}

- (void)postTouchPhase:(uint8_t)phase normalizedX:(uint16_t)x normalizedY:(uint16_t)y {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (!self.running || !self->_simulator || !self->_postEvent) return;
        NSEventType type;
        switch (phase) {
            case 0: type = NSEventTypeLeftMouseDown; break;
            case 1: type = NSEventTypeLeftMouseDragged; break;
            case 2: type = NSEventTypeLeftMouseUp; break;
            default: return;
        }
        CGFloat px = ((CGFloat)x / 65535.0) * 1004.0;
        CGFloat py = (1.0 - ((CGFloat)y / 65535.0)) * 30.0;
        self->_postEvent(self->_simulator, type, NSMakePoint(px, py));
    });
}

- (void)stop {
    if (![NSThread isMainThread]) {
        dispatch_sync(dispatch_get_main_queue(), ^{ [self stop]; });
        return;
    }
    [self cleanupOnMainThread];
}

- (void)cleanupOnMainThread {
    self.running = NO;
    _frameHandler = nil;
    if (_stream) {
        if (_stopStream) _stopStream(_stream);
        CFRelease(_stream);
        _stream = NULL;
    }
    if (_simulator && _invalidateSimulator) _invalidateSimulator(_simulator);
    _simulator = nil;
}

@end
