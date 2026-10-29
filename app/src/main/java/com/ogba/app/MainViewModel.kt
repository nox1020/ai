package com.ogba.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ogba.app.data.*
import com.ogba.app.llm.LlmEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    val dao = AppDb.get(app).dao()

    val bots = dao.bots().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val currentBotId = MutableStateFlow(-1L)
    val messages = currentBotId.flatMapLatest { dao.messages(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memories = currentBotId.flatMapLatest { dao.memories(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes = dao.notes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val routines = dao.routines().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val streaming = MutableStateFlow<String?>(null)
    val pendingTool = MutableStateFlow<ToolCall?>(null)
    val error = MutableStateFlow<String?>(null)

    val installed = MutableStateFlow(Models.installed(ctx))
    val selectedModel = MutableStateFlow(Models.selected(ctx))
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())

    init {
        viewModelScope.launch { bots.first { it.isNotEmpty() }.let { if (currentBotId.value < 0) currentBotId.value = it.first().id } }
    }

    fun selectBot(id: Long) { currentBotId.value = id }

    fun send(text: String) {
        val botId = currentBotId.value
        if (text.isBlank() || botId < 0 || streaming.value != null) return
        viewModelScope.launch {
            dao.insertMessage(Message(botId = botId, role = "user", content = text))
            val t = text.trim()
            when {
                t.startsWith("/remember ") -> {
                    dao.insertMemory(Memory(botId = botId, text = t.removePrefix("/remember ").trim()))
                    dao.insertMessage(Message(botId = botId, role = "tool", content = "✓ memory saved"))
                }
                t.startsWith("/") -> {
                    val name = t.drop(1).substringBefore(' ').lowercase()
                    if (name in Tools.names) pendingTool.value = ToolCall(name, t.substringAfter(' ', "").trim())
                    else dao.insertMessage(Message(botId = botId, role = "tool", content = "Commands: /calc EXPR, /time, /note TEXT, /notes, /remember FACT"))
                }
                else -> generate(botId)
            }
        }
    }

    private suspend fun generate(botId: Long) {
        val model = selectedModel.value
        if (model == null) { error.value = "no_model"; return }
        val bot = dao.bot(botId) ?: return
        val mem = dao.memoriesNow(botId).joinToString("\n") { "- " + it.text }
        val sys = buildString {
            append(bot.systemPrompt)
            if (mem.isNotEmpty()) append("\n\nKnown facts about the user:\n").append(mem)
            append("\n\n").append(Tools.INSTRUCTIONS)
        }
        val history = dao.lastMessages(botId, 16).map {
            when (it.role) { "tool" -> "user" to "[tool result] ${it.content}"; else -> it.role to it.content }
        }
        streaming.value = ""
        try {
            val out = LlmEngine.generate(model, listOf("system" to sys) + history, 512) { streaming.value = it }
            dao.insertMessage(Message(botId = botId, role = "assistant", content = out.ifEmpty { "…" }))
            Tools.parse(out)?.let { pendingTool.value = it }
        } catch (e: Exception) {
            error.value = e.message ?: "error"
        } finally { streaming.value = null }
    }

    fun stop() { LlmEngine.stopRequested = true }

    fun resolveTool(approved: Boolean) {
        val call = pendingTool.value ?: return
        pendingTool.value = null
        val botId = currentBotId.value
        viewModelScope.launch {
            if (!approved) { dao.insertMessage(Message(botId = botId, role = "tool", content = "✗ ${call.name} denied by user")); return@launch }
            val result = Tools.run(call, dao)
            dao.insertMessage(Message(botId = botId, role = "tool", content = result))
            if (selectedModel.value != null) generate(botId)
        }
    }

    fun clearChat() = viewModelScope.launch { dao.clearChat(currentBotId.value) }
    fun saveBot(b: Bot) = viewModelScope.launch { val id = dao.upsertBot(b); if (b.id == 0L) currentBotId.value = id }
    fun deleteBot(b: Bot) = viewModelScope.launch { dao.deleteBot(b); if (currentBotId.value == b.id) currentBotId.value = bots.value.firstOrNull { it.id != b.id }?.id ?: -1 }
    fun addMemory(t: String) = viewModelScope.launch { if (t.isNotBlank()) dao.insertMemory(Memory(botId = currentBotId.value, text = t)) }
    fun deleteMemory(m: Memory) = viewModelScope.launch { dao.deleteMemory(m) }
    fun deleteNote(n: Note) = viewModelScope.launch { dao.deleteNote(n) }

    fun addRoutine(name: String, prompt: String, minutes: Long) = viewModelScope.launch {
        val r = Routine(botId = currentBotId.value, name = name, prompt = prompt, intervalMinutes = minutes)
        val id = dao.insertRoutine(r)
        RoutineScheduler.schedule(ctx, r.copy(id = id))
    }
    fun runRoutine(r: Routine) = RoutineScheduler.runNow(ctx, r)
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
            } catch (e: Exception) { error.value = "Download failed: ${e.message}" }
            downloads.value = downloads.value - m.id
            refreshModels()
        }
    }
    fun import(uri: Uri) = viewModelScope.launch {
        try { val f = withContext(Dispatchers.IO) { Models.import(ctx, uri) }; Models.select(ctx, f.absolutePath) }
        catch (e: Exception) { error.value = "Import failed: ${e.message}" }
        refreshModels()
    }
}
