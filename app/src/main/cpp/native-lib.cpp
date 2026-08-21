#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cctype>
#include <cmath>
#include <algorithm>
#include <map>
#include <mutex>

extern "C" {
    #include "reciter.h"
    #include "sam.h"
    #include "epsonapi.h"
    // Minimal eSpeak-NG API surface we use (the prebuilt libttsespeak.so exposes these).
    typedef enum { EE_OK=0, EE_INTERNAL_ERROR=-1, EE_BUFFER_FULL=1, EE_NOT_FOUND=2 } espeak_ERROR;
    #define espeakRATE 1
    #define espeakPITCH 3
    #define espeakINITIALIZE_DONT_EXIT 0x8000
    int espeak_Initialize(int output, int buflength, const char *path, int options);
    espeak_ERROR espeak_Synth(const void *text, size_t size, unsigned int position, int position_type,
                              unsigned int end_position, unsigned int flags, unsigned int *unique_identifier, void *user_data);
    void espeak_SetSynthCallback(int (*SynthCallback)(short *wav, int numsamples, int events));
    espeak_ERROR espeak_SetParameter(int parameter, int value, int relative);
    espeak_ERROR espeak_SetVoiceByName(const char *name);
    void espeak_Cancel(void);
    // SAM references this global; provide a single definition so the native lib links.
    int debug = 0;
}

const int TARGET_SAMPLE_RATE = 48000;

// All three engines share process-wide state (DECtalk/espeak accumulation buffers and
// SAM's static globals). Syntheses can be triggered from the TTS service binder thread
// and from the UI's Dispatchers.IO thread concurrently, so guard every synthesis with a
// single mutex to avoid data races on those shared buffers/globals.
static std::mutex g_synthMutex;
static bool g_espeakInited = false;

// Windowed-sinc (Hann-windowed) resampler. Cleaner than cubic-Hermite followed by a 1-pole IIR:
// the sinc kernel *is* the anti-imaging low-pass, bandlimiting to the source Nyquist so upsampling
// to 48 kHz does not inject spurious high-frequency images.
static const float PI = 3.14159265358979323846f;
static const int RESAMPLER_HALF = 16;      // 33-tap kernel
static const int RESAMPLER_PHASES = 1024;  // polyphase table resolution

static float sinc(float x) {
    if (x == 0.0f) return 1.0f;
    float px = PI * x;
    return sinf(px) / px;
}

// Hann-windowed sinc low-pass, cutoff at the source Nyquist (fc = 0.5 cycles/sample), spanning
// [-halfLen, +halfLen] input samples. Returns 0 outside the support.
static float sincKernel(float x, int halfLen) {
    if (x < -static_cast<float>(halfLen) || x > static_cast<float>(halfLen)) return 0.0f;
    float w = 0.5f - 0.5f * cosf(2.0f * PI * (x + static_cast<float>(halfLen)) / (2.0f * static_cast<float>(halfLen)));
    return sinc(x) * w;
}

// Pre-computed polyphase filter bank. Building the kernel on the fly needs a sinf/cosf per tap per
// output sample (tens of millions of trig calls for a long utterance). The resample ratio is fixed
// per engine, so we precompute, for each of RESAMPLER_PHASES fractional phases, the (2*HALF+1)
// kernel weights once and reuse them for every synthesis from that engine.
static std::mutex g_filterMutex;
static std::map<int, std::vector<float>> g_filterBanks; // sourceRate -> PHASES * (2*HALF+1) weights

static const std::vector<float>& getFilterBank(int sourceRate) {
    std::lock_guard<std::mutex> lk(g_filterMutex);
    auto it = g_filterBanks.find(sourceRate);
    if (it != g_filterBanks.end()) return it->second;
    std::vector<float> bank(RESAMPLER_PHASES * (2 * RESAMPLER_HALF + 1));
    for (int p = 0; p < RESAMPLER_PHASES; ++p) {
        float frac = static_cast<float>(p) / RESAMPLER_PHASES; // interpolation offset in [0,1)
        for (int k = -RESAMPLER_HALF; k <= RESAMPLER_HALF; ++k) {
            // For output sample i: pos = i*ratio, center = floor(pos), frac = pos - center.
            // The tap at integer lag k is evaluated at x = frac - k.
            float x = frac - static_cast<float>(k);
            bank[p * (2 * RESAMPLER_HALF + 1) + (k + RESAMPLER_HALF)] = sincKernel(x, RESAMPLER_HALF);
        }
    }
    g_filterBanks.emplace(sourceRate, std::move(bank));
    return g_filterBanks.find(sourceRate)->second;
}

