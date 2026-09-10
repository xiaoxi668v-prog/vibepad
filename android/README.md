# VibePad

This project is the reversible Android 9 prototype for the dedicated VibePad.
It is installed alongside Entangle under the package `com.xiaoxi.vibepad`.

VibePad now uses the authenticated local Wi-Fi link to VibePad Helper. Bluetooth HID
is retained only as historical source and is not selected by `MainActivity`.

- relative mouse movement and buttons;
- vertical and horizontal scrolling;
- keyboard keys and modifiers, including the left Alt/Option key used by Typeless;
- a native landscape touch surface with no JavaScript hot path.

The current UI includes the confirmed 16:10 control layout, exact Vibe Coding
keyboard, single-tap Typeless wake, short/long send behavior, persistent custom
shortcuts, trusted Helper health, local usage quotas, and a controlled Mac app
catalog/launcher.

Three skins ship in the app and are switched in settings (or from the Mac
helper): `classic` (01 经典黑, the shipped 0.4.1 layout), `graphite`
(02 深空专业) and `titanium` (05 双手操控). Skins only change palette and
layout: protocol, gestures, key semantics and the Typeless flow are shared.
See `ui/Skin.kt`, `ui/VibePadView.kt` and `docs/HANDOFF.md` section 20.

Skin, header mode, favourite apps, custom shortcuts and pointer/scroll
sensitivity live in `ui/PadConfig.kt` and sync with the Mac helper over frames
`0x60`/`0x61`/`0x62`; the higher `revision` wins.

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
ANDROID_HOME=/Users/shishuai/Library/Android/sdk \
./gradlew :app:assembleDebug
```

## Safety

Use `/Users/shishuai/vibepad/scripts/release-vibepad.sh` to create a signed staged
release. Pass `--install` only when the Android tablet is connected and the user
is ready for real-finger acceptance testing. The script refuses to fall back to
ad-hoc signing.

The prototype does not provision Device Owner or enter Lock Task unless the
package has already been allow-listed. Kiosk provisioning is a later,
explicitly confirmed step because an already configured device may require a
factory reset before Device Owner can be assigned.
