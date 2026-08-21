package com.animeshahilya.retrotts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

// A single eSpeak voice entry surfaced in the UI. `id` is what eSpeak expects
// (e.g. "en", "en+robosoft", "fr"); `label` is the human-readable name.
data class EspeakVoice(val id: String, val label: String)

// Enumerate the eSpeak voices that actually shipped in espeak-ng-data/voices. Top-level files are
// base-language voices; the !v directory holds the many named/retro variant voices. The list is
// derived from the unpacked data so a fuller voice pack unlocks more entries automatically.
fun loadEspeakVoices(context: Context): List<EspeakVoice> {
    val voicesDir = File(context.filesDir, "espeak-ng-data/voices")
    if (!voicesDir.isDirectory) return listOf(EspeakVoice("en", "English (default)"))
    val voices = mutableListOf<EspeakVoice>()
    voicesDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted()?.forEach { lang ->
        voices.add(EspeakVoice(lang, lang))
    }
    val variantsDir = File(voicesDir, "!v")
    variantsDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted()?.forEach { v ->
        voices.add(EspeakVoice("en+$v", "English + $v"))
    }
    if (voices.isEmpty()) voices.add(EspeakVoice("en", "English (default)"))
    return voices
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        System.loadLibrary("retro-tts")

        // Unpack espeak data once (zip contents land under filesDir/espeak-ng-data).
        if (!File(filesDir, "espeak-ng-data").exists()) {
            unpackEspeakData(this)
        }

        setContent {
            MaterialTheme {
                TTSApp(filesDir.absolutePath)
            }
        }
    }

    external fun synthSam(text: String, pitch: Int, speechRate: Int): ByteArray
    external fun synthDectalk(text: String, pitch: Int, speechRate: Int): ByteArray
    external fun synthEspeak(text: String, dataPath: String, voiceName: String, pitch: Int, speechRate: Int): ByteArray

    @Composable
    fun TTSApp(dataPath: String) {
        val haptic = LocalHapticFeedback.current
        var text by remember { mutableStateOf("Hello, I am a retro voice.") }
        
        val prefs = getSharedPreferences("retro_tts_prefs", Context.MODE_PRIVATE)
        var selectedEngine by remember { mutableStateOf(prefs.getString("active_engine", "SAM") ?: "SAM") }
        var espeakVoice by remember { mutableStateOf(prefs.getString("espeak_voice", "en") ?: "en") }

        // Migrate legacy "ESPEAK (variant)" engine keys to the unified ESPEAK engine + espeak_voice pref.
        if (selectedEngine.startsWith("ESPEAK") && selectedEngine != "ESPEAK") {
            val v = selectedEngine.removePrefix("ESPEAK").trim().removeSurrounding("(", ")")
            espeakVoice = "en+$v"
            prefs.edit().putString("espeak_voice", espeakVoice).apply()
            selectedEngine = "ESPEAK"
            prefs.edit().putString("active_engine", "ESPEAK").apply()
        }

        val espeakVoices = remember { loadEspeakVoices(this@MainActivity) }

        var pitch by remember { mutableStateOf(100f) }
        var speechRate by remember { mutableStateOf(100f) }
        
        val coroutineScope = rememberCoroutineScope()
        var isSpeaking by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Retro TTS Settings", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (isSpeaking) return@ExtendedFloatingActionButton
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isSpeaking = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val p = pitch.toInt()
                                val r = speechRate.toInt()
                                if (selectedEngine == "SAM") {
                                    val pcm = synthSam(text, p, r)
                                    playAudio(pcm, 48000, AudioFormat.ENCODING_PCM_16BIT)
                                } else if (selectedEngine == "DECTALK") {
                                    val pcm = synthDectalk(text, p, r)
                                    playAudio(pcm, 48000, AudioFormat.ENCODING_PCM_16BIT)
                                } else if (selectedEngine.startsWith("ESPEAK")) {
                                    val pcm = synthEspeak(text, dataPath, espeakVoice, p, r)
                                    playAudio(pcm, 48000, AudioFormat.ENCODING_PCM_16BIT)
                                }
                            } finally {
                                isSpeaking = false
                            }
                        }
                    },
                    icon = { Text("▶") },
                    text = { Text(if (isSpeaking) "Speaking..." else "Test Voice") },
                    modifier = Modifier.semantics { contentDescription = "Play speech synthesis preview" }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Preview Text",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Text input field for speech synthesis testing" },
                            maxLines = 3
                        )
                    }
                }
                
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Voice Engine Options",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Column(Modifier.selectableGroup()) {
                            val engines = listOf(
                                "SAM" to "Software Automatic Mouth",
                                "DECTALK" to "DECtalk (Perfect Paul)",
                                "ESPEAK" to "eSpeak NG"
                            )
                            engines.forEach { (engine, description) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .selectable(
                                            selected = (engine == selectedEngine),
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectedEngine = engine
                                                prefs.edit().putString("active_engine", engine).apply()
                                            },
                                            role = Role.RadioButton
                                        )
                                        .semantics(mergeDescendants = true) {
                                            // Merge the label + radio state into a single announcement so
                                            // TalkBack says e.g. "Software Automatic Mouth, selected"
                                            // rather than the row text, the radio control, and the state separately.
                                            contentDescription = description
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (engine == selectedEngine),
                                        onClick = null
                                    )
                                    Text(
                                        text = engine,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                }
                            }
                        }

                        // Full eSpeak voice list (the "og" UI): hundreds of variants + any language voices
                        // present in the data pack. Selection is stored in `espeak_voice`.
                        if (selectedEngine == "ESPEAK") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "eSpeak Voice",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                                    .selectableGroup()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                            ) {
                                items(espeakVoices) { voice ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .selectable(
                                                selected = (voice.id == espeakVoice),
                                                onClick = {
                                                    espeakVoice = voice.id
                                                    prefs.edit().putString("espeak_voice", voice.id).apply()
                                                },
                                                role = Role.RadioButton
                                            )
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = voice.label
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (voice.id == espeakVoice),
                                            onClick = null
                                        )
                                        Text(
                                            text = voice.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Voice Parameters",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics { heading() }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text("Pitch: ${pitch.toInt()}%")
                        Slider(
                            value = pitch,
                            onValueChange = { pitch = it },
                            valueRange = 50f..200f,
                            modifier = Modifier.semantics { contentDescription = "Pitch" }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Speech Rate: ${speechRate.toInt()}%")
                        Slider(
                            value = speechRate,
                            onValueChange = { speechRate = it },
                            valueRange = 50f..200f,
                            modifier = Modifier.semantics { contentDescription = "Speech Rate" }
                        )
                    }
                }
                
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .semantics { contentDescription = "Open System TTS and Accessibility Settings to set Retro TTS as default" },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("⚙", modifier = Modifier.padding(end = 8.dp))
                    Text("Open System TTS Settings")
                }
                
                Spacer(modifier = Modifier.height(80.dp)) // FAB clearance
            }
        }
    }

    private fun playAudio(pcmData: ByteArray, sampleRate: Int, audioFormat: Int) {
        if (pcmData.isEmpty()) return
        
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioFormat
        )
        
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(audioFormat)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(pcmData.size.coerceAtLeast(minBufferSize))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            
        audioTrack.play()
        audioTrack.write(pcmData, 0, pcmData.size)
        audioTrack.stop()
        audioTrack.release()
    }

    companion object {
        fun unpackEspeakData(context: Context) {
            try {
                if (File(context.filesDir, "espeak-ng-data").exists()) return
                val resId = context.resources.getIdentifier("espeakdata", "raw", context.packageName)
                if (resId == 0) return
                val inputStream = context.resources.openRawResource(resId)
                val zipInputStream = ZipInputStream(inputStream)
                
                var zipEntry = zipInputStream.nextEntry
                while (zipEntry != null) {
                    val newFile = File(context.filesDir, zipEntry.name)
                    if (zipEntry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        val fos = FileOutputStream(newFile)
                        val buffer = ByteArray(1024)
                        var len: Int
                        while (zipInputStream.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                        fos.close()
                    }
                    zipInputStream.closeEntry()
                    zipEntry = zipInputStream.nextEntry
                }
                zipInputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