// Resample an engine's native PCM up to the target 48 kHz / 16-bit mono stream the
// Android AudioTrack consumes. is8Bit selects unsigned 8-bit (SAM) vs signed 16-bit.
jbyteArray upscaleAndSmooth(JNIEnv* env, const void* inputData, int inputSamplesCount, int sourceSampleRate, bool is8Bit) {
    if (inputSamplesCount <= 0) {
        return env->NewByteArray(0);
    }

    // Convert source samples to a float buffer in signed 16-bit scale.
    std::vector<float> src(inputSamplesCount);
    const uint8_t* in8 = static_cast<const uint8_t*>(inputData);
    const int16_t* in16 = static_cast<const int16_t*>(inputData);
    for (int i = 0; i < inputSamplesCount; ++i) {
        src[i] = is8Bit ? ((static_cast<float>(in8[i]) - 128.0f) * 256.0f) : static_cast<float>(in16[i]);
    }

    double ratio = static_cast<double>(sourceSampleRate) / TARGET_SAMPLE_RATE; // < 1 for upsampling
    if (ratio <= 0.0) ratio = 1.0;
    int outputSamplesCount = static_cast<int>(inputSamplesCount / ratio);
    if (outputSamplesCount <= 0) outputSamplesCount = 1;
    std::vector<int16_t> outputBuffer(outputSamplesCount);

    const std::vector<float>& bank = getFilterBank(sourceSampleRate);
    const int stride = 2 * RESAMPLER_HALF + 1;

    auto clampIdx = [&](int k) -> int {
        if (k < 0) return 0;
        if (k >= inputSamplesCount) return inputSamplesCount - 1;
        return k;
    };

    for (int i = 0; i < outputSamplesCount; ++i) {
        double pos = i * ratio;
        int center = static_cast<int>(floor(pos));
        float frac = static_cast<float>(pos - center);
        int p = static_cast<int>(frac * RESAMPLER_PHASES) % RESAMPLER_PHASES;
        if (p < 0) p += RESAMPLER_PHASES;
        const float* w = &bank[p * stride];
        float acc = 0.0f;
        for (int k = -RESAMPLER_HALF; k <= RESAMPLER_HALF; ++k) {
            acc += src[clampIdx(center + k)] * w[k + RESAMPLER_HALF];
        }
        int v = static_cast<int>(lrintf(acc));
        if (v > 32767) v = 32767;
        if (v < -32768) v = -32768;
        outputBuffer[i] = static_cast<int16_t>(v);
    }

    // Peak-normalize instead of a fixed gain. A fixed gain clips any signal already near full
    // scale; normalization keeps loud sources safe while lifting quiet ones for consistent
    // loudness. We only attenuate when clipping would occur, and cap any boost so near-silent
    // audio (mostly noise floor) is not amplified into harsh artifacts.
    const int targetPeak = 30000; // ~ -0.8 dBFS, leaves headroom
    int peak = 0;
    for (int i = 0; i < outputSamplesCount; ++i) {
        int a = outputBuffer[i] < 0 ? -outputBuffer[i] : outputBuffer[i];
        if (a > peak) peak = a;
    }
    if (peak > 0) {
        float scale = static_cast<float>(targetPeak) / peak;
        if (scale > 4.0f) scale = 4.0f; // cap boost for very quiet sources
        for (int i = 0; i < outputSamplesCount; ++i) {
            float val = outputBuffer[i] * scale;
            if (val > 32767.0f) val = 32767.0f;
            if (val < -32768.0f) val = -32768.0f;
            outputBuffer[i] = static_cast<int16_t>(val);
        }
    }

    int outBytes = outputSamplesCount * sizeof(int16_t);
    jbyteArray result = env->NewByteArray(outBytes);
    env->SetByteArrayRegion(result, 0, outBytes, reinterpret_cast<const jbyte*>(outputBuffer.data()));
    return result;
}

