# Retro TTS for Android

A modern, high-performance Android application that brings classic retro Text-To-Speech (TTS) synthesizers into a unified system-wide TTS engine, with full TalkBack and Accessibility Service compatibility!

This app wraps four iconic speech synthesis engines via Android JNI/C++ and exposes them as native Android TTS providers, allowing you to use retro and vintage synth voices for standard screen reading, navigation, and accessibility.

## Features

- **System-Wide TTS Provider**: Integrates seamlessly with Android Accessibility Services. Once set as the default TTS engine, TalkBack, navigation, and third-party apps can natively route speech to any retro engine.
- **Lock-Free Instant Cancellation**: Sub-millisecond speech interruption when navigating or swiping in TalkBack, preventing speech lag or queued utterance delays.
- **SIMD-Accelerated Polyphase Resampling**: High-fidelity Hann-windowed sinc polyphase filter bank auto-vectorized with ARM NEON to upsample all native engine rates (11,025 Hz and 22,050 Hz) cleanly to 48,000 Hz / 16-bit PCM with peak headroom normalization.
- **Modern Jetpack Compose UI**: Fast, responsive, and accessible Material 3 interface optimized for TalkBack exploration with meaningful tactile haptic feedback.
- **Live Pitch & Speech Rate Modification**: Real-time sliders to preview and tune voice parameters (50% to 200%). Pitch and speech rate seamlessly bridge from standard Android TTS requests down to native engine configurations.

## Included Synthesizers

1. **SAM (Software Automatic Mouth)**
   The legendary 8-bit speech synthesizer from 1982, famously used on the Commodore 64, Atari, and Apple II.
2. **DECtalk (Perfect Paul)**
   The iconic formant speech synthesizer developed by Digital Equipment Corporation in the 1980s, famously recognized worldwide as the voice of Prof. Stephen Hawking.
3. **eSpeak-NG (Multilingual + Variants)**
   The compact open-source formant synthesizer featuring robotic formant synthesis. Comes bundled with multilingual voices and specialized variant styles:
   - Default Standard
   - Whisper
   - Croak
   - Klatt
   - Robosoft & Robosoft8
   - Yelling
4. **Eloquence / IBM Embedded ViaVoice (openevv)**
   The classic, beloved screen reader voice synthesizer popular across decades of accessibility tools. Includes all 8 iconic formant presets:
   - **Reed** (Adult Male 1, default)
   - **Shelley** (Adult Female 1)
   - **Bobby** (Child)
   - **Glen** (Adult Male 2)
   - **Sandy** (Adult Female 2)
   - **Grandma** (Elderly Female)
   - **Grandpa** (Elderly Male)
   - **Rocko** (Male Character)

## Installation & Building

### Build from Source

You can build debug or signed release APKs using Gradle:

```bash
# Debug build
./gradlew assembleDebug

# Production / Release build
./gradlew assembleRelease
```

The compiled APKs will be generated in:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

### Setting as Default TTS Engine

1. Open the app and tap **Open System TTS Settings**.
2. Select **Preferred engine**.
3. Choose **Retro TTS** from the list.
4. Configure TalkBack speeds and pitches to taste.

## Architecture

- **Kotlin & Jetpack Compose**: `MainActivity.kt` orchestrates the UI, voice parameters, and asynchronous voice pack extraction. `RetroTtsService.kt` extends `TextToSpeechService` to handle system-wide accessibility synthesis requests with 2048-byte low-latency streaming chunks.
- **C++ JNI Bridge**: `native-lib.cpp` scales standard TalkBack pitch/rate parameters, interfaces with native synthesis engines, and utilizes a lock-free cancellation protocol for TalkBack touch responsiveness.
- **Audio DSP**: Polyphase sinc resampler normalizes all native rates to 48,000 Hz / 16-bit mono PCM with target peak normalization for zero-distortion playback.

## License

This project aggregates multiple engines with varying open-source licenses (GPLv3 for eSpeak-ng, MIT for openevv core, etc.). Please check the respective repository folders for individual licensing terms.
