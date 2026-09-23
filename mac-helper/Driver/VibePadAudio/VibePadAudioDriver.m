//==================================================================================================
//
//  VibePadAudioDriver.m
//
//  VibePadAudio — a virtual loopback microphone implemented as a CoreAudio AudioServerPlugIn
//  (HAL plug-in, ".driver" bundle loaded by coreaudiod).
//
//  Original work written from scratch for the VibePad project (MIT license). The architecture
//  (object tree, lock-free ring buffer, host-clock time stamps) follows the publicly documented
//  AudioServerPlugIn model; no third-party driver source was used.
//
//  Object tree:
//      PlugIn (kAudioObjectPlugInObject)
//       └── Device "VibePad Microphone"  (UID com.xiaoxi.vibepad.audio.device)
//            ├── Stream (output) — receives PCM written by the VibePad helper app
//            └── Stream (input)  — exposes the same PCM to any app as a microphone
//
//  The loopback is a dumb single-writer/single-reader ring buffer living inside the plug-in.
//  Real-time safety rules for the IO path: no allocation, no locks, no logging; only atomic
//  loads/stores and memcpy/memset.
//
//==================================================================================================

#import <CoreAudio/CoreAudio.h>
#import <CoreAudio/AudioServerPlugIn.h>
#import <CoreAudio/HostTime.h>
#import <CoreFoundation/CoreFoundation.h>
#import <CoreFoundation/CFPlugInCOM.h>

#import <mach/mach_time.h>
#import <math.h>
#import <os/log.h>
#import <pthread.h>
#import <stdatomic.h>
#import <stdint.h>
#import <string.h>

// Temporary M1 bring-up logging (non-RT paths only — never log from DoIOOperation).
#define VPD_LOG(...) do { \
    os_log(OS_LOG_DEFAULT, "VibePadAudio: " __VA_ARGS__); \
} while (0)

//==================================================================================================
#pragma mark -
#pragma mark Constants
//==================================================================================================

// Object IDs (kAudioObjectPlugInObject == 1 is mandated for the plug-in object).
enum
{
    kVPDObjectIDDevice       = 2,
    kVPDObjectIDOutputStream = 3,
    kVPDObjectIDInputStream  = 4
};

// Identity.
#define kVPDBundleID            CFSTR("com.xiaoxi.vibepad.audio.driver")
#define kVPDPlugInName          CFSTR("VibePadAudio")
#define kVPDManufacturerName    CFSTR("xiaoxi668v")
#define kVPDDeviceName          CFSTR("VibePad Microphone")
#define kVPDDeviceUID           CFSTR("com.xiaoxi.vibepad.audio.device")
#define kVPDDeviceModelUID      CFSTR("com.xiaoxi.vibepad.audio.model")
#define kVPDOutputStreamName    CFSTR("VibePad Microphone Output")
#define kVPDInputStreamName     CFSTR("VibePad Microphone Input")

// Format: 32-bit float, stereo, interleaved. Sample rate is switchable between 44.1k and 48k.
enum
{
    kVPDChannelCount     = 2,
    kVPDBytesPerFrame    = kVPDChannelCount * (UInt32)sizeof(Float32), // 8, interleaved
    kVPDRingCapacityFrames = 16384,  // power of two; ~341 ms at 48 kHz
    kVPDRingCapacityBytes  = kVPDRingCapacityFrames * kVPDBytesPerFrame
};

// If the output-side sample time jumps by more than this many frames, the producer is assumed
// to have restarted: the ring is dropped and re-anchored instead of glitching.
#define kVPDRingResyncThresholdFrames (kVPDRingCapacityFrames / 2)

// Frames between successive zero time stamps. The HAL requires this to be at least 10923.
#define kVPDZeroTimeStampPeriodFrames 16384

static const Float64 kVPDSampleRate44100 = 44100.0;
static const Float64 kVPDSampleRate48000 = 48000.0;

//==================================================================================================
#pragma mark -
#pragma mark Driver State
//==================================================================================================

// All mutable driver state lives in one statically allocated struct. The plug-in object is never
// destroyed; coreaudiod keeps the bundle loaded for the lifetime of the process.
typedef struct
{
    // COM reference count (the object is static; Release never frees).
    _Atomic(ULONG) refCount;

    // Host interface handed to us in Initialize; valid for the lifetime of the plug-in.
    AudioServerPlugInHostRef _Nullable host;

    // Nominal sample rate (44.1k or 48k). Only touched off the IO thread.
    _Atomic(Float64) sampleRate;

    // Host-clock ticks per frame at the current sample rate (for GetZeroTimeStamp).
    _Atomic(Float64) hostTicksPerFrame;

    // IO bookkeeping. ioClientCount is guarded by gVPDMutex; ioRunning is atomic for readers.
    UInt32 ioClientCount;
    _Atomic(UInt32) ioRunning;

    // Clock anchor: host time at which the device's sample clock was last zeroed (IO start).
    _Atomic(UInt64) clockAnchorHostTime;
    _Atomic(UInt64) zeroTimeStampSeed;

    // Loopback ring buffer. Byte counters are monotonic and always frame-aligned; the storage
    // offset is (counter & (capacity - 1)). Single writer (output IO op) / single reader
    // (input IO op); coreaudiod serializes both on the device's IO thread, and the atomics
    // below keep the counters coherent even if that ever changes.
    UInt8 ringStorage[kVPDRingCapacityBytes];
    _Atomic(UInt64) ringBytesWritten;   // producer counter (output side)
    _Atomic(UInt64) ringBytesRead;      // consumer counter (input side)

    // Bring-up diagnostics (M2); RT-safe atomic counters, reported from StopIO.
    _Atomic(UInt64) dbgMixOutputCalls;
    _Atomic(UInt64) dbgReadInputCalls;
    _Atomic(UInt64) dbgMixOutputBytes;

    // Mapping between the output side's sample-time line and the ring producer counter, used to
    // detect producer restarts. Only touched by the output IO op.
    _Atomic(int) ringAnchorValid;
    _Atomic(UInt64) ringAnchorSampleTime;   // Float64 bits are stored as int64-safe value
    _Atomic(UInt64) ringAnchorFrameCounter;
} VPDState;

static VPDState gVPD;
static pthread_mutex_t gVPDMutex = PTHREAD_MUTEX_INITIALIZER;

// Defined at the bottom of this file; referenced by the CFPlugIn factory.
extern AudioServerPlugInDriverRef gAudioServerPlugInDriverRef;

//==================================================================================================
#pragma mark -
#pragma mark Ring Buffer (real-time safe: no allocation, no locks, no logging)
//==================================================================================================

// Copies src into the ring at the current write position, wrapping at the capacity boundary.
static inline void VPD_RingCopyIn(const UInt8* _Nonnull src, UInt64 byteCount)
{
    UInt64 written = atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_relaxed);
    UInt64 pos = written & (UInt64)(kVPDRingCapacityBytes - 1);
    UInt64 first = kVPDRingCapacityBytes - pos;
    if (first > byteCount) { first = byteCount; }
    memcpy(gVPD.ringStorage + pos, src, first);
    if (first < byteCount)
    {
        memcpy(gVPD.ringStorage, src + first, byteCount - first);
    }
}

