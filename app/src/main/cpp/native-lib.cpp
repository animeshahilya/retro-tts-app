#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cctype>
#include <cmath>
#include <algorithm>

extern "C" {
    #include "reciter.h"
    #include "sam.h"
    #include "epsonapi.h"
    // eSpeak headers
    #define espeakRATE 1
    #define espeakPITCH 3
    typedef enum {
        EE_OK=0,
        EE_INTERNAL_ERROR=-1,
        EE_BUFFER_FULL=1,
        EE_NOT_FOUND=2
    } espeak_ERROR;
    int espeak_Initialize(int output, int buflength, const char *path, int options);
    espeak_ERROR espeak_Synth(const void *text, size_t size, unsigned int position, int position_type, unsigned int end_position, unsigned int flags, unsigned int *unique_identifier, void *user_data);
    void espeak_SetSynthCallback(int (*SynthCallback)(short *wav, int numsamples, int events));
    espeak_ERROR espeak_SetParameter(int parameter, int value, int relative);
    espeak_ERROR espeak_SetVoiceByName(const char *name);
    int debug = 0;
}

const int TARGET_SAMPLE_RATE = 48000;

// Cubic Hermite Spline interpolation
float cubicInterpolate(float y0, float y1, float y2, float y3, float mu) {
    float a0, a1, a2, a3, mu2;
    mu2 = mu * mu;
    a0 = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3;
    a1 = y0 - 2.5f * y1 + 2.0f * y2 - 0.5f * y3;
    a2 = -0.5f * y0 + 0.5f * y2;
    a3 = y1;
    return (a0 * mu * mu2 + a1 * mu2 + a2 * mu + a3);
}

// Low-pass filter (1-pole IIR) to remove aliasing harshness
void applyLowPassFilter(std::vector<int16_t>& samples, float alpha) {
    if (samples.empty()) return;
    float prev = samples[0];
    for (size_t i = 1; i < samples.size(); ++i) {
        float current = samples[i];
        prev = prev + alpha * (current - prev);
        samples[i] = static_cast<int16_t>(prev);
    }
}

jbyteArray upscaleAndSmooth(JNIEnv* env, const void* inputData, int inputSamplesCount, int sourceSampleRate, bool is8Bit) {
    if (inputSamplesCount <= 0) {
        return env->NewByteArray(0);
    }

    const uint8_t* in8 = static_cast<const uint8_t*>(inputData);
    const int16_t* in16 = static_cast<const int16_t*>(inputData);

    auto getSample = [&](int index) -> float {
        if (index < 0) index = 0;
        if (index >= inputSamplesCount) index = inputSamplesCount - 1;
        if (is8Bit) {
            return (static_cast<float>(in8[index]) - 128.0f) * 256.0f;
        } else {
            return static_cast<float>(in16[index]);
        }
    };

    double ratio = static_cast<double>(sourceSampleRate) / TARGET_SAMPLE_RATE;
    int outputSamplesCount = static_cast<int>(inputSamplesCount / ratio);
    std::vector<int16_t> outputBuffer(outputSamplesCount);

    for (int i = 0; i < outputSamplesCount; ++i) {
        double position = i * ratio;
        int idx = static_cast<int>(position);
        float mu = static_cast<float>(position - idx);

        float y0 = getSample(idx - 1);
        float y1 = getSample(idx);
        float y2 = getSample(idx + 1);
        float y3 = getSample(idx + 2);

        float interpolated = cubicInterpolate(y0, y1, y2, y3, mu);
        
        if (interpolated > 32767.0f) interpolated = 32767.0f;
        if (interpolated < -32768.0f) interpolated = -32768.0f;
        
        outputBuffer[i] = static_cast<int16_t>(interpolated);
    }

    float alpha = is8Bit ? 0.35f : (sourceSampleRate < 20000 ? 0.45f : 0.6f);
    applyLowPassFilter(outputBuffer, alpha);

    for (int i = 0; i < outputSamplesCount; ++i) {
        float val = outputBuffer[i] * 1.2f;
        if (val > 32767.0f) val = 32767.0f;
        if (val < -32768.0f) val = -32768.0f;
        outputBuffer[i] = static_cast<int16_t>(val);
    }

    int outBytes = outputSamplesCount * sizeof(int16_t);
    jbyteArray result = env->NewByteArray(outBytes);
    env->SetByteArrayRegion(result, 0, outBytes, reinterpret_cast<const jbyte*>(outputBuffer.data()));
    return result;
}

