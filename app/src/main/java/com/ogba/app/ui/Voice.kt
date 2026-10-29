package com.ogba.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ogba.app.*
import com.ogba.app.R
import java.util.Locale

/** Hands-free loop: listen (offline STT) -> local LLM -> speak (Android TTS) -> listen again. Tap to interrupt. */
class VoiceController(private val ctx: Context, private val vm: MainViewModel) {
    enum class St { Idle, Listening, Thinking, Speaking }
    var state by mutableStateOf(St.Idle); private set
    var heard by mutableStateOf("")
    var said by mutableStateOf("")
    var warning by mutableStateOf<String?>(null)
    var lang by mutableStateOf(ctx.getSharedPreferences("ogba", 0).getString("voice_lang", if (Locale.getDefault().language == "fa") "fa" else "en") ?: "en")
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var pending = 0
    private var spokenUpTo = 0
    private var lastText = ""
    private var misses = 0
    private var ttsReady = false
    private val tts = TextToSpeech(ctx) { ok -> ttsReady = ok == TextToSpeech.SUCCESS; main.post { applyLang() } }
    private var rec: SpeechRecognizer? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { pending = (pending - 1).coerceAtLeast(0); maybeListen() } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { pending = (pending - 1).coerceAtLeast(0); maybeListen() } }
        })
    }

    private val locale get() = if (lang == "fa") Locale("fa", "IR") else Locale.US

    fun setLanguage(l: String) { lang = l; ctx.getSharedPreferences("ogba", 0).edit().putString("voice_lang", l).apply(); applyLang(); rec?.destroy(); rec = null }

    private fun applyLang() {
        if (!ttsReady) return
        val r = tts.setLanguage(locale)
        warning = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
            ctx.getString(R.string.tts_missing, locale.displayLanguage) else null
        tts.setSpeechRate(1.05f)
    }

    private fun recognizer(): SpeechRecognizer {
        rec?.let { return it }
        val r = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { level = v }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it } }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) { retry(); return }
                misses = 0; heard = text; said = ""; spokenUpTo = 0; lastText = ""
                state = St.Thinking
                vm.send(text, voice = true)
            }
            override fun onError(e: Int) {
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { warning = ctx.getString(R.string.mic_permission); stop(); return }
                if (e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) warning = ctx.getString(R.string.stt_missing, locale.displayLanguage)
                retry()
            }
        })
        rec = r; return r
    }
    var level by mutableStateOf(0f)

    private fun retry() { if (!active) return; if (++misses > 4) { state = St.Idle; active = false; return }; main.postDelayed({ listen() }, 300) }

    fun listen() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { warning = ctx.getString(R.string.mic_permission); return }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { warning = ctx.getString(R.string.no_voice); return }
        active = true; state = St.Listening; heard = ""
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        try { recognizer().startListening(i) } catch (e: Exception) { warning = e.message }
    }

    /** Called with the streaming reply; speaks finished sentences immediately. */
    fun onStream(text: String?) {
        if (!active) return
        if (text == null) { if (lastText.length > spokenUpTo) speak(lastText.substring(spokenUpTo)); spokenUpTo = 0; lastText = ""; return }
        if (text.length < spokenUpTo) spokenUpTo = 0          // new agent step started
        lastText = text
        val cut = text.lastIndexOfAny(charArrayOf('.', '!', '?', '؟', '\n', '…', '。'))
        if (cut >= spokenUpTo && cut > 0) { speak(text.substring(spokenUpTo, cut + 1)); spokenUpTo = cut + 1 }
    }

    private fun speak(raw: String) {
        val clean = raw.lines().filterNot { it.contains("[[") || it.trim().startsWith("Plan:", true) }.joinToString(" ")
            .replace(Regex("[*#`_>|]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        said = (said + " " + clean).trim()
        state = St.Speaking; pending++
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    fun onBusyChanged(busy: Boolean) { if (!busy) main.postDelayed({ maybeListen() }, 250) }

    private fun maybeListen() { if (active && pending == 0 && !vm.busy.value && state != St.Listening) listen() }

    /** Tap: interrupt speaking/thinking and listen; or toggle listening. */
    fun tap() {
        when (state) {
            St.Speaking, St.Thinking -> { tts.stop(); pending = 0; vm.stop(); spokenUpTo = Int.MAX_VALUE; main.postDelayed({ spokenUpTo = 0; listen() }, 300) }
            St.Listening -> stop()
            St.Idle -> { misses = 0; listen() }
        }
    }

    fun stop() { active = false; rec?.cancel(); tts.stop(); pending = 0; state = St.Idle }
    private var released = false
    fun release() { if (released) return; released = true; stop(); vm.stop(); rec?.destroy(); rec = null; tts.shutdown() }
}

@Composable
fun VoiceScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val bots by vm.bots.collectAsState()
    val botId by vm.currentBotId.collectAsState()
    val vc = remember { VoiceController(ctx, vm) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vc.listen() }
    LaunchedEffect(bots.size) { bots.firstOrNull { it.name == VOICE_BOT.name }?.let { if (botId != it.id) vm.currentBotId.value = it.id } }
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vc.listen() else perm.launch(Manifest.permission.RECORD_AUDIO)
    }
    val stream by vm.streaming.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(stream[botId]) { vc.onStream(stream[botId]) }
    LaunchedEffect(busy) { vc.onBusyChanged(busy) }
    DisposableEffect(Unit) { onDispose { vc.release() } }

    val pulse = rememberInfiniteTransition(label = "p")
    val s by pulse.animateFloat(1f, if (vc.state == VoiceController.St.Idle) 1f else 1.12f, infiniteRepeatable(tween(if (vc.state == VoiceController.St.Speaking) 350 else 900), RepeatMode.Reverse), label = "s")
    val ring = when (vc.state) { VoiceController.St.Listening -> C.blue; VoiceController.St.Speaking -> C.rose; VoiceController.St.Thinking -> C.amber; else -> C.cardActive }

    Column(Modifier.fillMaxSize().background(C.background).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.open(Screen.Chat) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.zinc400) }
            Text(bots.firstOrNull { it.id == botId }?.name ?: "OGBA", color = C.foreground, fontSize = 15.sp, modifier = Modifier.weight(1f))
            listOf("fa" to "فارسی", "en" to "English").forEach { (k, l) ->
                FilterChip(vc.lang == k, { vc.setLanguage(k) }, label = { Text(l, fontSize = 12.sp) }, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(220.dp).scale(s).background(ring.copy(alpha = .18f), CircleShape).clickable { vc.tap() }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(170.dp + (vc.level.coerceIn(0f, 10f) * 3).dp).background(ring.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
                MascotAvatar(if (vc.state == VoiceController.St.Speaking) "pink" else "blue", 120.dp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(when (vc.state) {
            VoiceController.St.Listening -> R.string.v_listening; VoiceController.St.Thinking -> R.string.v_thinking
            VoiceController.St.Speaking -> R.string.v_speaking; else -> R.string.v_idle }), color = C.zinc300, fontSize = 16.sp)
        Spacer(Modifier.height(16.dp))
        if (vc.heard.isNotEmpty()) Text("“${vc.heard}”", color = C.zinc400, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (vc.said.isNotEmpty()) Text(vc.said.takeLast(400), color = C.foreground, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        vc.warning?.let { Text(it, color = C.amber400, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.v_hint), color = C.zinc500, fontSize = 11.sp, textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 12.dp)) {
            Button(onClick = { vc.release(); vm.open(Screen.Chat) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F191B), contentColor = C.errorText)) { Text(stringResource(R.string.v_end)) }
        }
    }
}