// Output side: consume one IO cycle of interleaved samples into the ring. The HAL hands us a
// raw sample buffer (not an AudioBufferList): inIOBufferFrameSize * kVPDBytesPerFrame bytes.
// Full buffers overwrite the oldest data; the consumer counter is advanced to make room.
static void VPD_RingWrite(const UInt8* _Nonnull src, UInt64 totalBytes,
                          const AudioServerPlugInIOCycleInfo* _Nullable cycleInfo)
{
    // Whole frames only; a trailing partial frame (should never happen) is dropped so the byte
    // counters stay frame-aligned.
    totalBytes -= totalBytes % kVPDBytesPerFrame;
    if (totalBytes == 0) { return; }

    UInt64 written = atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_relaxed);

    // Anchor / resync against the output sample-time line so a restarted producer (helper app
    // relaunch, AudioQueue reset) does not smear stale audio across the seam.
    if (cycleInfo != NULL && (cycleInfo->mOutputTime.mFlags & kAudioTimeStampSampleTimeValid) != 0)
    {
        int64_t sampleTime = (int64_t)cycleInfo->mOutputTime.mSampleTime;
        if (atomic_load_explicit(&gVPD.ringAnchorValid, memory_order_relaxed) == 0)
        {
            atomic_store_explicit(&gVPD.ringAnchorSampleTime, (UInt64)sampleTime, memory_order_relaxed);
            atomic_store_explicit(&gVPD.ringAnchorFrameCounter, written / kVPDBytesPerFrame, memory_order_relaxed);
            atomic_store_explicit(&gVPD.ringAnchorValid, 1, memory_order_relaxed);
        }
        else
        {
            int64_t anchorTime = (int64_t)atomic_load_explicit(&gVPD.ringAnchorSampleTime, memory_order_relaxed);
            int64_t anchorFrame = (int64_t)atomic_load_explicit(&gVPD.ringAnchorFrameCounter, memory_order_relaxed);
            int64_t expected = anchorTime + ((int64_t)(written / kVPDBytesPerFrame) - anchorFrame);
            int64_t drift = sampleTime - expected;
            if (drift > kVPDRingResyncThresholdFrames || drift < -(int64_t)kVPDRingResyncThresholdFrames)
            {
                // Producer jumped: drop everything buffered and re-anchor at the new timeline.
                atomic_store_explicit(&gVPD.ringBytesRead, written, memory_order_release);
                atomic_store_explicit(&gVPD.ringAnchorSampleTime, (UInt64)sampleTime, memory_order_relaxed);
                atomic_store_explicit(&gVPD.ringAnchorFrameCounter, written / kVPDBytesPerFrame, memory_order_relaxed);
            }
        }
    }

    UInt64 read = atomic_load_explicit(&gVPD.ringBytesRead, memory_order_acquire);
    UInt64 available = written - read;
    if (available > (UInt64)kVPDRingCapacityBytes)
    {
        // Defensive: the consumer fell impossibly far behind (e.g. counters were reset). Clamp.
        read = written - kVPDRingCapacityBytes;
        atomic_store_explicit(&gVPD.ringBytesRead, read, memory_order_release);
        available = kVPDRingCapacityBytes;
    }

    // A single IO cycle larger than the ring keeps only its tail. Both operands are multiples of
    // the frame size, so the skip stays frame-aligned.
    UInt64 skipHead = 0;
    if (totalBytes > (UInt64)kVPDRingCapacityBytes)
    {
        skipHead = totalBytes - kVPDRingCapacityBytes;
    }
    UInt64 payloadBytes = totalBytes - skipHead;

    // Overwrite the oldest data when the ring would overflow.
    if (available + payloadBytes > (UInt64)kVPDRingCapacityBytes)
    {
        UInt64 drop = available + payloadBytes - kVPDRingCapacityBytes;
        atomic_store_explicit(&gVPD.ringBytesRead, read + drop, memory_order_release);
    }

    VPD_RingCopyIn(src + skipHead, payloadBytes);
    atomic_store_explicit(&gVPD.ringBytesWritten, written + payloadBytes, memory_order_release);
}

// Input side: fill one IO cycle's worth of interleaved samples from the ring. Reads past the
// producer are zero-filled (silence) so clients always get exactly the bytes they asked for.
static void VPD_RingRead(UInt8* _Nonnull dst, UInt64 size)
{
    UInt64 written = atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_acquire);
    UInt64 read = atomic_load_explicit(&gVPD.ringBytesRead, memory_order_relaxed);

    int64_t available = (int64_t)(written - read);
    if (available < 0) { available = 0; }
    if (available > (int64_t)kVPDRingCapacityBytes)
    {
        // Defensive clamp, mirrors the writer's.
        read = written - kVPDRingCapacityBytes;
        available = kVPDRingCapacityBytes;
    }

    UInt64 chunk = size < (UInt64)available ? size : (UInt64)available;
    if (chunk > 0)
    {
        UInt64 pos = read & (UInt64)(kVPDRingCapacityBytes - 1);
        UInt64 first = kVPDRingCapacityBytes - pos;
        if (first > chunk) { first = chunk; }
        memcpy(dst, gVPD.ringStorage + pos, first);
        if (first < chunk)
        {
            memcpy(dst + first, gVPD.ringStorage, chunk - first);
        }
    }
    if (chunk < size)
    {
        memset(dst + chunk, 0, size - chunk);
    }
    atomic_store_explicit(&gVPD.ringBytesRead, read + chunk, memory_order_release);
}

// Resets the loopback and re-zeros the device clock. Called with gVPDMutex held, never from the
// IO thread's steady state.
static void VPD_ResetRingAndClock(void)
{
    atomic_store_explicit(&gVPD.ringBytesRead, 0, memory_order_relaxed);
    atomic_store_explicit(&gVPD.ringBytesWritten, 0, memory_order_relaxed);
    atomic_store_explicit(&gVPD.ringAnchorValid, 0, memory_order_relaxed);
    atomic_store_explicit(&gVPD.clockAnchorHostTime, AudioGetCurrentHostTime(), memory_order_relaxed);
}

//==================================================================================================
#pragma mark -
#pragma mark Helpers (non-RT paths only)
//==================================================================================================

static Boolean VPD_IsValidObjectID(AudioObjectID objectID)
{
    return objectID == kAudioObjectPlugInObject
        || objectID == kVPDObjectIDDevice
        || objectID == kVPDObjectIDOutputStream
        || objectID == kVPDObjectIDInputStream;
}

static Boolean VPD_IsSupportedSampleRate(Float64 rate)
{
    return rate == kVPDSampleRate44100 || rate == kVPDSampleRate48000;
}

static void VPD_UpdateHostTicksPerFrame(void)
{
    // seconds = ticks * numer / denom / 1e9  →  ticksPerFrame = 1e9 * denom / (numer * rate)
    static mach_timebase_info_data_t sTimebase;
    if (sTimebase.denom == 0) { mach_timebase_info(&sTimebase); }
    Float64 rate = atomic_load_explicit(&gVPD.sampleRate, memory_order_relaxed);
    Float64 ticksPerSecond = 1.0e9 * (Float64)sTimebase.denom / (Float64)sTimebase.numer;
    atomic_store_explicit(&gVPD.hostTicksPerFrame, ticksPerSecond / rate, memory_order_relaxed);
}

static AudioStreamBasicDescription VPD_CurrentFormat(void)
{
    AudioStreamBasicDescription format;
    memset(&format, 0, sizeof(format));
    format.mSampleRate = atomic_load_explicit(&gVPD.sampleRate, memory_order_relaxed);
    format.mFormatID = kAudioFormatLinearPCM;
    format.mFormatFlags = kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked;
    format.mBytesPerPacket = kVPDBytesPerFrame;
    format.mFramesPerPacket = 1;
    format.mBytesPerFrame = kVPDBytesPerFrame;
    format.mChannelsPerFrame = kVPDChannelCount;
    format.mBitsPerChannel = 32;
    return format;
}

// Copies fixed-size property data into the caller's buffer.
static OSStatus VPD_CopyOut(const void* _Nonnull source, UInt32 sourceSize,
                            UInt32 inDataSize, UInt32* _Nullable outDataSize, void* _Nonnull outData)
{
    if (inDataSize < sourceSize) { return kAudioHardwareBadPropertySizeError; }
    memcpy(outData, source, sourceSize);
    if (outDataSize != NULL) { *outDataSize = sourceSize; }
    return noErr;
}

static OSStatus VPD_CopyOutUInt32(UInt32 value, UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    return VPD_CopyOut(&value, sizeof(value), inDataSize, outDataSize, outData);
}

static OSStatus VPD_CopyOutFloat64(Float64 value, UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    return VPD_CopyOut(&value, sizeof(value), inDataSize, outDataSize, outData);
}

// CF objects are handed out retained; the caller owns the reference.
static OSStatus VPD_CopyOutCFString(CFStringRef value, UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    CFStringRef retained = (CFStringRef)CFRetain(value);
    return VPD_CopyOut(&retained, sizeof(CFStringRef), inDataSize, outDataSize, outData);
}

