package com.example.retrottsapp

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

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        System.loadLibrary("retro-tts")

        // Unpack espeakdata.zip always to get new variants
        unpackEspeakData(this)

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
                                    val variant = when (selectedEngine) {
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
                                    val pcm = synthEspeak(text, dataPath, variant, p, r)
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
                                "ESPEAK (default)" to "Standard eSpeak",
                                "ESPEAK (whisper)" to "eSpeak Whisper Variant",
                                "ESPEAK (croak)" to "eSpeak Croak Variant",
                                "ESPEAK (klatt)" to "eSpeak Klatt Variant",
                                "ESPEAK (robosoft)" to "eSpeak Robosoft Variant",
                                "ESPEAK (yelling)" to "eSpeak Yelling Variant"
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
                                        .semantics { 
                                            contentDescription = description
                                            stateDescription = if (engine == selectedEngine) "Selected" else "Not selected"
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (engine == selectedEngine),
                                        onClick = null // TalkBack uses the Row semantic
                                    )
                                    Text(
                                        text = engine,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
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
                        
                        Text("Pitch: ${pitch.toInt()}%", modifier = Modifier.semantics { contentDescription = "Pitch slider, ${pitch.toInt()} percent" })
                        Slider(
                            value = pitch,
                            onValueChange = { pitch = it },
                            valueRange = 50f..200f,
                            modifier = Modifier.semantics { contentDescription = "Adjust Pitch" }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Speech Rate: ${speechRate.toInt()}%", modifier = Modifier.semantics { contentDescription = "Speech Rate slider, ${speechRate.toInt()} percent" })
                        Slider(
                            value = speechRate,
                            onValueChange = { speechRate = it },
                            valueRange = 50f..200f,
                            modifier = Modifier.semantics { contentDescription = "Adjust Speech Rate" }
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
