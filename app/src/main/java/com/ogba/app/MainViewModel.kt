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

enum class Screen { Chat, Computer, Marketplace, Audit, Settings, Models, Memory, Routines }

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

    init {
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
    fun send(text: String) {
        val botId = currentBotId.value
        if (text.isBlank() || botId < 0 || streaming.value.containsKey(botId)) return
        viewModelScope.launch {
            dao.insertMessage(Message(botId = botId, role = "user", content = text.trim()))
            val t = text.trim()
            if (t.startsWith("/remember ")) {
                dao.insertMemory(Memory(botId = botId, text = t.removePrefix("/remember ").trim()))
                streamTurn(botId, "The user asked you to remember: \"${t.removePrefix("/remember ").trim()}\". It is saved in memory; confirm briefly.")
                return@launch
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
        val id = "req-" + System.currentTimeMillis().toString(36)
        val d = CompletableDeferred<String>()
        decisions[id] = d
        approvals.value = approvals.value.filter { it.status == "pending" || it.botId != botId } + Approval(id, botId, call)
        dao.insertAudit(AuditEvent(botId = botId, type = "request.opened", tool = call.tool, summary = call.summary))
        val decision = withTimeoutOrNull(10 * 60_000L) { d.await() } ?: "expired"
        decisions.remove(id)
        approvals.value = approvals.value.map { if (it.requestId == id) it.copy(status = if (decision == "allow") "allowed" else if (decision == "deny") "denied" else "expired") else it }
        return when (decision) {
            "allow" -> {
                event(botId, "tool.started", call.tool)
                try {
                    val r = withContext(Dispatchers.IO) { Tools.execute(ctx, call, dao) }
                    event(botId, "tool.completed", call.tool, result = r)
                    "Action result (${call.tool}): $r"
                } catch (e: Exception) {
                    event(botId, "tool.failed", call.tool, error = e.message)
                    "Action could not execute (${call.tool}): ${e.message}"
                }
            }
            "deny" -> { event(botId, "tool.denied", call.tool); "Action denied by the user: ${call.tool}" }
            else -> { event(botId, "tool.expired", call.tool); "Action expired before approval: ${call.tool}" }
        }
    }

    fun respondApproval(requestId: String, action: String) { decisions[requestId]?.complete(action) }

    private suspend fun streamTurn(botId: Long, toolContext: String, depth: Int = 0) {
        val bot = dao.bot(botId) ?: return
        val model = Models.pathFor(ctx, bot.model)
        if (model == null) {
            dao.insertMessage(Message(botId = botId, role = "error", content = "Error: no local model installed. Open Settings → Models to download one."))
            return
        }
        val mem = dao.memoriesNow(botId).joinToString("\n") { "- " + it.text }
        var sys = Prompts.system(bot.systemPrompt)
        if (mem.isNotEmpty()) sys += "\n\nMemory (facts the user asked you to remember):\n$mem"
        sys += "\n\n" + Tools.INSTRUCTIONS
        if (toolContext.isNotEmpty()) sys += "\n\n$toolContext"
        val history = dao.lastMessages(botId, 16).map { (if (it.role == "user") "user" else "assistant") to it.content }
        streaming.value = streaming.value + (botId to "")
        var out = ""
        try {
            out = LlmEngine.generate(model, listOf("system" to sys) + history, 512) { s -> streaming.value = streaming.value + (botId to s) }
            dao.insertMessage(Message(botId = botId, role = "bot", content = out.ifEmpty { "…" }, model = File(model).name))
        } catch (e: Exception) {
            dao.insertMessage(Message(botId = botId, role = "error", content = "Error: ${e.message}"))
        } finally { streaming.value = streaming.value - botId }
        // Model-initiated tool request -> same approval broker, then a follow-up turn.
        val req = Tools.parseModelRequest(out)
        if (req != null && depth < 3) streamTurn(botId, runGated(botId, req), depth + 1)
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
    fun download(m: CatalogModel) {
        if (downloads.value.containsKey(m.id)) return
        downloads.value = downloads.value + (m.id to 0f)
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Models.download(ctx, m) { p -> downloads.value = downloads.value + (m.id to p) } }
                if (selectedModel.value == null) Models.select(ctx, f.absolutePath)
            } catch (e: Exception) { toast.value = "Download failed: ${e.message}" }
            downloads.value = downloads.value - m.id
            refreshModels()
        }
    }
    fun import(uri: Uri) = viewModelScope.launch {
        try { val f = withContext(Dispatchers.IO) { Models.import(ctx, uri) }; Models.select(ctx, f.absolutePath) }
        catch (e: Exception) { toast.value = "Import failed: ${e.message}" }
        refreshModels()
    }
}