static void VPD_NotifyHost(AudioObjectID objectID, UInt32 count, const AudioObjectPropertyAddress* addresses)
{
    // The host pointer is assigned once in Initialize before any client traffic exists, so a
    // plain read is sufficient here.
    AudioServerPlugInHostRef host = gVPD.host;
    if (host != NULL && host->PropertiesChanged != NULL)
    {
        host->PropertiesChanged(host, objectID, count, addresses);
    }
}

static void VPD_NotifyRunningChanged(void)
{
    AudioObjectPropertyAddress addresses[] =
    {
        { kAudioDevicePropertyDeviceIsRunning, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain },
        { kAudioDevicePropertyDeviceIsRunningSomewhere, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain }
    };
    VPD_NotifyHost(kVPDObjectIDDevice, 2, addresses);
}

static void VPD_NotifyFormatChanged(void)
{
    AudioObjectPropertyAddress deviceAddress =
        { kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain };
    VPD_NotifyHost(kVPDObjectIDDevice, 1, &deviceAddress);

    AudioObjectPropertyAddress streamAddresses[] =
    {
        { kAudioStreamPropertyVirtualFormat, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain },
        { kAudioStreamPropertyPhysicalFormat, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMain }
    };
    VPD_NotifyHost(kVPDObjectIDOutputStream, 2, streamAddresses);
    VPD_NotifyHost(kVPDObjectIDInputStream, 2, streamAddresses);
}

//==================================================================================================
#pragma mark -
#pragma mark PlugIn Object Properties
//==================================================================================================

static Boolean VPD_PlugInHasProperty(const AudioObjectPropertyAddress* address)
{
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyManufacturer:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
        case kAudioPlugInPropertyBundleID:
        case kAudioPlugInPropertyDeviceList:
        case kAudioPlugInPropertyTranslateUIDToDevice:
        case kAudioPlugInPropertyBoxList:
        case kAudioPlugInPropertyClockDeviceList:
            return true;
        default:
            return false;
    }
}

static Boolean VPD_PlugInIsPropertySettable(const AudioObjectPropertyAddress* address)
{
    switch (address->mSelector)
    {
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
            return true;
        default:
            return false;
    }
}

static OSStatus VPD_PlugInGetPropertyDataSize(const AudioObjectPropertyAddress* address,
                                              UInt32 qualifierDataSize, const void* qualifierData,
                                              UInt32* outDataSize)
{
    (void)qualifierDataSize; (void)qualifierData;
    VPD_LOG("plugin GetPropertyDataSize: selector=%c%c%c%c scope=%c%c%c%c",
            (char)(address->mSelector >> 24), (char)(address->mSelector >> 16),
            (char)(address->mSelector >> 8), (char)(address->mSelector),
            (char)(address->mScope >> 24), (char)(address->mScope >> 16),
            (char)(address->mScope >> 8), (char)(address->mScope));
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
            *outDataSize = sizeof(AudioClassID);
            return noErr;
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyManufacturer:
        case kAudioPlugInPropertyBundleID:
            *outDataSize = sizeof(CFStringRef);
            return noErr;
        case kAudioObjectPropertyOwnedObjects:
            if (address->mScope != kAudioObjectPropertyScopeGlobal)
            {
                *outDataSize = 0;
                return noErr;
            }
            *outDataSize = sizeof(AudioObjectID);
            return noErr;
        case kAudioPlugInPropertyDeviceList:
            *outDataSize = sizeof(AudioObjectID);
            return noErr;
        case kAudioPlugInPropertyTranslateUIDToDevice:
            *outDataSize = sizeof(AudioObjectID);
            return noErr;
        case kAudioPlugInPropertyBoxList:
        case kAudioPlugInPropertyClockDeviceList:
            *outDataSize = 0;
            return noErr;
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
            *outDataSize = sizeof(AudioObjectPropertyAddress);
            return noErr;
        default:
            return kAudioHardwareUnknownPropertyError;
    }
}

