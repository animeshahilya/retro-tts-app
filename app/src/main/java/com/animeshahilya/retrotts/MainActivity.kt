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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

// A single eSpeak voice entry surfaced in the UI. `id` is what eSpeak expects
// (e.g. "en", "en+robosoft", "fr"); `label` is the human-readable name.
data class EspeakVoice(val id: String, val label: String)

// 8 classic formant voice presets of IBM Embedded ViaVoice / Eloquence (openevv)
data class EloquenceVoice(val id: Int, val name: String, val description: String)

val eloquenceVoices = listOf(
    EloquenceVoice(1, "Reed", "Adult Male 1 (Default)"),
    EloquenceVoice(2, "Shelley", "Adult Female 1"),
    EloquenceVoice(3, "Bobby", "Child"),
    EloquenceVoice(4, "Glen", "Adult Male 2"),
    EloquenceVoice(5, "Sandy", "Adult Female 2"),
    EloquenceVoice(6, "Grandma", "Elderly Female"),
    EloquenceVoice(7, "Grandpa", "Elderly Male"),
    EloquenceVoice(8, "Rocko", "Male Character")
)

// 6 classic DECtalk voices — each maps to a distinct pitch/voice preset in the native layer
data class DectalkVoice(val id: String, val label: String, val pitchOffset: Int)
val dectalkVoices = listOf(
    DectalkVoice("Paul", "Perfect Paul (Default)", 0),
    DectalkVoice("Betty", "Beautiful Betty", 25),
    DectalkVoice("Harry", "Huge Harry", -20),
    DectalkVoice("Frank", "Frail Frank", 15),
    DectalkVoice("Dennis", "Doctor Dennis", -10),
    DectalkVoice("Kit", "Kit the Kid", 35)
)

