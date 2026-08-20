package com.animeshahilya.retrotts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.io.File

class RetroTtsService : TextToSpeechService() {

    private var activeEngine: String = "SAM"

    override fun onCreate() {
        super.onCreate()
        // Unpack espeak data if needed
        val espeakDataPath = File(filesDir, "espeakdata")
        if (!espeakDataPath.exists()) {
            MainActivity.unpackEspeakData(this)
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun onStop() {
        // Stop any ongoing synthesis if possible
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText?.toString() ?: return
        if (callback == null) return

        // Read active engine from SharedPreferences
        val prefs = getSharedPreferences("retro_tts_prefs", Context.MODE_PRIVATE)
        activeEngine = prefs.getString("active_engine", "SAM") ?: "SAM"

        // Set audio parameters based on the engine
        val sampleRate = 48000
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        callback.start(sampleRate, audioFormat, 1)

        val pitch = request.pitch
        val speechRate = request.speechRate

        val pcmData = try {
            if (activeEngine == "SAM") {
                synthSam(text, pitch, speechRate)
            } else if (activeEngine == "DECTALK") {
                synthDectalk(text, pitch, speechRate)
            } else if (activeEngine.startsWith("ESPEAK")) {
                val variant = when (activeEngine) {
                    "ESPEAK (whisper)" -> "en+whisper"
                    "ESPEAK (croak)" -> "en+croak"
                    "ESPEAK (klatt)" -> "en+klatt"
                    "ESPEAK (m1)" -> "en+m1"
                    "ESPEAK (f1)" -> "en+f1"
                    "ESPEAK (robosoft)" -> "en+robosoft"
                    "ESPEAK (robosoft8)" -> "en+robosoft8"
                    "ESPEAK (yelling)" -> "en+yelling"
                    else -> "en"
                }
                synthEspeak(text, File(filesDir, "espeakdata").absolutePath, variant, pitch, speechRate)
            } else {
                ByteArray(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }

        if (pcmData.isNotEmpty()) {
            callback.audioAvailable(pcmData, 0, pcmData.size)
        }

        callback.done()
    }

    // JNI External functions
    private external fun synthSam(text: String, pitch: Int, speechRate: Int): ByteArray
    private external fun synthDectalk(text: String, pitch: Int, speechRate: Int): ByteArray
    private external fun synthEspeak(text: String, dataPath: String, voiceName: String, pitch: Int, speechRate: Int): ByteArray

    companion object {
        init {
            System.loadLibrary("retro-tts")
        }
    }
}

