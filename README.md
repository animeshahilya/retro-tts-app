# Retro TTS for Android

A modern Android application that brings classic retro Text-To-Speech (TTS) synthesizers into a unified system-wide TTS engine, with full TalkBack compatibility! 

This app wraps three iconic voice synthesis engines via Android JNI/C++ and exposes them as native Android TTS providers, allowing you to use retro voices for standard screen reading, navigation, and accessibility.

## Features

- **System-Wide TTS Provider**: Integrates seamlessly with Android Accessibility Services. Once set as the default TTS engine, TalkBack and other apps can natively route speech to the retro engines.
- **Modern Jetpack Compose UI**: A fast, responsive, and fully accessible Material 3 interface that is rigorously optimized for TalkBack users.
- **Haptic Feedback**: Meaningful tactile feedback for selecting voices and interacting with the UI.
- **Live Pitch & Speech Rate Modification**: On-the-fly sliders to preview and tune your voice settings (50% to 200%). Pitch and speech rate seamlessly bridge from standard Android TTS requests down to the native C/C++ engine configurations.

## Included Synthesizers

1. **SAM (Software Automatic Mouth)**
   The legendary 8-bit voice synthesizer from 1982, famously used on the Commodore 64 and Atari. 
2. **DECtalk (Perfect Paul)**
   The iconic hardware speech synthesizer developed by Digital Equipment Corporation in the 1980s, famously known as the voice of Stephen Hawking.
3. **eSpeak-ng (Multiple Variants)**
   The compact, open-source software speech synthesizer featuring a robotic formant synthesis engine. Comes bundled with numerous specialized variants, including:
   - Default Standard
   - Whisper
   - Croak
   - Klatt
   - Robosoft & Robosoft8
   - Yelling

## Installation

You can build this project directly using Android Studio or Gradle via the command line:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Setting as Default TTS Engine

1. Open the app and tap **Open System TTS Settings**.
2. Select **Preferred engine**.
3. Choose **Retro TTS** from the list.
4. (Optional) In the Android Accessibility Settings, configure TalkBack to use standard speeds and pitches to ensure the retro engines sound accurate to their original configurations.

## Architecture

- **Kotlin & Jetpack Compose**: `MainActivity.kt` orchestrates the modern UI and parameters. `RetroTtsService.kt` extends `TextToSpeechService` to handle system-wide accessibility synthesis requests.
- **C++ JNI Bridge**: `native-lib.cpp` mathematical scales and translates standard TalkBack 0-100 pitch/rate arrays into internal C struct modifiers for SAM, DECtalk, and eSpeak.
- **Native Libraries**: The core engines run incredibly fast as pre-compiled C/C++ code inside `libttsespeak.so`, utilizing zero-allocation paths and `AudioTrack` PCM streaming.

## License

This project aggregates multiple engines with varying open-source licenses (GPLv3 for eSpeak-ng, etc.). Please check the respective repository folders for individual licensing terms.
