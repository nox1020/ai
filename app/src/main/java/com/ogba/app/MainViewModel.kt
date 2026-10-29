package com.ogba.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ogba.app.data.*
import com.ogba.app.llm.LlmEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Pending / resolved approval card (Permission Broker Request). status: pending | allowed | denied | expired */
data class Approval(val requestId: String, val botId: Long, val call: ToolCall, val status: String = "pending")
/** Inline tool lifecycle event card: tool.started / tool.completed / tool.failed / tool.denied / tool.expired */
data class ToolEvent(val id: Long, val botId: Long, val type: String, val tool: String, val result: String? = null, val error: String? = null)

enum class Screen { Chat, Voice, Computer, Browser, Marketplace, Audit, Settings, Models, Memory, Routines }

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("ogba", Context.MODE_PRIVATE)

    val bots = dao.bots().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val lastPerBot = dao.lastPerBot().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val currentBotId = MutableStateFlow(-1L)
    val screen = MutableStateFlow(Screen.Chat)
    val messages = currentBotId.flatMapLatest { dao.messages(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memories = currentBotId.flatMapLatest { dao.memories(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes = dao.notes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val routines = dao.routines().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val audit = dao.audit().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** botId -> streaming text while a turn is open */
    val streaming = MutableStateFlow<Map<Long, String>>(emptyMap())
    val approvals = MutableStateFlow<List<Approval>>(emptyList())
    val toolEvents = MutableStateFlow<List<ToolEvent>>(emptyList())
    private val decisions = mutableMapOf<String, CompletableDeferred<String>>()

    val installed = MutableStateFlow(Models.installed(ctx))
    val selectedModel = MutableStateFlow(Models.selected(ctx))
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val userName = MutableStateFlow(prefs.getString("user_name", "You") ?: "You")
    val userEmail = MutableStateFlow(prefs.getString("user_email", "") ?: "")
    val toast = MutableStateFlow<String?>(null)
    val autoReads = MutableStateFlow(prefs.getBoolean("auto_reads", true))
    val maxSteps = MutableStateFlow(prefs.getInt("max_steps", 6))
    fun setAutoReads(v: Boolean) { prefs.edit().putBoolean("auto_reads", v).apply(); autoReads.value = v }
    val gpuOn = MutableStateFlow(prefs.getBoolean("gpu_on", false))
    val gpuLayers = MutableStateFlow(prefs.getInt("gpu_layers", 99))
    val flashAttn = MutableStateFlow(prefs.getBoolean("flash_attn", true))
    fun setPerf(gpu: Boolean = gpuOn.value, layers: Int = gpuLayers.value, flash: Boolean = flashAttn.value) {
        prefs.edit().putBoolean("gpu_on", gpu).putInt("gpu_layers", layers).putBoolean("flash_attn", flash).apply()
        gpuOn.value = gpu; gpuLayers.value = layers; flashAttn.value = flash
        LlmEngine.gpuLayers = if (gpu && LlmEngine.hasGpu) layers else 0
        LlmEngine.flashAttn = flash
        LlmEngine.invalidate()
    }
    fun setMaxSteps(v: Int) { prefs.edit().putInt("max_steps", v).apply(); maxSteps.value = v }

    init {
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(ctx).getWorkInfosByTagFlow("model-download").collect { infos ->
                val map = mutableMapOf<String, Float>()
                var finished = false
                infos.forEach { w ->
                    val id = w.tags.firstOrNull { it.startsWith("dl-id:") }?.removePrefix("dl-id:") ?: return@forEach
                    when (w.state) {
                        androidx.work.WorkInfo.State.RUNNING, androidx.work.WorkInfo.State.ENQUEUED, androidx.work.WorkInfo.State.BLOCKED -> map[id] = w.progress.getFloat("p", 0f)
                        androidx.work.WorkInfo.State.SUCCEEDED -> finished = true
                        else -> {}
                    }
                }
                downloads.value = map
                if (finished) refreshModels()
            }
        }
        Browser.showScreen = { screen.value = Screen.Browser }
        viewModelScope.launch { bots.first { it.isNotEmpty() }.let { if (currentBotId.value < 0) currentBotId.value = it.first().id } }
    }

    fun selectBot(id: Long) { currentBotId.value = id; screen.value = Screen.Chat }
    fun open(s: Screen) { screen.value = s }
    fun saveProfile(name: String, email: String) {
        prefs.edit().putString("user_name", name.ifBlank { "You" }).putString("user_email", email).apply()
        userName.value = name.ifBlank { "You" }; userEmail.value = email
    }

    private fun event(botId: Long, type: String, tool: String, result: String? = null, error: String? = null) {
        toolEvents.value = (toolEvents.value.filter { it.botId == botId }.takeLast(4) + ToolEvent(System.nanoTime(), botId, type, tool, result, error)) +
            toolEvents.value.filter { it.botId != botId }
        viewModelScope.launch { dao.insertAudit(AuditEvent(botId = botId, type = type, tool = tool, summary = error ?: result?.take(300) ?: "")) }
    }

    /** Send flow == ChatWindow.handleSendMessage -> POST message -> SSE stream_turn. */
    val busy = MutableStateFlow(false)
    @Volatile private var voiceTurn = false

    fun send(text: String, voice: Boolean = false) {
        val botId = currentBotId.value
        if (text.isBlank() || botId < 0 || busy.value) return
        busy.value = true
        voiceTurn = voice
        viewModelScope.launch { try { sendInner(botId, text) } finally { busy.value = false } }
    }

    private suspend fun sendInner(botId: Long, text: String) {
        run {
            dao.insertMessage(Message(botId = botId, role = "user", content = text.trim()))
            val t = text.trim()
            if (t.startsWith("/remember ")) {
                dao.insertMemory(Memory(botId = botId, text = t.removePrefix("/remember ").trim()))
                streamTurn(botId, "The user asked you to remember: \"${t.removePrefix("/remember ").trim()}\". It is saved in memory; confirm briefly.")
                return
            }
            var toolContext = ""
            val call = try { Tools.parseUserCommand(t) } catch (e: ToolError) {
                val kind = "workspace"
                toolContext = "A $kind request was rejected before execution: ${e.message}"
                event(botId, "tool.failed", kind, error = e.message); null
            }
            if (call != null) toolContext = runGated(botId, call)
            streamTurn(botId, toolContext)
        }
    }

    /** Action gateway: open request -> wait for decision -> execute -> tool context string. */
    private suspend fun runGated(botId: Long, call: ToolCall): String {
        if (call.tool !in Tools.registry) { event(botId, "tool.failed", call.tool, error = "Action rejected by policy"); return "Action rejected by policy: ${call.tool}" }
        if (call.tool in Tools.readOnly && autoReads.value) {
            dao.insertAudit(AuditEvent(botId = botId, type = "auto.allowed", tool = call.tool, summary = call.summary))
            return execGated(botId, call)
        }
        val id = "req-" + System.currentTimeMillis().toString(36)
        val d = CompletableDeferred<String>()
        decisions[id] = d
        approvals.value = approvals.value.filter { it.status == "pending" || it.botId != botId } + Approval(id, botId, call)
        dao.insertAudit(AuditEvent(botId = botId, type = "request.opened", tool = call.tool, summary = call.summary))
        val decision = withTimeoutOrNull(10 * 60_000L) { d.await() } ?: "expired"
        decisions.remove(id)
        approvals.value = approvals.value.map { if (it.requestId == id) it.copy(status = if (decision == "allow") "allowed" else if (decision == "deny") "denied" else "expired") else it }
        return when (decision) {
            "allow" -> execGated(botId, call)
            "deny" -> { event(botId, "tool.denied", call.tool); "Action denied by the user: ${call.tool}" }
            else -> { event(botId, "tool.expired", call.tool); "Action expired before approval: ${call.tool}" }
        }
    }

    private suspend fun execGated(botId: Long, call: ToolCall): String {
        event(botId, "tool.started", call.tool, result = call.summary)
        return try {
            val r = withContext(Dispatchers.IO) { Tools.execute(ctx, call, dao) }
            event(botId, "tool.completed", call.tool, result = r)
            "Action result (${call.tool}): $r"
        } catch (e: Exception) {
            event(botId, "tool.failed", call.tool, error = e.message)
            "Action could not execute (${call.tool}): ${e.message}"
        }
    }

    fun respondApproval(requestId: String, action: String) { decisions[requestId]?.complete(action) }

    /** Agent loop: plan -> act (tool, approval-gated) -> observe -> repeat, up to maxSteps. */
    private suspend fun streamTurn(botId: Long, toolContext: String) {
        val bot = dao.bot(botId) ?: return
        val model = Models.pathFor(ctx, bot.model)
        if (model == null) {
            dao.insertMessage(Message(botId = botId, role = "error", content = "Error: no local model installed. Open Settings → Models to download one."))
            return
        }
        val observations = StringBuilder(toolContext)
        val steps = maxSteps.value.coerceIn(1, 12)
        for (step in 1..steps) {
            val mem = dao.memoriesNow(botId).joinToString("\n") { "- " + it.text }
            var sys = Prompts.system(bot.systemPrompt)
            if (mem.isNotEmpty()) sys += "\n\nMemory (facts the user asked you to remember):\n$mem"
            sys += "\n\n" + Tools.INSTRUCTIONS
            if (observations.isNotEmpty()) sys += "\n\n" + observations.toString().takeLast(3500)
            if (step == steps) sys += "\n\nThis is the final step: answer the user now without tools."
            if (voiceTurn) sys += "\n\n" + Prompts.VOICE_STYLE
            val history = dao.lastMessages(botId, 12).map { (if (it.role == "user") "user" else "assistant") to it.content.take(1500) }
            streaming.value = streaming.value + (botId to "")
            var out = ""
            try {
                out = LlmEngine.generate(model, listOf("system" to sys) + history, 512) { s -> streaming.value = streaming.value + (botId to s) }
                val tps = LlmEngine.lastTokPerSec
                dao.insertMessage(Message(botId = botId, role = "bot", content = out.ifEmpty { "…" },
                    model = File(model).name.removeSuffix(".gguf") + " · " + String.format(java.util.Locale.US, "%.1f tok/s", tps) + (if (LlmEngine.onGpu) " · GPU" else " · CPU")))
            } catch (e: Exception) {
                dao.insertMessage(Message(botId = botId, role = "error", content = "Error: ${e.message}"))
                return
            } finally { streaming.value = streaming.value - botId }
            if (LlmEngine.stopRequested) return
            val req = Tools.parseModelRequest(out) ?: return
            if (step == steps) return
            event(botId, "agent.step", "step $step/$steps", result = req.summary)
            val obs = runGated(botId, req).replaceFirst("Action result", "Observation")
            observations.append("\n").append(obs)
        }
    }

    fun stop() { LlmEngine.stopRequested = true }

    fun clearChat() = viewModelScope.launch { dao.clearChat(currentBotId.value) }
    fun createBot(name: String, role: String, model: String) = viewModelScope.launch {
        val id = dao.insertBot(Bot(name = name, role = role.ifBlank { "AI Assistant" }, model = model,
            description = "Custom agent running ${model.ifEmpty { "the default local model" }} on-device.", avatar = "🤖",
            systemPrompt = "You are $name, a helpful AI assistant."))
        selectBot(id)
    }
    fun updateBot(b: Bot) = viewModelScope.launch { dao.updateBot(b) }
    fun deleteBot(b: Bot) = viewModelScope.launch { dao.deleteBot(b); dao.clearChat(b.id); if (currentBotId.value == b.id) currentBotId.value = bots.value.firstOrNull { it.id != b.id }?.id ?: -1 }
    fun setBotModel(b: Bot, model: String) = updateBot(b.copy(model = model))

    fun addMemory(t: String) = viewModelScope.launch { if (t.isNotBlank()) dao.insertMemory(Memory(botId = currentBotId.value, text = t)) }
    fun deleteMemory(m: Memory) = viewModelScope.launch { dao.deleteMemory(m) }
    fun deleteNote(n: Note) = viewModelScope.launch { dao.deleteNote(n) }

    fun addRoutine(name: String, prompt: String, minutes: Long) = viewModelScope.launch {
        val r = Routine(botId = currentBotId.value, name = name, prompt = prompt, intervalMinutes = minutes)
        RoutineScheduler.schedule(ctx, r.copy(id = dao.insertRoutine(r)))
    }
    fun runRoutine(r: Routine) { RoutineScheduler.runNow(ctx, r); toast.value = "Routine queued: ${r.name}" }
    fun deleteRoutine(r: Routine) = viewModelScope.launch { RoutineScheduler.cancel(ctx, r); dao.deleteRoutine(r) }

    private fun refreshModels() { installed.value = Models.installed(ctx); selectedModel.value = Models.selected(ctx) }
    fun selectModel(f: File) { Models.select(ctx, f.absolutePath); refreshModels() }
    fun deleteModel(f: File) { f.delete(); refreshModels() }
    fun download(m: CatalogModel) = Models.enqueue(ctx, m)
    fun cancelDownload(m: CatalogModel) = Models.cancel(ctx, m)
    fun import(uri: Uri) = viewModelScope.launch {
        try { val f = withContext(Dispatchers.IO) { Models.import(ctx, uri) }; Models.select(ctx, f.absolutePath) }
        catch (e: Exception) { toast.value = "Import failed: ${e.message}" }
        refreshModels()
    }
}
