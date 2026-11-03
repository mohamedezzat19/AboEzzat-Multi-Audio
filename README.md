# AboEzzat Multi Audio

Android app prototype for Bluetooth device discovery and audio controls.

## Current scope
- One-screen dark UI.
- Bluetooth device discovery (BLE scan + classic discovery where permitted).
- Basic app-owned playback equalizer UI scaffold.
- Source buttons are UI placeholders; AUX/USB/TF are controlled by speaker hardware unless it exposes a supported control protocol.

## Platform limits
- Android apps cannot force arbitrary Bluetooth speakers to receive audio simultaneously. Multi-output depends on Android/HyperOS and speaker capabilities.
- Bluetooth LE discovery is not the same as Bluetooth audio streaming.
- Bluetooth device name/PIN pairing is system-managed. The app may request a local adapter name change where supported, but cannot impose a universal PIN on nearby speakers.
- Equalizer effects generally apply to audio sessions created by this app; global system-wide EQ is not guaranteed.

## Build
Open in Android Studio (JDK 17, Android Gradle Plugin 8.7.3) or run GitHub Actions to build a debug APK.