// ================= SAM Core =================
jbyteArray doSynthSam(JNIEnv* env, jstring text, jint pitch, jint speechRate) {
    const char *nativeString = env->GetStringUTFChars(text, 0);
    if (!nativeString) return env->NewByteArray(0);

    // Buffer must hold the input text (fully upper-cased, 1:1 with phoneme codes) plus the
    // terminating 0x9b marker. Original code used 256 bytes and strcat'd 0x9b past a 255-char
    // copy, overrunning the buffer by one byte. Size generously and bound every write.
    unsigned char input[2048];
    memset(input, 0, sizeof(input));
    strncpy((char*)input, nativeString, sizeof(input) - 2);
    ((char*)input)[sizeof(input) - 2] = '\0';

    for (int i = 0; input[i] != 0; i++) {
        input[i] = (unsigned char)toupper((unsigned char)input[i]);
    }

    TextToPhonemes(input);
    strncat((char*)input, "\x9b", sizeof(input) - strlen((char*)input) - 1);

    SetInput((char*)input);

    // Default SAM: pitch = 64, speed = 72
    // Lower pitch value = higher voice in SAM
    int samPitch = 64 * 100 / (pitch > 0 ? pitch : 100);
    if (samPitch < 0) samPitch = 0;
    if (samPitch > 255) samPitch = 255;

    // Lower speed value = faster voice in SAM
    int samSpeed = 72 * 100 / (speechRate > 0 ? speechRate : 100);
    if (samSpeed < 0) samSpeed = 0;
    if (samSpeed > 255) samSpeed = 255;

    SetPitch((unsigned char)samPitch);
    SetSpeed((unsigned char)samSpeed);

    SAMMain();

    char* buffer = GetBuffer();
    int length = GetBufferLength();

    jbyteArray result = upscaleAndSmooth(env, buffer, length, 22050, true);

    env->ReleaseStringUTFChars(text, nativeString);
    return result;
}

// ================= DECtalk Core =================
static std::vector<short> dectalk_buffer;

short* dectalk_callback(short* wav, long numsamples, int events) {
    if (numsamples > 0 && wav != nullptr) {
        dectalk_buffer.insert(dectalk_buffer.end(), wav, wav + numsamples);
    }
    return wav;
}

jbyteArray doSynthDectalk(JNIEnv* env, jstring text, jint pitch, jint speechRate) {
    const char *nativeString = env->GetStringUTFChars(text, 0);
    if (!nativeString) return env->NewByteArray(0);

    dectalk_buffer.clear();

    TextToSpeechInit(dectalk_callback, nullptr);

    // DECtalk defaults: rate = 180, ap = 122
    int dtRate = 180 * (speechRate > 0 ? speechRate : 100) / 100;
    TextToSpeechSetRate(dtRate);

    int dtPitch = 122 * (pitch > 0 ? pitch : 100) / 100;
    TextToSpeechSetVoiceParam("ap", dtPitch);

    // Long TalkBack utterances were silently truncated at 1024 bytes. Use a larger buffer
    // and forward the whole string, not just the first 1023 characters.
    char inputBuf[8192];
    memset(inputBuf, 0, sizeof(inputBuf));
    strncpy(inputBuf, nativeString, sizeof(inputBuf) - 1);
    inputBuf[sizeof(inputBuf) - 1] = '\0';

    TextToSpeechStart(inputBuf, nullptr, 0);
    TextToSpeechSync();

    jbyteArray result = upscaleAndSmooth(env, dectalk_buffer.data(), (int)dectalk_buffer.size(), 11025, false);

    env->ReleaseStringUTFChars(text, nativeString);
    return result;
}

// ================= eSpeak Core =================
static std::vector<short> espeak_buffer;

static int espeak_callback(short *wav, int numsamples, int events) {
    if (wav == nullptr) return 1;
    if (numsamples > 0) {
        espeak_buffer.insert(espeak_buffer.end(), wav, wav + numsamples);
    }
    return 0; // 0 continues synthesis
}