// ================= SAM Core =================
jbyteArray doSynthSam(JNIEnv* env, jstring text, jint pitch, jint speechRate) {
    const char *nativeString = env->GetStringUTFChars(text, 0);
    
    unsigned char input[256];
    memset(input, 0, 256);
    strncpy((char*)input, nativeString, 255);
    
    for(int i = 0; input[i] != 0; i++) {
        input[i] = toupper(input[i]);
    }
    
    TextToPhonemes(input);
    strcat((char*)input, "\x9b");
    
    SetInput((char*)input);
    
    // Default SAM: pitch = 64, speed = 72
    // Lower pitch value = higher voice in SAM
    int samPitch = 64 * 100 / (pitch > 0 ? pitch : 100);
    if(samPitch < 0) samPitch = 0;
    if(samPitch > 255) samPitch = 255;
    
    // Lower speed value = faster voice in SAM
    int samSpeed = 72 * 100 / (speechRate > 0 ? speechRate : 100);
    if(samSpeed < 0) samSpeed = 0;
    if(samSpeed > 255) samSpeed = 255;
    
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
    
    dectalk_buffer.clear();
    
    TextToSpeechInit(dectalk_callback, nullptr);
    
    // DECtalk defaults: rate = 180, ap = 122
    int dtRate = 180 * (speechRate > 0 ? speechRate : 100) / 100;
    TextToSpeechSetRate(dtRate);
    
    int dtPitch = 122 * (pitch > 0 ? pitch : 100) / 100;
    TextToSpeechSetVoiceParam("ap", dtPitch);
    
    char inputBuf[1024];
    memset(inputBuf, 0, 1024);
    strncpy(inputBuf, nativeString, 1023);
    
    TextToSpeechStart(inputBuf, nullptr, 0);
    TextToSpeechSync();
    
    jbyteArray result = upscaleAndSmooth(env, dectalk_buffer.data(), dectalk_buffer.size(), 11025, false);
    
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

jbyteArray doSynthEspeak(JNIEnv* env, jstring text, jstring dataPath, jstring voiceName, jint pitch, jint speechRate) {
    const char *nativeText = env->GetStringUTFChars(text, 0);
    const char *nativeDataPath = env->GetStringUTFChars(dataPath, 0);
    const char *nativeVoiceName = env->GetStringUTFChars(voiceName, 0);
    
    espeak_buffer.clear();
    
    espeak_Initialize(1, 0, nativeDataPath, 0); // 1 = AUDIO_OUTPUT_RETRIEVAL
    espeak_SetSynthCallback(espeak_callback);
    espeak_SetVoiceByName(nativeVoiceName);
    
    // eSpeak defaults: rate = 175, pitch = 50
    int esRate = 175 * (speechRate > 0 ? speechRate : 100) / 100;
    int esPitch = 50 * (pitch > 0 ? pitch : 100) / 100;
    
    espeak_SetParameter(espeakRATE, esRate, 0);
    espeak_SetParameter(espeakPITCH, esPitch, 0);
    
    espeak_Synth(nativeText, strlen(nativeText)+1, 0, 1, 0, 0x1, nullptr, nullptr); // 1 = POS_CHARACTER
    
    jbyteArray result = upscaleAndSmooth(env, espeak_buffer.data(), espeak_buffer.size(), 22050, false);
    
    env->ReleaseStringUTFChars(text, nativeText);
    env->ReleaseStringUTFChars(dataPath, nativeDataPath);
    env->ReleaseStringUTFChars(voiceName, nativeVoiceName);
    return result;
}

// ================= JNI EXPORTS =================

extern "C" {
    // MainActivity bindings
    JNIEXPORT jbyteArray JNICALL Java_com_example_retrottsapp_MainActivity_synthSam(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        return doSynthSam(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_example_retrottsapp_MainActivity_synthDectalk(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        return doSynthDectalk(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_example_retrottsapp_MainActivity_synthEspeak(JNIEnv* env, jobject, jstring text, jstring dataPath, jstring voiceName, jint pitch, jint speechRate) {
        return doSynthEspeak(env, text, dataPath, voiceName, pitch, speechRate);
    }

    // RetroTtsService bindings
    JNIEXPORT jbyteArray JNICALL Java_com_example_retrottsapp_RetroTtsService_synthSam(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        return doSynthSam(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_example_retrottsapp_RetroTtsService_synthDectalk(JNIEnv* env, jobject, jstring text, jint pitch, jint speechRate) {
        return doSynthDectalk(env, text, pitch, speechRate);
    }
    JNIEXPORT jbyteArray JNICALL Java_com_example_retrottsapp_RetroTtsService_synthEspeak(JNIEnv* env, jobject, jstring text, jstring dataPath, jstring voiceName, jint pitch, jint speechRate) {
        return doSynthEspeak(env, text, dataPath, voiceName, pitch, speechRate);
    }
}

