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
#include <atomic>

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

    // Minimal openevv (Eloquence) API surface
    typedef struct OldInst OldInst;
    enum ECIMessage { eciWaveformBuffer = 0, eciPhonemeBuffer = 1, eciIndexReply = 2 };
    enum ECICallbackReturn { eciDataNotProcessed = 0, eciDataProcessed = 1, eciDataAbort = 2 };
    enum { EVV_P_REAL_WORLD_UNITS = 8 };
    enum { EVV_V_GENDER = 0, EVV_V_HEAD_SIZE = 1, EVV_V_PITCH = 2, EVV_V_FLUCTUATION = 3, EVV_V_ROUGHNESS = 4,
           EVV_V_BREATHINESS = 5, EVV_V_SPEED = 6, EVV_V_VOLUME = 7, EVV_V_COUNT = 8 };

    #if defined(_WIN32)
    #define STDCALL __attribute__((stdcall))
    #else
    #define STDCALL
    #endif

    OldInst *STDCALL eo_new(void);
    OldInst *STDCALL eo_newEx(int32_t language);
    int      STDCALL es_delete(OldInst *h);
    int      STDCALL et_addText(OldInst *h, const char *text);
    int      STDCALL et_synthesize(OldInst *h);
    int      STDCALL ev_setOutputBuffer(OldInst *h, int32_t n, void *buf);
    int32_t  STDCALL ev_setParam(OldInst *h, int32_t which, int32_t value);
    int32_t  STDCALL vc_getVoiceParam(OldInst *h, int32_t voice, int32_t which);
    int      STDCALL vc_setVoiceParam(OldInst *h, int32_t voice, int32_t which, int32_t value);
    int      STDCALL vc_copyVoice(OldInst *h, int32_t from, int32_t to);
    void     STDCALL eo_registerCallback(OldInst *h, void *cb, void *data);
    void     STDCALL eo_synchronizeSynth(OldInst *h);
    int      STDCALL eo_speaking(OldInst *h);
    int      STDCALL eo_stop(OldInst *h);
    int      STDCALL eo_getAvailableLanguages(uint32_t *out, int *count);

    void evvRunStaticInitialisers(void);
    void evv_port_start(void);
    void evv_port_finish(void);
    void evv_sleep_ms(int ms);
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
    if (inputSamplesCount <= 0 || !inputData) {
        return env->NewByteArray(0);
    }

    double ratio = static_cast<double>(sourceSampleRate) / TARGET_SAMPLE_RATE; // < 1 for upsampling
    if (ratio <= 0.0) ratio = 1.0;
    int outputSamplesCount = static_cast<int>(inputSamplesCount / ratio);
    if (outputSamplesCount <= 0) outputSamplesCount = 1;

    // Fast conversion to float buffer
    std::vector<float> src(inputSamplesCount);
    const uint8_t* in8 = static_cast<const uint8_t*>(inputData);
    const int16_t* in16 = static_cast<const int16_t*>(inputData);
    if (is8Bit) {
        for (int i = 0; i < inputSamplesCount; ++i) {
            src[i] = (static_cast<float>(in8[i]) - 128.0f) * 256.0f;
        }
    } else {
        for (int i = 0; i < inputSamplesCount; ++i) {
            src[i] = static_cast<float>(in16[i]);
        }
    }

    std::vector<int16_t> outputBuffer(outputSamplesCount);
    const std::vector<float>& bank = getFilterBank(sourceSampleRate);
    const int stride = 2 * RESAMPLER_HALF + 1; // 33 taps

    int peak = 0;
    int i = 0;

    // 1) Leading edge: center < RESAMPLER_HALF
    for (; i < outputSamplesCount; ++i) {
        double pos = i * ratio;
        int center = static_cast<int>(pos);
        if (center >= RESAMPLER_HALF) break;

        float frac = static_cast<float>(pos - center);
        int p = static_cast<int>(frac * RESAMPLER_PHASES) % RESAMPLER_PHASES;
        if (p < 0) p += RESAMPLER_PHASES;
        const float* w = &bank[p * stride];
        float acc = 0.0f;
        for (int k = -RESAMPLER_HALF; k <= RESAMPLER_HALF; ++k) {
            int idx = center + k;
            if (idx < 0) idx = 0;
            else if (idx >= inputSamplesCount) idx = inputSamplesCount - 1;
            acc += src[idx] * w[k + RESAMPLER_HALF];
        }
        int v = static_cast<int>(lrintf(acc));
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        outputBuffer[i] = static_cast<int16_t>(v);
        int a = v < 0 ? -v : v;
        if (a > peak) peak = a;
    }

    // 2) Interior bulk: branch-free, auto-vectorizable (NEON / SIMD)
    int interiorEnd = outputSamplesCount;
    for (int j = outputSamplesCount - 1; j >= i; --j) {
        double pos = j * ratio;
        int center = static_cast<int>(pos);
        if (center + RESAMPLER_HALF < inputSamplesCount) {
            interiorEnd = j + 1;
            break;
        }
    }

    for (; i < interiorEnd; ++i) {
        double pos = i * ratio;
        int center = static_cast<int>(pos);
        float frac = static_cast<float>(pos - center);
        int p = static_cast<int>(frac * RESAMPLER_PHASES) % RESAMPLER_PHASES;
        if (p < 0) p += RESAMPLER_PHASES;
        const float* w = &bank[p * stride];
        const float* s = &src[center - RESAMPLER_HALF];

        float acc = 0.0f;
        #pragma clang loop vectorize(enable)
        for (int k = 0; k < stride; ++k) {
            acc += s[k] * w[k];
        }
        int v = static_cast<int>(lrintf(acc));
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        outputBuffer[i] = static_cast<int16_t>(v);
        int a = v < 0 ? -v : v;
        if (a > peak) peak = a;
    }

    // 3) Trailing edge: center + RESAMPLER_HALF >= inputSamplesCount
    for (; i < outputSamplesCount; ++i) {
        double pos = i * ratio;
        int center = static_cast<int>(pos);
        float frac = static_cast<float>(pos - center);
        int p = static_cast<int>(frac * RESAMPLER_PHASES) % RESAMPLER_PHASES;
        if (p < 0) p += RESAMPLER_PHASES;
        const float* w = &bank[p * stride];
        float acc = 0.0f;
        for (int k = -RESAMPLER_HALF; k <= RESAMPLER_HALF; ++k) {
            int idx = center + k;
            if (idx < 0) idx = 0;
            else if (idx >= inputSamplesCount) idx = inputSamplesCount - 1;
            acc += src[idx] * w[k + RESAMPLER_HALF];
        }
        int v = static_cast<int>(lrintf(acc));
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        outputBuffer[i] = static_cast<int16_t>(v);
        int a = v < 0 ? -v : v;
        if (a > peak) peak = a;
    }

    // Peak-normalize headroom (~ -0.8 dBFS)
    const int targetPeak = 30000;
    if (peak > 0 && peak != targetPeak) {
        float scale = static_cast<float>(targetPeak) / peak;
        if (scale > 4.0f) scale = 4.0f; // cap boost for very quiet sources
        if (std::abs(scale - 1.0f) > 0.05f) {
            for (int k = 0; k < outputSamplesCount; ++k) {
                float val = outputBuffer[k] * scale;
                if (val > 32767.0f) val = 32767.0f;
                else if (val < -32768.0f) val = -32768.0f;
                outputBuffer[k] = static_cast<int16_t>(val);
            }
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

    size_t len = strlen(nativeString);
    if (len == 0) {
        env->ReleaseStringUTFChars(text, nativeString);
        return env->NewByteArray(0);
    }

    unsigned char input[2048];
    memset(input, 0, sizeof(input));
    if (len > sizeof(input) - 4) len = sizeof(input) - 4;
    memcpy(input, nativeString, len);
    input[len] = '\0';

    for (int k = 0; input[k] != 0; k++) {
        input[k] = (unsigned char)toupper((unsigned char)input[k]);
    }

    TextToPhonemes(input);
    strncat((char*)input, "\x9b", sizeof(input) - strlen((char*)input) - 1);

    SetInput((char*)input);

    // Default SAM: pitch = 64, speed = 72
    int samPitch = 64 * 100 / (pitch > 0 ? pitch : 100);
    if (samPitch < 0) samPitch = 0;
    if (samPitch > 255) samPitch = 255;

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
static bool g_dectalkInited = false;

short* dectalk_callback(short* wav, long numsamples, int events) {
    if (numsamples > 0 && wav != nullptr) {
        dectalk_buffer.insert(dectalk_buffer.end(), wav, wav + numsamples);
    }
    return wav;
}

static void ensureDectalk() {
    if (!g_dectalkInited) {
        TextToSpeechInit(dectalk_callback, nullptr);
        g_dectalkInited = true;
    }
}

jbyteArray doSynthDectalk(JNIEnv* env, jstring text, jint pitch, jint speechRate) {
    const char *nativeString = env->GetStringUTFChars(text, 0);
    if (!nativeString) return env->NewByteArray(0);

    ensureDectalk();
    dectalk_buffer.clear();

    // DECtalk defaults: rate = 180, ap = 122
    int dtRate = 180 * (speechRate > 0 ? speechRate : 100) / 100;
    TextToSpeechSetRate(dtRate);

    int dtPitch = 122 * (pitch > 0 ? pitch : 100) / 100;
    TextToSpeechSetVoiceParam("ap", dtPitch);

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

static bool ensureEspeak(const char* path) {
    if (g_espeakInited) return true;
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

// ================= openevv (Eloquence) Core =================
static std::vector<short> openevv_buffer;
static const int EVV_FRAME_SIZE = 2048;
static short evv_frame_buf[EVV_FRAME_SIZE];
static OldInst *g_openevvEngine = nullptr;
static std::atomic<bool> g_cancelRequested{false};

static enum ECICallbackReturn STDCALL openevv_callback(OldInst *h, enum ECIMessage msg, long param, void *data) {
    (void)h;
    (void)data;
    if (msg == eciWaveformBuffer && param > 0) {
        openevv_buffer.insert(openevv_buffer.end(), evv_frame_buf, evv_frame_buf + param);
    }
    return g_cancelRequested.load() ? eciDataAbort : eciDataProcessed;
}

static bool ensureOpenevv() {
    if (g_openevvEngine != nullptr) return true;
    evv_port_start();
    evvRunStaticInitialisers();
    uint32_t langs[32];
    int count = 32;
    if (eo_getAvailableLanguages(langs, &count) != 0 || count < 1) {
        return false;
    }
    g_openevvEngine = eo_new();
    if (!g_openevvEngine) {
        g_openevvEngine = eo_newEx(langs[0]);
    }
    if (!g_openevvEngine) {
        return false;
    }
    eo_registerCallback(g_openevvEngine, (void*)openevv_callback, nullptr);
    if (!ev_setOutputBuffer(g_openevvEngine, EVV_FRAME_SIZE, evv_frame_buf)) {
        return false;
    }
    return true;
}

jbyteArray doSynthOpenevv(JNIEnv* env, jstring text, jint voiceIndex, jint pitch, jint speechRate) {
    const char *nativeText = env->GetStringUTFChars(text, 0);
    if (!nativeText) return env->NewByteArray(0);

    if (!ensureOpenevv()) {
        env->ReleaseStringUTFChars(text, nativeText);
        return env->NewByteArray(0);
    }

    g_cancelRequested.store(false);
    openevv_buffer.clear();

    // Voice preset: 1 to 8 (Reed, Shelley, Bobby, Glen, Sandy, Grandma, Grandpa, Rocko)
    int vIdx = voiceIndex;
    if (vIdx < 1 || vIdx > 8) vIdx = 1;
    vc_copyVoice(g_openevvEngine, vIdx, 0);

    // Scale speed (default ~50 in engine units) and pitch (default ~65 in engine units)
    int baseSpeed = vc_getVoiceParam(g_openevvEngine, vIdx, EVV_V_SPEED);
    int basePitch = vc_getVoiceParam(g_openevvEngine, vIdx, EVV_V_PITCH);

    int newSpeed = baseSpeed * (speechRate > 0 ? speechRate : 100) / 100;
    if (newSpeed < 10) newSpeed = 10;
    if (newSpeed > 250) newSpeed = 250;

    int newPitch = basePitch * (pitch > 0 ? pitch : 100) / 100;
    if (newPitch < 10) newPitch = 10;
    if (newPitch > 100) newPitch = 100;

    vc_setVoiceParam(g_openevvEngine, 0, EVV_V_SPEED, newSpeed);
    vc_setVoiceParam(g_openevvEngine, 0, EVV_V_PITCH, newPitch);

    if (et_addText(g_openevvEngine, nativeText) && et_synthesize(g_openevvEngine)) {
        for (int i = 0; i < 30000 && !g_cancelRequested.load() && eo_speaking(g_openevvEngine); ++i) {
            evv_sleep_ms(1);
        }
    }
    eo_synchronizeSynth(g_openevvEngine);

    jbyteArray result = upscaleAndSmooth(env, openevv_buffer.data(), (int)openevv_buffer.size(), 11025, false);

    env->ReleaseStringUTFChars(text, nativeText);
    return result;
}

void doCancelEspeak() {
    if (g_espeakInited) espeak_Cancel();
}

void doCancelOpenevv() {
    if (g_openevvEngine != nullptr) {
        eo_stop(g_openevvEngine);
    }
}

// ================= JNI EXPORTS =================
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
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_MainActivity_synthOpenevv(JNIEnv* env, jobject, jstring text, jint voiceIndex, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthOpenevv(env, text, voiceIndex, pitch, speechRate);
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
    JNIEXPORT jbyteArray JNICALL Java_com_animeshahilya_retrotts_RetroTtsService_synthOpenevv(JNIEnv* env, jobject, jstring text, jint voiceIndex, jint pitch, jint speechRate) {
        std::lock_guard<std::mutex> lock(g_synthMutex);
        return doSynthOpenevv(env, text, voiceIndex, pitch, speechRate);
    }

    // Lock-free instant cancellation for TalkBack responsiveness
    JNIEXPORT void JNICALL Java_com_animeshahilya_retrotts_RetroTtsService_cancelSynth(JNIEnv*, jobject) {
        g_cancelRequested.store(true);
        doCancelEspeak();
        doCancelOpenevv();
    }
}