// Initialize eSpeak exactly once. The data path must point at the directory that *contains*
// espeak-ng-data (i.e. the app files dir). espeakINITIALIZE_DONT_EXIT prevents the library from
// calling exit(1) on a missing-data-path failure, which would otherwise kill the whole app
// process (and with it any TalkBack session using this engine).
static bool ensureEspeak(const char* path) {
    if (g_espeakInited) return true;
    // eSpeak-NG spawns the "mbrola" executable via execlp(); make sure the directory that
    // holds our extracted mbrola binary (the data path) is on PATH so it can be found.
    const char* oldPath = getenv("PATH");
    char newPath[4096];
    snprintf(newPath, sizeof(newPath), "%s:%s", path, oldPath ? oldPath : "");
    setenv("PATH", newPath, 1);
    int sr = espeak_Initialize(1, 0, path, espeakINITIALIZE_DONT_EXIT);
    if (sr <= 0) return false;
    g_espeakInited = true;
    return true;
}

jbyteArray doSynthEspeak(JNIEnv* env, jstring text, jstring dataPath, jstring voiceName, jint pitch, jint speechRate) {
    const char *nativeText = env->GetStringUTFChars(text, 0);
    const char *nativeDataPath = env->GetStringUTFChars(dataPath, 0);
    const char *nativeVoiceName = env->GetStringUTFChars(voiceName, 0);

    if (!nativeText || !nativeDataPath || !nativeVoiceName) {
        if (nativeText) env->ReleaseStringUTFChars(text, nativeText);
        if (nativeDataPath) env->ReleaseStringUTFChars(dataPath, nativeDataPath);
        if (nativeVoiceName) env->ReleaseStringUTFChars(voiceName, nativeVoiceName);
        return env->NewByteArray(0);
    }

    espeak_buffer.clear();

    if (!ensureEspeak(nativeDataPath)) {
        env->ReleaseStringUTFChars(text, nativeText);
        env->ReleaseStringUTFChars(dataPath, nativeDataPath);
        env->ReleaseStringUTFChars(voiceName, nativeVoiceName);
        return env->NewByteArray(0);
    }

    espeak_SetSynthCallback(espeak_callback);
    espeak_SetVoiceByName(nativeVoiceName);

    // eSpeak defaults: rate = 175, pitch = 50
    int esRate = 175 * (speechRate > 0 ? speechRate : 100) / 100;
    int esPitch = 50 * (pitch > 0 ? pitch : 100) / 100;

    espeak_SetParameter(espeakRATE, esRate, 0);
    espeak_SetParameter(espeakPITCH, esPitch, 0);

    espeak_Synth(nativeText, strlen(nativeText) + 1, 0, 1, 0, 0x1, nullptr, nullptr); // 1 = POS_CHARACTER

    jbyteArray result = upscaleAndSmooth(env, espeak_buffer.data(), (int)espeak_buffer.size(), 22050, false);

    env->ReleaseStringUTFChars(text, nativeText);
    env->ReleaseStringUTFChars(dataPath, nativeDataPath);
    env->ReleaseStringUTFChars(voiceName, nativeVoiceName);
    return result;
}

// Request interruption of an in-flight eSpeak synthesis (no-op for SAM/DECtalk).
void doCancelEspeak() {
    if (g_espeakInited) espeak_Cancel();
}

// ================= JNI EXPORTS =================
// NOTE: the JNI long name MUST match the Kotlin package/class, otherwise the runtime raises
// UnsatisfiedLinkError on the first synthesis call. Keep these in sync with
// com.animeshahilya.retrotts.{MainActivity,RetroTtsService}.

extern "C" {
    // MainActivity bindings
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_MainActivity_synthSam(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthSam(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_MainActivity_synthDectalk(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthDectalk(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_MainActivity_synthEspeak(JNIEnv* env, jobject, jstring text, jstring dataPath, jstring voiceName, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthEspeak(env, text, dataPath, voiceName, pitch, speechRate);
    }

    // RetroTtsService bindings
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_RetroTtsService_synthSam(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthSam(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_RetroTtsService_synthDectalk(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthDectalk(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_RetroTtsService_synthEspeak(JNIEnv* env, jobject, jstring text, jstring dataPath, jstring voiceName, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthEspeak(env, text, dataPath, voiceName, pitch, speechRate);
    }

    JNIEXPORT void JNICALL Java_com_animeshahilya_retrotts_RetroTtsService_cancelSynth(JNIEnv*, jobject) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        doCancelEspeak();
    }
}
