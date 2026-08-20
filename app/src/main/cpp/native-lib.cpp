#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>
#include <cctype>

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
    
    jbyteArray result = env->NewByteArray(length);
    if(length > 0) {
        env->SetByteArrayRegion(result, 0, length, (const jbyte*)buffer);
    }
    
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
    
    int byteLength = dectalk_buffer.size() * 2;
    jbyteArray result = env->NewByteArray(byteLength);
    if(byteLength > 0) {
        env->SetByteArrayRegion(result, 0, byteLength, (const jbyte*)dectalk_buffer.data());
    }
    
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
    
    int byteLength = espeak_buffer.size() * 2;
    jbyteArray result = env->NewByteArray(byteLength);
    if(byteLength > 0) {
        env->SetByteArrayRegion(result, 0, byteLength, (const jbyte*)espeak_buffer.data());
    }
    
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

