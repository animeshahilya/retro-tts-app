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
        // Extract the bundled mbrola executable (arm64) next to the data so eSpeak-NG's
        // execlp("mbrola") can find it when an MBROLA voice is selected.
        extractMbrola(this)
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val l = lang.lowercase()
        // eSpeak can serve any language whose voice file ships in espeak-ng-data/voices; SAM and
        // DECtalk are English-only. We derive eSpeak's supported set from the unpacked data so a
        // fuller voice pack (more languages) is unlocked automatically without code changes.
        val supported = if (activeEngine == "ESPEAK" || activeEngine.startsWith("ESPEAK")) {
            espeakLangs
        } else {
            setOf("en", "eng")
        }
        val base = if (l.length >= 2) l.substring(0, 2) else l
        if (supported.contains(base) || supported.contains(l)) {
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

        // Migrate legacy "ESPEAK (variant)" engine keys to the unified ESPEAK engine + espeak_voice pref.
        if (activeEngine.startsWith("ESPEAK") && activeEngine != "ESPEAK") {
            val v = activeEngine.removePrefix("ESPEAK").trim().removeSurrounding("(", ")")
            prefs.edit().putString("espeak_voice", "en+$v").apply()
            prefs.edit().putString("active_engine", "ESPEAK").apply()
            activeEngine = "ESPEAK"
        }

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
            } else if (activeEngine == "ESPEAK" || activeEngine.startsWith("ESPEAK")) {
                // Use the user-selected eSpeak voice (default "en"); variant picks like
                // "en+robosoft" or a full language voice such as "fr" unlock more voices.
                val voice = prefs.getString("espeak_voice", "en") ?: "en"
                // Pass the files dir (which contains espeak-ng-data), NOT a non-existent
                // "espeakdata" subdir. A wrong path makes espeak_Initialize fail.
                synthEspeak(text, filesDir.absolutePath, voice, pitch, speechRate)
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

    // Languages eSpeak can actually speak, derived from the unpacked voice files so the set grows
    // automatically when a voice pack with more languages is supplied. Computed once.
    private val espeakLangs: Set<String> by lazy {
        val dir = File(filesDir, "espeak-ng-data")
        val langs = mutableSetOf<String>()
        // Language voices live under lang/<family>/<code>; collect their 2-letter codes.
        File(dir, "lang").listFiles()?.forEach { family ->
            if (family.isDirectory) {
                family.listFiles()?.filter { it.isFile }?.forEach { f ->
                    val code = f.name.lowercase()
                    if (code.length >= 2) langs.add(code.substring(0, 2))
                }
            }
        }
        // Variant voices (!v) are English-based; ensure "en" is reported.
        File(File(dir, "voices"), "!v").listFiles()?.filter { it.isFile }?.forEach { langs.add("en") }
        if (langs.isEmpty()) langs.add("en")
        langs
    }

    companion object {
        init {
            System.loadLibrary("retro-tts")
        }
    }
}