static OSStatus VPD_PlugInGetPropertyData(const AudioObjectPropertyAddress* address,
                                          UInt32 qualifierDataSize, const void* qualifierData,
                                          UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    (void)qualifierDataSize; (void)qualifierData;
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
            return VPD_CopyOutUInt32(kAudioObjectClassID, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyClass:
            return VPD_CopyOutUInt32(kAudioPlugInClassID, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyOwner:
            return VPD_CopyOutUInt32(kAudioObjectUnknown, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyName:
            return VPD_CopyOutCFString(kVPDPlugInName, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyManufacturer:
            return VPD_CopyOutCFString(kVPDManufacturerName, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyOwnedObjects:
        case kAudioPlugInPropertyDeviceList:
        {
            if (address->mSelector == kAudioObjectPropertyOwnedObjects
                && address->mScope != kAudioObjectPropertyScopeGlobal)
            {
                if (outDataSize != NULL) { *outDataSize = 0; }
                return noErr;
            }
            AudioObjectID device = kVPDObjectIDDevice;
            return VPD_CopyOut(&device, sizeof(device), inDataSize, outDataSize, outData);
        }
        case kAudioPlugInPropertyTranslateUIDToDevice:
        {
            AudioObjectID result = kAudioObjectUnknown;
            if (qualifierDataSize == sizeof(CFStringRef) && qualifierData != NULL)
            {
                CFStringRef uid = *(CFStringRef _Nonnull *)qualifierData;
                if (uid != NULL && CFStringCompare(uid, kVPDDeviceUID, 0) == kCFCompareEqualTo)
                {
                    result = kVPDObjectIDDevice;
                }
            }
            return VPD_CopyOut(&result, sizeof(result), inDataSize, outDataSize, outData);
        }
        case kAudioPlugInPropertyBoxList:
        case kAudioPlugInPropertyClockDeviceList:
            if (outDataSize != NULL) { *outDataSize = 0; }
            return noErr;
        default:
            return kAudioHardwareUnknownPropertyError;
    }
}

//==================================================================================================
#pragma mark -
#pragma mark Device Properties
//==================================================================================================

static Boolean VPD_DeviceHasProperty(const AudioObjectPropertyAddress* address)
{
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyManufacturer:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
        case kAudioObjectPropertyControlList:
        case kAudioDevicePropertyDeviceUID:
        case kAudioDevicePropertyModelUID:
        case kAudioDevicePropertyTransportType:
        case kAudioDevicePropertyRelatedDevices:
        case kAudioDevicePropertyClockDomain:
        case kAudioDevicePropertyDeviceIsAlive:
        case kAudioDevicePropertyDeviceIsRunning:
        case kAudioDevicePropertyDeviceIsRunningSomewhere:
        case kAudioDevicePropertyDeviceCanBeDefaultDevice:
        case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice:
        case kAudioDevicePropertyLatency:
        case kAudioDevicePropertyStreams:
        case kAudioDevicePropertyStreamConfiguration:
        case kAudioDevicePropertySafetyOffset:
        case kAudioDevicePropertyNominalSampleRate:
        case kAudioDevicePropertyAvailableNominalSampleRates:
        case kAudioDevicePropertyIsHidden:
        case kAudioDevicePropertyPreferredChannelsForStereo:
        case kAudioDevicePropertyPreferredChannelLayout:
        case kAudioDevicePropertyZeroTimeStampPeriod:
        case kAudioDevicePropertyClockIsStable:
            return true;
        default:
            return false;
    }
}

static Boolean VPD_DeviceIsPropertySettable(const AudioObjectPropertyAddress* address)
{
    switch (address->mSelector)
    {
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
        case kAudioDevicePropertyNominalSampleRate:
            return true;
        default:
            return false;
    }
}

// Streams owned by the device for a given scope. outStreams may be NULL to only get the count.
static UInt32 VPD_DeviceStreamsForScope(AudioObjectPropertyScope scope, AudioObjectID* outStreams)
{
    UInt32 count = 0;
    if (scope == kAudioObjectPropertyScopeGlobal || scope == kAudioObjectPropertyScopeOutput)
    {
        if (outStreams != NULL) { outStreams[count] = kVPDObjectIDOutputStream; }
        ++count;
    }
    if (scope == kAudioObjectPropertyScopeGlobal || scope == kAudioObjectPropertyScopeInput)
    {
        if (outStreams != NULL) { outStreams[count] = kVPDObjectIDInputStream; }
        ++count;
    }
    return count;
}

static OSStatus VPD_DeviceGetPropertyDataSize(const AudioObjectPropertyAddress* address,
                                              UInt32 qualifierDataSize, const void* qualifierData,
                                              UInt32* outDataSize)
{
    (void)qualifierDataSize; (void)qualifierData;
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioDevicePropertyTransportType:
        case kAudioDevicePropertyClockDomain:
        case kAudioDevicePropertyDeviceIsAlive:
        case kAudioDevicePropertyDeviceIsRunning:
        case kAudioDevicePropertyDeviceIsRunningSomewhere:
        case kAudioDevicePropertyDeviceCanBeDefaultDevice:
        case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice:
        case kAudioDevicePropertyLatency:
        case kAudioDevicePropertySafetyOffset:
        case kAudioDevicePropertyIsHidden:
        case kAudioDevicePropertyZeroTimeStampPeriod:
        case kAudioDevicePropertyClockIsStable:
            *outDataSize = sizeof(UInt32);
            return noErr;
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyManufacturer:
        case kAudioDevicePropertyDeviceUID:
        case kAudioDevicePropertyModelUID:
            *outDataSize = sizeof(CFStringRef);
            return noErr;
        case kAudioObjectPropertyOwnedObjects:
            if (address->mScope == kAudioObjectPropertyScopeGlobal)
            {
                *outDataSize = 2 * sizeof(AudioObjectID);
                return noErr;
            }
            *outDataSize = VPD_DeviceStreamsForScope(address->mScope, NULL) * sizeof(AudioObjectID);
            return noErr;
        case kAudioObjectPropertyControlList:
            *outDataSize = 0;
            return noErr;
        case kAudioDevicePropertyRelatedDevices:
            *outDataSize = sizeof(AudioObjectID);
            return noErr;
        case kAudioDevicePropertyStreams:
            *outDataSize = VPD_DeviceStreamsForScope(address->mScope, NULL) * sizeof(AudioObjectID);
            return noErr;
        case kAudioDevicePropertyStreamConfiguration:
            // One stream in the scope, one interleaved buffer of 2 channels.
            if (address->mScope == kAudioObjectPropertyScopeGlobal)
            {
                *outDataSize = 0;
                return noErr;
            }
            *outDataSize = sizeof(AudioBufferList);
            return noErr;
        case kAudioDevicePropertyNominalSampleRate:
            *outDataSize = sizeof(Float64);
            return noErr;
        case kAudioDevicePropertyAvailableNominalSampleRates:
            *outDataSize = 2 * sizeof(AudioValueRange);
            return noErr;
        case kAudioDevicePropertyPreferredChannelsForStereo:
            *outDataSize = 2 * sizeof(UInt32);
            return noErr;
        case kAudioDevicePropertyPreferredChannelLayout:
            *outDataSize = (UInt32)offsetof(AudioChannelLayout, mChannelDescriptions);
            return noErr;
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
            *outDataSize = sizeof(AudioObjectPropertyAddress);
            return noErr;
        default:
            return kAudioHardwareUnknownPropertyError;
    }
}

static OSStatus VPD_DeviceGetPropertyData(const AudioObjectPropertyAddress* address,
                                          UInt32 qualifierDataSize, const void* qualifierData,
                                          UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    (void)qualifierDataSize; (void)qualifierData;
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
            return VPD_CopyOutUInt32(kAudioObjectClassID, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyClass:
            return VPD_CopyOutUInt32(kAudioDeviceClassID, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyOwner:
            return VPD_CopyOutUInt32(kAudioObjectPlugInObject, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyName:
            return VPD_CopyOutCFString(kVPDDeviceName, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyManufacturer:
            return VPD_CopyOutCFString(kVPDManufacturerName, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyOwnedObjects:
        {
            if (address->mScope == kAudioObjectPropertyScopeGlobal)
            {
                AudioObjectID owned[] = { kVPDObjectIDOutputStream, kVPDObjectIDInputStream };
                return VPD_CopyOut(owned, sizeof(owned), inDataSize, outDataSize, outData);
            }
            AudioObjectID streams[2];
            UInt32 count = VPD_DeviceStreamsForScope(address->mScope, streams);
            return VPD_CopyOut(streams, count * (UInt32)sizeof(AudioObjectID), inDataSize, outDataSize, outData);
        }
        case kAudioObjectPropertyControlList:
            if (outDataSize != NULL) { *outDataSize = 0; }
            return noErr;
        case kAudioDevicePropertyDeviceUID:
            return VPD_CopyOutCFString(kVPDDeviceUID, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyModelUID:
            return VPD_CopyOutCFString(kVPDDeviceModelUID, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyTransportType:
            return VPD_CopyOutUInt32(kAudioDeviceTransportTypeVirtual, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyRelatedDevices:
        {
            AudioObjectID self = kVPDObjectIDDevice;
            return VPD_CopyOut(&self, sizeof(self), inDataSize, outDataSize, outData);
        }
        case kAudioDevicePropertyClockDomain:
            return VPD_CopyOutUInt32(0, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyDeviceIsAlive:
            return VPD_CopyOutUInt32(1, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyDeviceIsRunning:
        case kAudioDevicePropertyDeviceIsRunningSomewhere:
            return VPD_CopyOutUInt32(atomic_load_explicit(&gVPD.ioRunning, memory_order_acquire),
                                     inDataSize, outDataSize, outData);
        case kAudioDevicePropertyDeviceCanBeDefaultDevice:
            return VPD_CopyOutUInt32(1, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice:
            return VPD_CopyOutUInt32(0, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyLatency:
        case kAudioDevicePropertySafetyOffset:
            return VPD_CopyOutUInt32(0, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyStreams:
        {
            AudioObjectID streams[2];
            UInt32 count = VPD_DeviceStreamsForScope(address->mScope, streams);
            return VPD_CopyOut(streams, count * (UInt32)sizeof(AudioObjectID), inDataSize, outDataSize, outData);
        }
        case kAudioDevicePropertyStreamConfiguration:
        {
            if (address->mScope == kAudioObjectPropertyScopeGlobal)
            {
                if (outDataSize != NULL) { *outDataSize = 0; }
                return noErr;
            }
            if (inDataSize < sizeof(AudioBufferList)) { return kAudioHardwareBadPropertySizeError; }
            AudioBufferList* configuration = (AudioBufferList*)outData;
            configuration->mNumberBuffers = 1;
            configuration->mBuffers[0].mNumberChannels = kVPDChannelCount;
            configuration->mBuffers[0].mDataByteSize = 0;
            configuration->mBuffers[0].mData = NULL;
            if (outDataSize != NULL) { *outDataSize = sizeof(AudioBufferList); }
            return noErr;
        }
        case kAudioDevicePropertyNominalSampleRate:
            return VPD_CopyOutFloat64(atomic_load_explicit(&gVPD.sampleRate, memory_order_relaxed),
                                      inDataSize, outDataSize, outData);
        case kAudioDevicePropertyAvailableNominalSampleRates:
        {
            AudioValueRange ranges[] =
            {
                { kVPDSampleRate44100, kVPDSampleRate44100 },
                { kVPDSampleRate48000, kVPDSampleRate48000 }
            };
            return VPD_CopyOut(ranges, sizeof(ranges), inDataSize, outDataSize, outData);
        }
        case kAudioDevicePropertyIsHidden:
            return VPD_CopyOutUInt32(0, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyPreferredChannelsForStereo:
        {
            UInt32 pair[] = { 1, 2 }; // channel numbers are 1-based
            return VPD_CopyOut(pair, sizeof(pair), inDataSize, outDataSize, outData);
        }
        case kAudioDevicePropertyPreferredChannelLayout:
        {
            AudioChannelLayout layout;
            memset(&layout, 0, sizeof(layout));
            layout.mChannelLayoutTag = kAudioChannelLayoutTag_Stereo;
            return VPD_CopyOut(&layout, (UInt32)offsetof(AudioChannelLayout, mChannelDescriptions),
                               inDataSize, outDataSize, outData);
        }
        case kAudioDevicePropertyZeroTimeStampPeriod:
            return VPD_CopyOutUInt32(kVPDZeroTimeStampPeriodFrames, inDataSize, outDataSize, outData);
        case kAudioDevicePropertyClockIsStable:
            // The device clock is derived directly from the host clock.
            return VPD_CopyOutUInt32(1, inDataSize, outDataSize, outData);
        default:
            return kAudioHardwareUnknownPropertyError;
    }
}

// Applies a new nominal sample rate. Resets the loopback and clock, then notifies the host.
// Returns noErr when the rate was accepted (including no-op sets to the current rate).
static OSStatus VPD_DeviceSetNominalSampleRate(Float64 newRate)
{
    if (!VPD_IsSupportedSampleRate(newRate)) { return kAudioHardwareIllegalOperationError; }
    Float64 oldRate = atomic_load_explicit(&gVPD.sampleRate, memory_order_relaxed);
    if (newRate == oldRate) { return noErr; }

    pthread_mutex_lock(&gVPDMutex);
    atomic_store_explicit(&gVPD.sampleRate, newRate, memory_order_relaxed);
    VPD_UpdateHostTicksPerFrame();
    VPD_ResetRingAndClock();
    pthread_mutex_unlock(&gVPDMutex);

    VPD_NotifyFormatChanged();
    return noErr;
}

//==================================================================================================
#pragma mark -
#pragma mark Stream Properties
//==================================================================================================

static Boolean VPD_StreamHasProperty(const AudioObjectPropertyAddress* address)
{
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
        case kAudioStreamPropertyIsActive:
        case kAudioStreamPropertyDirection:
        case kAudioStreamPropertyTerminalType:
        case kAudioStreamPropertyStartingChannel:
        case kAudioStreamPropertyLatency:
        case kAudioStreamPropertyVirtualFormat:
        case kAudioStreamPropertyAvailableVirtualFormats:
        case kAudioStreamPropertyPhysicalFormat:
        case kAudioStreamPropertyAvailablePhysicalFormats:
            return true;
        default:
            return false;
    }
}

static Boolean VPD_StreamIsPropertySettable(const AudioObjectPropertyAddress* address)
{
    switch (address->mSelector)
    {
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
        case kAudioStreamPropertyVirtualFormat:
            return true;
        default:
            return false;
    }
}

static OSStatus VPD_StreamGetPropertyDataSize(const AudioObjectPropertyAddress* address,
                                              UInt32 qualifierDataSize, const void* qualifierData,
                                              UInt32* outDataSize)
{
    (void)qualifierDataSize; (void)qualifierData;
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioStreamPropertyIsActive:
        case kAudioStreamPropertyDirection:
        case kAudioStreamPropertyTerminalType:
        case kAudioStreamPropertyStartingChannel:
        case kAudioStreamPropertyLatency:
            *outDataSize = sizeof(UInt32);
            return noErr;
        case kAudioObjectPropertyName:
            *outDataSize = sizeof(CFStringRef);
            return noErr;
        case kAudioStreamPropertyVirtualFormat:
        case kAudioStreamPropertyPhysicalFormat:
            *outDataSize = sizeof(AudioStreamBasicDescription);
            return noErr;
        case kAudioStreamPropertyAvailableVirtualFormats:
        case kAudioStreamPropertyAvailablePhysicalFormats:
            *outDataSize = 2 * sizeof(AudioStreamRangedDescription);
            return noErr;
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
            *outDataSize = sizeof(AudioObjectPropertyAddress);
            return noErr;
        default:
            return kAudioHardwareUnknownPropertyError;
    }
}

static OSStatus VPD_StreamGetPropertyData(AudioObjectID streamID,
                                          const AudioObjectPropertyAddress* address,
                                          UInt32 qualifierDataSize, const void* qualifierData,
                                          UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    (void)qualifierDataSize; (void)qualifierData;
    switch (address->mSelector)
    {
        case kAudioObjectPropertyBaseClass:
            return VPD_CopyOutUInt32(kAudioObjectClassID, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyClass:
            return VPD_CopyOutUInt32(kAudioStreamClassID, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyOwner:
            return VPD_CopyOutUInt32(kVPDObjectIDDevice, inDataSize, outDataSize, outData);
        case kAudioObjectPropertyName:
            return VPD_CopyOutCFString(streamID == kVPDObjectIDInputStream
                                       ? kVPDInputStreamName : kVPDOutputStreamName,
                                       inDataSize, outDataSize, outData);
        case kAudioStreamPropertyIsActive:
            return VPD_CopyOutUInt32(1, inDataSize, outDataSize, outData);
        case kAudioStreamPropertyDirection:
            // 0 == output (toward the device), 1 == input (from the device).
            return VPD_CopyOutUInt32(streamID == kVPDObjectIDInputStream ? 1 : 0,
                                     inDataSize, outDataSize, outData);
        case kAudioStreamPropertyTerminalType:
            return VPD_CopyOutUInt32(streamID == kVPDObjectIDInputStream
                                     ? kAudioStreamTerminalTypeMicrophone
                                     : kAudioStreamTerminalTypeSpeaker,
                                     inDataSize, outDataSize, outData);
        case kAudioStreamPropertyStartingChannel:
            return VPD_CopyOutUInt32(1, inDataSize, outDataSize, outData);
        case kAudioStreamPropertyLatency:
            return VPD_CopyOutUInt32(0, inDataSize, outDataSize, outData);
        case kAudioStreamPropertyVirtualFormat:
        case kAudioStreamPropertyPhysicalFormat:
        {
            AudioStreamBasicDescription format = VPD_CurrentFormat();
            return VPD_CopyOut(&format, sizeof(format), inDataSize, outDataSize, outData);
        }
        case kAudioStreamPropertyAvailableVirtualFormats:
        case kAudioStreamPropertyAvailablePhysicalFormats:
        {
            AudioStreamRangedDescription descriptions[2];
            memset(descriptions, 0, sizeof(descriptions));
            for (UInt32 i = 0; i < 2; ++i)
            {
                Float64 rate = i == 0 ? kVPDSampleRate44100 : kVPDSampleRate48000;
                AudioStreamBasicDescription format = VPD_CurrentFormat();
                format.mSampleRate = rate;
                descriptions[i].mFormat = format;
                descriptions[i].mSampleRateRange.mMinimum = rate;
                descriptions[i].mSampleRateRange.mMaximum = rate;
            }
            return VPD_CopyOut(descriptions, sizeof(descriptions), inDataSize, outDataSize, outData);
        }
        default:
            return kAudioHardwareUnknownPropertyError;
    }
}

// Accepts only the one canonical stream format at a supported sample rate.
static OSStatus VPD_StreamSetVirtualFormat(const AudioStreamBasicDescription* format)
{
    AudioStreamBasicDescription canonical = VPD_CurrentFormat();
    canonical.mSampleRate = format->mSampleRate;
    if (format->mFormatID != canonical.mFormatID
        || (format->mFormatFlags & (kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked))
               != (kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked)
        || format->mBytesPerPacket != canonical.mBytesPerPacket
        || format->mFramesPerPacket != canonical.mFramesPerPacket
        || format->mBytesPerFrame != canonical.mBytesPerFrame
        || format->mChannelsPerFrame != canonical.mChannelsPerFrame
        || format->mBitsPerChannel != canonical.mBitsPerChannel)
    {
        return kAudioHardwareIllegalOperationError;
    }
    return VPD_DeviceSetNominalSampleRate(format->mSampleRate);
}

//==================================================================================================
#pragma mark -
#pragma mark COM / CFPlugIn Entry Points
//==================================================================================================

static HRESULT VPD_QueryInterface(void* inDriver, REFIID inUUID, LPVOID* outInterface)
{
    (void)inDriver;
    if (outInterface == NULL) { return E_POINTER; }

    // REFIID arrives as CFUUIDBytes by value; wrap it to compare against the known UUIDs.
    CFUUIDRef requested = CFUUIDCreateFromUUIDBytes(kCFAllocatorDefault, inUUID);
    if (requested == NULL)
    {
        *outInterface = NULL;
        return E_NOINTERFACE;
    }
    Boolean matches = CFEqual(requested, IUnknownUUID)
        || CFEqual(requested, kAudioServerPlugInDriverInterfaceUUID);
    CFRelease(requested);
    VPD_LOG("QueryInterface: match=%d", (int)matches);

    if (matches)
    {
        // Single object, single interface: both UUIDs return the driver object itself.
        atomic_fetch_add_explicit(&gVPD.refCount, 1, memory_order_relaxed);
        *outInterface = gAudioServerPlugInDriverRef;
        return S_OK;
    }
    *outInterface = NULL;
    return E_NOINTERFACE;
}

static ULONG VPD_AddRef(void* inDriver)
{
    (void)inDriver;
    return atomic_fetch_add_explicit(&gVPD.refCount, 1, memory_order_relaxed) + 1;
}

static ULONG VPD_Release(void* inDriver)
{
    (void)inDriver;
    ULONG previous = atomic_load_explicit(&gVPD.refCount, memory_order_relaxed);
    while (previous > 0)
    {
        if (atomic_compare_exchange_weak_explicit(&gVPD.refCount, &previous, previous - 1,
                                                  memory_order_relaxed, memory_order_relaxed))
        {
            return previous - 1;
        }
    }
    // The object is statically allocated and is never destroyed.
    return 0;
}

// CFPlugIn factory: registered in Info.plist under CFPlugInFactories for the
// kAudioServerPlugInTypeUUID type.
static void* VPD_Factory(CFAllocatorRef allocator, CFUUIDRef typeUUID)
{
    (void)allocator;
    VPD_LOG("factory called");
    if (typeUUID == NULL || !CFEqual(typeUUID, kAudioServerPlugInTypeUUID))
    {
        VPD_LOG("factory: type UUID mismatch, returning NULL");
        return NULL;
    }
    VPD_AddRef(NULL);
    VPD_LOG("factory: returning driver ref");
    return gAudioServerPlugInDriverRef;
}

//==================================================================================================
#pragma mark -
#pragma mark Basic Operations
//==================================================================================================

static OSStatus VPD_Initialize(AudioServerPlugInDriverRef inDriver, AudioServerPlugInHostRef inHost)
{
    (void)inDriver;
    VPD_LOG("Initialize called, host=%p", inHost);
    gVPD.host = inHost;
    if (atomic_load_explicit(&gVPD.sampleRate, memory_order_relaxed) == 0)
    {
        atomic_store_explicit(&gVPD.sampleRate, kVPDSampleRate48000, memory_order_relaxed);
    }
    VPD_UpdateHostTicksPerFrame();
    if (atomic_load_explicit(&gVPD.clockAnchorHostTime, memory_order_relaxed) == 0)
    {
        atomic_store_explicit(&gVPD.clockAnchorHostTime, AudioGetCurrentHostTime(), memory_order_relaxed);
    }
    return noErr;
}

static OSStatus VPD_CreateDevice(AudioServerPlugInDriverRef inDriver, CFDictionaryRef inDescription,
                                 const AudioServerPlugInClientInfo* inClientInfo,
                                 AudioObjectID* outDeviceObjectID)
{
    (void)inDriver; (void)inDescription; (void)inClientInfo; (void)outDeviceObjectID;
    // The device is static; dynamically created devices are not supported.
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus VPD_DestroyDevice(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID)
{
    (void)inDriver; (void)inDeviceObjectID;
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus VPD_AddDeviceClient(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                    const AudioServerPlugInClientInfo* inClientInfo)
{
    (void)inDriver; (void)inClientInfo;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    return noErr;
}

static OSStatus VPD_RemoveDeviceClient(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                       const AudioServerPlugInClientInfo* inClientInfo)
{
    (void)inDriver; (void)inClientInfo;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    return noErr;
}

static OSStatus VPD_PerformDeviceConfigurationChange(AudioServerPlugInDriverRef inDriver,
                                                     AudioObjectID inDeviceObjectID,
                                                     UInt64 inChangeAction, void* inChangeInfo)
{
    (void)inDriver; (void)inDeviceObjectID; (void)inChangeAction; (void)inChangeInfo;
    // Sample-rate changes are applied synchronously via SetPropertyData; the deferred
    // configuration-change mechanism is not used.
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus VPD_AbortDeviceConfigurationChange(AudioServerPlugInDriverRef inDriver,
                                                   AudioObjectID inDeviceObjectID,
                                                   UInt64 inChangeAction, void* inChangeInfo)
{
    (void)inDriver; (void)inDeviceObjectID; (void)inChangeAction; (void)inChangeInfo;
    return kAudioHardwareUnsupportedOperationError;
}

//==================================================================================================
#pragma mark -
#pragma mark Property Entry Points
//==================================================================================================

static Boolean VPD_HasProperty(AudioServerPlugInDriverRef inDriver, AudioObjectID inObjectID,
                               pid_t inClientProcessID, const AudioObjectPropertyAddress* inAddress)
{
    (void)inDriver; (void)inClientProcessID;
    if (inAddress == NULL || !VPD_IsValidObjectID(inObjectID))
    {
        VPD_LOG("HasProperty: invalid object %u", (unsigned)inObjectID);
        return false;
    }
    switch (inObjectID)
    {
        case kAudioObjectPlugInObject:
            return VPD_PlugInHasProperty(inAddress);
        case kVPDObjectIDDevice:
            return VPD_DeviceHasProperty(inAddress);
        case kVPDObjectIDOutputStream:
        case kVPDObjectIDInputStream:
            return VPD_StreamHasProperty(inAddress);
        default:
            return false;
    }
}

static OSStatus VPD_IsPropertySettable(AudioServerPlugInDriverRef inDriver, AudioObjectID inObjectID,
                                       pid_t inClientProcessID,
                                       const AudioObjectPropertyAddress* inAddress,
                                       Boolean* outIsSettable)
{
    (void)inDriver; (void)inClientProcessID;
    if (inAddress == NULL || outIsSettable == NULL) { return kAudioHardwareIllegalOperationError; }
    if (!VPD_IsValidObjectID(inObjectID)) { return kAudioHardwareBadObjectError; }
    if (!VPD_HasProperty(inDriver, inObjectID, inClientProcessID, inAddress))
    {
        return kAudioHardwareUnknownPropertyError;
    }
    switch (inObjectID)
    {
        case kAudioObjectPlugInObject:
            *outIsSettable = VPD_PlugInIsPropertySettable(inAddress);
            return noErr;
        case kVPDObjectIDDevice:
            *outIsSettable = VPD_DeviceIsPropertySettable(inAddress);
            return noErr;
        case kVPDObjectIDOutputStream:
        case kVPDObjectIDInputStream:
            *outIsSettable = VPD_StreamIsPropertySettable(inAddress);
            return noErr;
        default:
            return kAudioHardwareBadObjectError;
    }
}

static OSStatus VPD_GetPropertyDataSize(AudioServerPlugInDriverRef inDriver, AudioObjectID inObjectID,
                                        pid_t inClientProcessID,
                                        const AudioObjectPropertyAddress* inAddress,
                                        UInt32 inQualifierDataSize, const void* inQualifierData,
                                        UInt32* outDataSize)
{
    (void)inDriver; (void)inClientProcessID;
    if (inAddress == NULL || outDataSize == NULL) { return kAudioHardwareIllegalOperationError; }
    if (!VPD_IsValidObjectID(inObjectID)) { return kAudioHardwareBadObjectError; }
    OSStatus vpdSizeResult = noErr;
    switch (inObjectID)
    {
        case kAudioObjectPlugInObject:
            vpdSizeResult = VPD_PlugInGetPropertyDataSize(inAddress, inQualifierDataSize, inQualifierData, outDataSize);
            break;
        case kVPDObjectIDDevice:
            vpdSizeResult = VPD_DeviceGetPropertyDataSize(inAddress, inQualifierDataSize, inQualifierData, outDataSize);
            break;
        case kVPDObjectIDOutputStream:
        case kVPDObjectIDInputStream:
            vpdSizeResult = VPD_StreamGetPropertyDataSize(inAddress, inQualifierDataSize, inQualifierData, outDataSize);
            break;
        default:
            return kAudioHardwareBadObjectError;
    }
    if (vpdSizeResult == noErr && inObjectID != kAudioObjectPlugInObject)
    {
        VPD_LOG("GetPropertyDataSize: obj=%u selector=%c%c%c%c scope=%c%c%c%c -> %u bytes",
                (unsigned)inObjectID,
                (char)(inAddress->mSelector >> 24), (char)(inAddress->mSelector >> 16),
                (char)(inAddress->mSelector >> 8), (char)(inAddress->mSelector),
                (char)(inAddress->mScope >> 24), (char)(inAddress->mScope >> 16),
                (char)(inAddress->mScope >> 8), (char)(inAddress->mScope),
                (unsigned)*outDataSize);
    }
    else if (vpdSizeResult != noErr)
    {
        VPD_LOG("GetPropertyDataSize FAILED: obj=%u selector=%c%c%c%c scope=%c%c%c%c err=%d",
                (unsigned)inObjectID,
                (char)(inAddress->mSelector >> 24), (char)(inAddress->mSelector >> 16),
                (char)(inAddress->mSelector >> 8), (char)(inAddress->mSelector),
                (char)(inAddress->mScope >> 24), (char)(inAddress->mScope >> 16),
                (char)(inAddress->mScope >> 8), (char)(inAddress->mScope),
                (int)vpdSizeResult);
    }
    return vpdSizeResult;
}

static OSStatus VPD_GetPropertyData(AudioServerPlugInDriverRef inDriver, AudioObjectID inObjectID,
                                    pid_t inClientProcessID,
                                    const AudioObjectPropertyAddress* inAddress,
                                    UInt32 inQualifierDataSize, const void* inQualifierData,
                                    UInt32 inDataSize, UInt32* outDataSize, void* outData)
{
    (void)inDriver; (void)inClientProcessID;
    if (inAddress == NULL || outData == NULL) { return kAudioHardwareIllegalOperationError; }
    if (!VPD_IsValidObjectID(inObjectID)) { return kAudioHardwareBadObjectError; }
    OSStatus vpdResult = noErr;
    switch (inObjectID)
    {
        case kAudioObjectPlugInObject:
            vpdResult = VPD_PlugInGetPropertyData(inAddress, inQualifierDataSize, inQualifierData,
                                             inDataSize, outDataSize, outData);
            break;
        case kVPDObjectIDDevice:
            vpdResult = VPD_DeviceGetPropertyData(inAddress, inQualifierDataSize, inQualifierData,
                                             inDataSize, outDataSize, outData);
            break;
        case kVPDObjectIDOutputStream:
        case kVPDObjectIDInputStream:
            vpdResult = VPD_StreamGetPropertyData(inObjectID, inAddress, inQualifierDataSize, inQualifierData,
                                             inDataSize, outDataSize, outData);
            break;
        default:
            return kAudioHardwareBadObjectError;
    }
    if (vpdResult != noErr)
    {
        VPD_LOG("GetPropertyData FAILED: obj=%u selector=%c%c%c%c scope=%c%c%c%c err=%d",
                (unsigned)inObjectID,
                (char)(inAddress->mSelector >> 24), (char)(inAddress->mSelector >> 16),
                (char)(inAddress->mSelector >> 8), (char)(inAddress->mSelector),
                (char)(inAddress->mScope >> 24), (char)(inAddress->mScope >> 16),
                (char)(inAddress->mScope >> 8), (char)(inAddress->mScope),
                (int)vpdResult);
    }
    return vpdResult;
}

static OSStatus VPD_SetPropertyData(AudioServerPlugInDriverRef inDriver, AudioObjectID inObjectID,
                                    pid_t inClientProcessID,
                                    const AudioObjectPropertyAddress* inAddress,
                                    UInt32 inQualifierDataSize, const void* inQualifierData,
                                    UInt32 inDataSize, const void* inData)
{
    (void)inDriver; (void)inClientProcessID; (void)inQualifierDataSize; (void)inQualifierData;
    if (inAddress == NULL || inData == NULL) { return kAudioHardwareIllegalOperationError; }
    if (!VPD_IsValidObjectID(inObjectID)) { return kAudioHardwareBadObjectError; }

    switch (inAddress->mSelector)
    {
        case kAudioObjectPropertyListenerAdded:
        case kAudioObjectPropertyListenerRemoved:
            // Listeners are managed by the host; accepting the registration is enough.
            return noErr;
        default:
            break;
    }

    switch (inObjectID)
    {
        case kVPDObjectIDDevice:
            if (inAddress->mSelector == kAudioDevicePropertyNominalSampleRate)
            {
                if (inDataSize != sizeof(Float64)) { return kAudioHardwareBadPropertySizeError; }
                return VPD_DeviceSetNominalSampleRate(*(const Float64*)inData);
            }
            return kAudioHardwareUnsupportedOperationError;
        case kVPDObjectIDOutputStream:
        case kVPDObjectIDInputStream:
            if (inAddress->mSelector == kAudioStreamPropertyVirtualFormat)
            {
                if (inDataSize != sizeof(AudioStreamBasicDescription))
                {
                    return kAudioHardwareBadPropertySizeError;
                }
                return VPD_StreamSetVirtualFormat((const AudioStreamBasicDescription*)inData);
            }
            return kAudioHardwareUnsupportedOperationError;
        default:
            return kAudioHardwareUnsupportedOperationError;
    }
}

//==================================================================================================
#pragma mark -
#pragma mark IO Operations
//==================================================================================================

static OSStatus VPD_StartIO(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                            UInt32 inClientID)
{
    (void)inDriver; (void)inClientID;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }

    Boolean becameRunning = false;
    pthread_mutex_lock(&gVPDMutex);
    gVPD.ioClientCount += 1;
    if (gVPD.ioClientCount == 1)
    {
        // First client: start from a clean, empty, freshly clocked loopback.
        VPD_ResetRingAndClock();
        atomic_store_explicit(&gVPD.dbgMixOutputCalls, 0, memory_order_relaxed);
        atomic_store_explicit(&gVPD.dbgReadInputCalls, 0, memory_order_relaxed);
        atomic_store_explicit(&gVPD.ioRunning, 1, memory_order_release);
        becameRunning = true;
    }
    pthread_mutex_unlock(&gVPDMutex);

    if (becameRunning) { VPD_NotifyRunningChanged(); }
    VPD_LOG("StartIO: clients=%d", gVPD.ioClientCount);
    return noErr;
}

static OSStatus VPD_StopIO(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                           UInt32 inClientID)
{
    (void)inDriver; (void)inClientID;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }

    Boolean becameIdle = false;
    pthread_mutex_lock(&gVPDMutex);
    if (gVPD.ioClientCount > 0)
    {
        gVPD.ioClientCount -= 1;
        if (gVPD.ioClientCount == 0)
        {
            atomic_store_explicit(&gVPD.ioRunning, 0, memory_order_release);
            becameIdle = true;
        }
    }
    pthread_mutex_unlock(&gVPDMutex);

    if (becameIdle) { VPD_NotifyRunningChanged(); }
    VPD_LOG("StopIO: clients=%d ringWritten=%llu ringRead=%llu mixCalls=%llu readCalls=%llu",
            gVPD.ioClientCount,
            (unsigned long long)atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&gVPD.ringBytesRead, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&gVPD.dbgMixOutputCalls, memory_order_relaxed),
            (unsigned long long)atomic_load_explicit(&gVPD.dbgReadInputCalls, memory_order_relaxed));
    return noErr;
}

static OSStatus VPD_GetZeroTimeStamp(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                     UInt32 inClientID, Float64* outSampleTime, UInt64* outHostTime,
                                     UInt64* outSeed)
{
    (void)inDriver; (void)inClientID;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    if (outSampleTime == NULL || outHostTime == NULL || outSeed == NULL)
    {
        return kAudioHardwareIllegalOperationError;
    }

    // The device clock is the host clock: sample time is derived from the number of host ticks
    // elapsed since IO started, wrapped at the zero-time-stamp period so the host can track
    // period boundaries exactly.
    UInt64 now = AudioGetCurrentHostTime();
    UInt64 anchor = atomic_load_explicit(&gVPD.clockAnchorHostTime, memory_order_relaxed);
    Float64 ticksPerFrame = atomic_load_explicit(&gVPD.hostTicksPerFrame, memory_order_relaxed);

    Float64 frames = 0.0;
    if (ticksPerFrame > 0.0 && now >= anchor)
    {
        frames = (Float64)(now - anchor) / ticksPerFrame;
    }
    Float64 wrapped = fmod(frames, (Float64)kVPDZeroTimeStampPeriodFrames);
    if (wrapped < 0.0 || !(wrapped == wrapped)) { wrapped = 0.0; } // guard NaN/negative

    *outSampleTime = wrapped;
    *outHostTime = now;
    *outSeed = atomic_fetch_add_explicit(&gVPD.zeroTimeStampSeed, 1, memory_order_relaxed) + 1;
    return noErr;
}

static OSStatus VPD_WillDoIOOperation(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                      UInt32 inClientID, UInt32 inOperationID,
                                      Boolean* outWillDo, Boolean* outWillDoInPlace)
{
    (void)inDriver; (void)inClientID;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    if (outWillDo == NULL || outWillDoInPlace == NULL) { return kAudioHardwareIllegalOperationError; }

    // Only the two terminal transfer operations are handled, both in-place in the main buffer:
    // reading input (ring → client) and mixing output (client → ring). Everything else in the
    // IO pipeline (conversion, processing) is left to the host.
    switch (inOperationID)
    {
        case kAudioServerPlugInIOOperationReadInput:
        case kAudioServerPlugInIOOperationMixOutput:
            *outWillDo = true;
            *outWillDoInPlace = true;
            VPD_LOG("WillDoIOOperation: op=%u willDo=1", (unsigned)inOperationID);
            break;
        default:
            *outWillDo = false;
            *outWillDoInPlace = false;
            break;
    }
    return noErr;
}

static OSStatus VPD_BeginIOOperation(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                     UInt32 inClientID, UInt32 inOperationID,
                                     UInt32 inIOBufferFrameSize,
                                     const AudioServerPlugInIOCycleInfo* inIOCycleInfo)
{
    (void)inDriver; (void)inClientID; (void)inOperationID;
    (void)inIOBufferFrameSize; (void)inIOCycleInfo;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    return noErr;
}

// Real-time entry point. No allocation, no locks, no logging below this line.
static OSStatus VPD_DoIOOperation(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                  AudioObjectID inStreamObjectID, UInt32 inClientID,
                                  UInt32 inOperationID, UInt32 inIOBufferFrameSize,
                                  const AudioServerPlugInIOCycleInfo* inIOCycleInfo,
                                  void* ioMainBuffer, void* ioSecondaryBuffer)
{
    (void)inDriver; (void)inClientID; (void)inIOBufferFrameSize; (void)ioSecondaryBuffer;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    if (ioMainBuffer == NULL) { return noErr; }

    if (inOperationID == kAudioServerPlugInIOOperationMixOutput
        && inStreamObjectID == kVPDObjectIDOutputStream)
    {
        UInt64 prev = atomic_fetch_add_explicit(&gVPD.dbgMixOutputCalls, 1, memory_order_relaxed);
        if (prev == 0) {
            VPD_LOG("DoIOOperation: FIRST mixOutput frames=%u main=%p sec=%p",
                    (unsigned int)inIOBufferFrameSize, ioMainBuffer, ioSecondaryBuffer);
        }
        VPD_RingWrite((const UInt8*)ioMainBuffer,
                      (UInt64)inIOBufferFrameSize * kVPDBytesPerFrame, inIOCycleInfo);
        if (prev % 200 == 199) {
            VPD_LOG("mix[%llu]: ringWritten=%llu ringRead=%llu",
                    (unsigned long long)(prev + 1),
                    (unsigned long long)atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_relaxed),
                    (unsigned long long)atomic_load_explicit(&gVPD.ringBytesRead, memory_order_relaxed));
        }
        atomic_store_explicit(&gVPD.dbgMixOutputBytes,
                              atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_relaxed),
                              memory_order_relaxed);
    }
    else if (inOperationID == kAudioServerPlugInIOOperationReadInput
             && inStreamObjectID == kVPDObjectIDInputStream)
    {
        UInt64 prev = atomic_fetch_add_explicit(&gVPD.dbgReadInputCalls, 1, memory_order_relaxed);
        if (prev == 0) {
            VPD_LOG("DoIOOperation: FIRST readInput frames=%u main=%p sec=%p",
                    (unsigned int)inIOBufferFrameSize, ioMainBuffer, ioSecondaryBuffer);
        }
        VPD_RingRead((UInt8*)ioMainBuffer, (UInt64)inIOBufferFrameSize * kVPDBytesPerFrame);
        if (prev % 200 == 199) {
            VPD_LOG("read[%llu]: ringWritten=%llu ringRead=%llu",
                    (unsigned long long)(prev + 1),
                    (unsigned long long)atomic_load_explicit(&gVPD.ringBytesWritten, memory_order_relaxed),
                    (unsigned long long)atomic_load_explicit(&gVPD.ringBytesRead, memory_order_relaxed));
        }
    }
    return noErr;
}

static OSStatus VPD_EndIOOperation(AudioServerPlugInDriverRef inDriver, AudioObjectID inDeviceObjectID,
                                   UInt32 inClientID, UInt32 inOperationID,
                                   UInt32 inIOBufferFrameSize,
                                   const AudioServerPlugInIOCycleInfo* inIOCycleInfo)
{
    (void)inDriver; (void)inClientID; (void)inOperationID;
    (void)inIOBufferFrameSize; (void)inIOCycleInfo;
    if (inDeviceObjectID != kVPDObjectIDDevice) { return kAudioHardwareBadObjectError; }
    return noErr;
}

//==================================================================================================
#pragma mark -
#pragma mark Interface Table and Exported Symbols
//==================================================================================================

static AudioServerPlugInDriverInterface gVPDInterface =
{
    NULL, // _reserved
    VPD_QueryInterface,
    VPD_AddRef,
    VPD_Release,
    VPD_Initialize,
    VPD_CreateDevice,
    VPD_DestroyDevice,
    VPD_AddDeviceClient,
    VPD_RemoveDeviceClient,
    VPD_PerformDeviceConfigurationChange,
    VPD_AbortDeviceConfigurationChange,
    VPD_HasProperty,
    VPD_IsPropertySettable,
    VPD_GetPropertyDataSize,
    VPD_GetPropertyData,
    VPD_SetPropertyData,
    VPD_StartIO,
    VPD_StopIO,
    VPD_GetZeroTimeStamp,
    VPD_WillDoIOOperation,
    VPD_BeginIOOperation,
    VPD_DoIOOperation,
    VPD_EndIOOperation
};

// The COM object: a struct whose first word points at the interface table. This is the object
// identity handed to coreaudiod; it is statically allocated and lives forever.
typedef struct
{
    AudioServerPlugInDriverInterface* interface; // must be the first field
} VPDCOMObject;

static VPDCOMObject gVPDObject = { &gVPDInterface };

// Required export: coreaudiod resolves this symbol to reach the driver object.
AudioServerPlugInDriverRef gAudioServerPlugInDriverRef = &gVPDObject.interface;

extern void* VibePadAudioDriverFactory(CFAllocatorRef allocator, CFUUIDRef typeUUID);
void* VibePadAudioDriverFactory(CFAllocatorRef allocator, CFUUIDRef typeUUID)
{
    return VPD_Factory(allocator, typeUUID);
}