// Enumerate the eSpeak voices present in the unpacked espeak-ng-data. Language voices live under
// lang/<family>/<code> (e.g. lang/roa/fr); the !v directory holds the many named "retro" variant
// voices (selected as en+<variant>). The list is derived from the data, so a fuller voice pack
// (more languages) is unlocked automatically without code changes.
fun loadEspeakVoices(context: Context): List<EspeakVoice> {
    val dataDir = File(context.filesDir, "espeak-ng-data")
    if (!dataDir.isDirectory) return listOf(EspeakVoice("en", "English (default)"))
    val voices = mutableListOf<EspeakVoice>()
    // Language voices: lang/<family>/<code>
    val langDir = File(dataDir, "lang")
    langDir.listFiles()?.filter { it.isDirectory }?.forEach { family ->
        family.listFiles()?.filter { it.isFile }?.forEach { f ->
            voices.add(EspeakVoice(f.name, f.name))
        }
    }
    // Retro/robot variant voices: voices/!v (selected as en+<variant>)
    val variantsDir = File(File(dataDir, "voices"), "!v")
    variantsDir.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted()?.forEach { v ->
        voices.add(EspeakVoice("en+$v", "en + $v"))
    }
    if (voices.isEmpty()) voices.add(EspeakVoice("en", "English (default)"))
    return voices
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        System.loadLibrary("retro-tts")

        // Unpack espeak data asynchronously on IO thread if not already unpacked
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            if (!File(filesDir, "espeak-ng-data").exists()) {
                unpackEspeakData(this@MainActivity)
            }
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
    external fun synthOpenevv(text: String, voice: Int, pitch: Int, speechRate: Int): ByteArray
    external fun synthSp0256(text: String, pitch: Int, speechRate: Int): ByteArray
    external fun synthVotrax(text: String, pitch: Int, speechRate: Int): ByteArray

    @Composable
    fun TTSApp(dataPath: String) {
        val haptic = LocalHapticFeedback.current
        var text by remember { mutableStateOf("Hello, I am a retro voice.") }
        
        val prefs = getSharedPreferences("retro_tts_prefs", Context.MODE_PRIVATE)
        var selectedEngine by remember { mutableStateOf(prefs.getString("active_engine", "SAM") ?: "SAM") }
        var espeakVoice by remember { mutableStateOf(prefs.getString("espeak_voice", "en") ?: "en") }
        var openevvVoice by remember { mutableStateOf(prefs.getInt("openevv_voice", 1)) }
        var dectalkVoice by remember { mutableStateOf(prefs.getString("dectalk_voice", "Paul") ?: "Paul") }

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
        var espeakSearchQuery by remember { mutableStateOf("") }
        
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
                        coroutineScope.launch {
                            isSpeaking = true
                            try {
                                withContext(Dispatchers.IO) {
                                    val p = pitch.toInt()
                                    val r = speechRate.toInt()
                                    val pcm = when (selectedEngine) {
                                        "SAM" -> synthSam(text, p, r)
                                        "DECTALK" -> {
                                            val v = dectalkVoices.find { it.id == dectalkVoice } ?: dectalkVoices[0]
                                            val dp = (p + v.pitchOffset).coerceIn(50, 200)
                                            synthDectalk(text, dp, r)
                                        }
                                        "ESPEAK" -> synthEspeak(text, dataPath, espeakVoice, p, r)
                                        "OPENEVV" -> synthOpenevv(text, openevvVoice, p, r)
                                        "SP0256" -> synthSp0256(text, p, r)
                                        "VOTRAX" -> synthVotrax(text, p, r)
                                        else -> synthSam(text, p, r)
                                    }
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
                                "SAM" to "SAM — 1982 Apple II",
                                "SP0256" to "SP0256-AL2 — Arcade Chip",
                                "VOTRAX" to "Votrax SC-01 — Vintage",
                                "DECTALK" to "DECtalk — Perfect Paul",
                                "ESPEAK" to "eSpeak NG — Modern",
                                "OPENEVV" to "Eloquence — ViaVoice"
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
                                        text = when (engine) {
                                            "SAM" -> "SAM"
                                            "SP0256" -> "SP0256-AL2"
                                            "VOTRAX" -> "Votrax SC-01"
                                            "DECTALK" -> "DECtalk"
                                            "ESPEAK" -> "eSpeak NG"
                                            "OPENEVV" -> "Eloquence"
                                            else -> engine
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(start = 16.dp)
                                    )
                                }
                            }
                        }

                        // Eloquence voice list: 8 classic formant voice presets. Selection stored in `openevv_voice`.
                        if (selectedEngine == "OPENEVV") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Eloquence Voice",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .selectableGroup()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                            ) {
                                items(eloquenceVoices) { voice ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .selectable(
                                                selected = (voice.id == openevvVoice),
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    openevvVoice = voice.id
                                                    prefs.edit().putInt("openevv_voice", voice.id).apply()
                                                },
                                                role = Role.RadioButton
                                            )
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = "${voice.name}, ${voice.description}"
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (voice.id == openevvVoice),
                                            onClick = null
                                        )
                                        Text(
                                            text = "${voice.name} (${voice.description})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // DECtalk voice list — 6 classic voices, each a distinct formant preset. Makes the old DECtalk engine feel new.
                        if (selectedEngine == "DECTALK") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "DECtalk Voice",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .selectableGroup()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                            ) {
                                items(dectalkVoices) { voice ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .selectable(
                                                selected = (voice.id == dectalkVoice),
                                                onClick = {
                                                    dectalkVoice = voice.id
                                                    prefs.edit().putString("dectalk_voice", voice.id).apply()
                                                },
                                                role = Role.RadioButton
                                            )
                                            .semantics(mergeDescendants = true) {
                                                contentDescription = voice.label + if (voice.id == dectalkVoice) ", selected" else ""
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = (voice.id == dectalkVoice),
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

                        // Full eSpeak voice list — now searchable and filtered, so hundreds of variants no longer require endless scrolling.
                        // Simple by default: shows every voice, filters live as you type. Type "fr", "hindi", or "robo" to narrow instantly.
                        if (selectedEngine == "ESPEAK") {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "eSpeak Voice",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { heading() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = espeakSearchQuery,
                                onValueChange = { espeakSearchQuery = it },
                                placeholder = { Text("Search voices (e.g. en, fr, hindi, robo…)") },
                                leadingIcon = { Text("🔍", modifier = Modifier.padding(start = 12.dp)) },
                                trailingIcon = {
                                    if (espeakSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { espeakSearchQuery = "" }) {
                                            Text("✕")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search eSpeak voices" }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val filteredEspeakVoices = remember(espeakVoices, espeakSearchQuery) {
                                if (espeakSearchQuery.isBlank()) espeakVoices
                                else espeakVoices.filter { it.id.contains(espeakSearchQuery, ignoreCase = true) || it.label.contains(espeakSearchQuery, ignoreCase = true) }
                            }
                            Text(
                                "${filteredEspeakVoices.size} of ${espeakVoices.size} voices",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() }
                            )
                            if (filteredEspeakVoices.isEmpty()) {
                                Text(
                                    "No voices match \"${espeakSearchQuery}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp)
                                        .selectableGroup()
                                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                ) {
                                    items(filteredEspeakVoices, key = { it.id }) { voice ->
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
                                                    contentDescription = voice.label + if (voice.id == espeakVoice) ", selected" else ""
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
                        
                        Text("Pitch: ${pitch.toInt()}%", modifier = Modifier.semantics { heading() })
                        Slider(
                            value = pitch,
                            onValueChange = { pitch = it },
                            valueRange = 50f..200f,
                            modifier = Modifier.semantics {
                                contentDescription = "Pitch"
                                stateDescription = "${pitch.toInt()} percent"
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Speech Rate: ${speechRate.toInt()}%", modifier = Modifier.semantics { heading() })
                        Slider(
                            value = speechRate,
                            onValueChange = { speechRate = it },
                            valueRange = 50f..200f,
                            modifier = Modifier.semantics {
                                contentDescription = "Speech Rate"
                                stateDescription = "${speechRate.toInt()} percent"
                            }
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
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
        val bufferSize = when {
            minBufferSize <= 0 -> pcmData.size.coerceAtLeast(8192)
            else -> pcmData.size.coerceAtLeast(minBufferSize)
        }
        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            try { audioTrack.release() } catch (_: Exception) {}
            return
        }
        try {
            audioTrack.play()
            var offset = 0
            while (offset < pcmData.size) {
                val written = audioTrack.write(pcmData, offset, pcmData.size - offset)
                if (written < 0) break
                if (written == 0) Thread.sleep(10) else offset += written
            }
            val durationMs = (pcmData.size.toLong() * 1000L) / (sampleRate * 2L)
            Thread.sleep((durationMs + 60L).coerceAtLeast(60L))
            try { audioTrack.stop() } catch (_: Exception) {}
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { audioTrack.release() } catch (_: Exception) {}
        }
    }

    companion object {
        fun unpackEspeakData(context: Context) {
            val filesDir = context.filesDir
            val dataDir = File(filesDir, "espeak-ng-data")
            val tmpDir = File(filesDir, "espeak-ng-data.tmp")
            try {
                val resId = context.resources.getIdentifier("espeakdata", "raw", context.packageName)
                if (resId == 0) return
                val expected = readEspeakDataVersion(context, resId)
                val versionFile = File(dataDir, "version")
                if (expected != null && dataDir.exists() && versionFile.exists() && versionFile.readText().trim() == expected) return
                if (expected == null && dataDir.exists()) return
                if (tmpDir.exists()) tmpDir.deleteRecursively()
                tmpDir.mkdirs()
                val filesDirCanonical = filesDir.canonicalPath + File.separator
                val tmpCanonical = tmpDir.canonicalPath + File.separator
                context.resources.openRawResource(resId).use { raw ->
                    ZipInputStream(raw).use { zis ->
                        val buffer = ByteArray(8192)
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            val dest = when {
                                name == "espeak-ng-data" || name == "espeak-ng-data/" -> tmpDir
                                name.startsWith("espeak-ng-data/") -> File(tmpDir, name.removePrefix("espeak-ng-data/"))
                                else -> File(filesDir, name)
                            }
                            val destCanonical = dest.canonicalPath
                            val isInsideFiles = destCanonical.startsWith(filesDirCanonical) || destCanonical == filesDir.canonicalPath
                            val isInsideTmp = destCanonical.startsWith(tmpCanonical) || destCanonical == tmpDir.canonicalPath
                            if (!isInsideFiles && !isInsideTmp) throw SecurityException("Zip entry outside filesDir: $name")
                            if (entry.isDirectory) {
                                dest.mkdirs()
                            } else {
                                dest.parentFile?.mkdirs()
                                FileOutputStream(dest).use { fos ->
                                    var len: Int
                                    while (zis.read(buffer).also { len = it } > 0) {
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                if (expected != null) {
                    val tmpVersion = File(tmpDir, "version")
                    if (!tmpVersion.exists()) tmpVersion.writeText(expected)
                }
                if (dataDir.exists()) dataDir.deleteRecursively()
                if (!tmpDir.renameTo(dataDir)) {
                    tmpDir.copyRecursively(dataDir, overwrite = true)
                    tmpDir.deleteRecursively()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try { File(filesDir, "espeak-ng-data.tmp").deleteRecursively() } catch (_: Exception) {}
            }
        }

        private fun readEspeakDataVersion(context: Context, resId: Int): String? {
            return try {
                context.resources.openRawResource(resId).use { raw ->
                    ZipInputStream(raw).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == "version") {
                                return zis.bufferedReader().readText().trim()
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }
}
