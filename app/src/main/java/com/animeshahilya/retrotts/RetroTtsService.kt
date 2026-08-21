package com.animeshahilya.retrotts

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RetroTtsService : TextToSpeechService() {

    private var activeEngine: String = "SAM"
    private val stopRequested = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Unpack espeak data once. The zip top-level directory is "espeak-ng-data", so gate on
        // that path (not the previously-used bogus "espeakdata" directory).
        val espeakDataPath = File(filesDir, "espeak-ng-data")
        if (!espeakDataPath.exists()) {
            MainActivity.unpackEspeakData(this)
        }
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // All bundled engines are English-only. Claim support for English, reject everything else
        // so the framework falls back to a real multilingual engine instead of emitting English for
        // e.g. a French utterance.
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val l = lang.lowercase()
        if (l == "eng" || l == "en") {
            return if (country == null || country.equals("USA", ignoreCase = true) || country.equals("GBR", ignoreCase = true)) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onStop() {
        // Ask any in-flight (eSpeak) synthesis to abort and stop delivering audio.
        stopRequested.set(true)
        cancelSynth()
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        val text = request?.charSequenceText?.toString() ?: return
        if (callback == null) return

        stopRequested.set(false)

        // Read active engine from SharedPreferences
        val prefs = getSharedPreferences("retro_tts_prefs", Context.MODE_PRIVATE)
        activeEngine = prefs.getString("active_engine", "SAM") ?: "SAM"

        // All engines are normalized to 48 kHz / 16-bit mono by the native layer.
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
                // Pass the files dir (which contains espeak-ng-data), NOT a non-existent
                // "espeakdata" subdir. A wrong path makes espeak_Initialize fail.
                synthEspeak(text, filesDir.absolutePath, variant, pitch, speechRate)
            } else {
                ByteArray(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }

        if (pcmData.isEmpty()) {
            // Report failure so the framework can fall back to another engine instead of
            // signalling a silent success.
            callback.error(TextToSpeech.ERROR)
            return
        }

        // Stream in chunks so an onStop() can interrupt delivery and TalkBack stays responsive.
        var offset = 0
        val chunkSize = 4096
        while (offset < pcmData.size) {
            if (stopRequested.get()) break
            val count = minOf(chunkSize, pcmData.size - offset)
            callback.audioAvailable(pcmData, offset, count)
            offset += count
        }

        callback.done()
    }

    // JNI External functions
    private external fun synthSam(text: String, pitch: Int, speechRate: Int): ByteArray
    private external fun synthDectalk(text: String, pitch: Int, speechRate: Int): ByteArray
    private external fun synthEspeak(text: String, dataPath: String, voiceName: String, pitch: Int, speechRate: Int): ByteArray
    private external fun cancelSynth()

    companion object {
        init {
            System.loadLibrary("retro-tts")
        }
    }
}
