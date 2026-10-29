package com.ogba.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ogba.app.*
import com.ogba.app.R
import com.ogba.app.data.Bot
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PanelScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(C.background)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = C.zinc400) }
            Text(title, color = C.foreground, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        HorizontalDivider(color = C.headerBorder)
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), content = content)
    }
}

@Composable
fun DarkCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.padding(vertical = 5.dp).fillMaxWidth().background(C.popover, RoundedCornerShape(14.dp)).border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(14.dp)).padding(14.dp), content = content)

@Composable
fun Badge(text: String, color: Color) = Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
    modifier = Modifier.background(color.copy(alpha = .12f), RoundedCornerShape(6.dp)).border(1.dp, color.copy(alpha = .3f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))

@Composable
fun SectionTitle(t: String) = Text(t, color = C.zinc400, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
fun darkFieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.zinc500, unfocusedBorderColor = C.pillBorder, focusedContainerColor = C.popover, unfocusedContainerColor = C.popover)

/** ComputerPanel.jsx – remote/Docker computers need a server; offline we expose the local sandbox workspace instead. */
@Composable
fun ComputerPanel(vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var files by remember { mutableStateOf(Tools.workspaceRoot(ctx).walkTopDown().filter { it.isFile }.toList()) }
    PanelScaffold(stringResource(R.string.computer), { vm.open(Screen.Chat) }) {
        DarkCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_computer), color = C.foreground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Badge(stringResource(R.string.unavailable_offline), C.amber400)
            }
            Text(stringResource(R.string.remote_computer_desc), color = C.zinc400, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        DarkCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.local_workspace), color = C.foreground, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Badge(stringResource(R.string.ready), C.emerald)
                IconButton(onClick = { files = Tools.workspaceRoot(ctx).walkTopDown().filter { it.isFile }.toList() }) { Icon(Icons.Default.Refresh, null, tint = C.zinc400) }
            }
            Text(stringResource(R.string.local_workspace_desc), color = C.zinc400, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            if (files.isEmpty()) Text(stringResource(R.string.empty_workspace), color = C.zinc500, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            files.forEach { f -> Text("📄 " + f.relativeTo(Tools.workspaceRoot(ctx)).path + "  (${f.length()} B)", color = C.zinc300, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

/** Marketplace.jsx – Composio connectors need the internet; built-in local tools replace them. */
@Composable
fun Marketplace(vm: MainViewModel) {
    val local = listOf("workspace.list" to "List files in the local sandbox", "workspace.read" to "Read a sandbox file", "workspace.write" to "Write a sandbox file",
        "local.calc" to "Calculator", "local.time" to "Device clock", "local.note" to "Save a note", "local.notes" to "Read notes")
    val cloud = listOf("GitHub", "Gmail", "Slack", "Google Calendar", "Notion", "Linear", "Google Drive")
    PanelScaffold(stringResource(R.string.plugins), { vm.open(Screen.Chat) }) {
        LazyColumn {
            item { SectionTitle(stringResource(R.string.builtin_tools).uppercase()) }
            items(local) { (n, d) ->
                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = C.cyan300, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Badge(stringResource(R.string.approval_required), C.amber400)
                    }
                    Text(d, color = C.zinc400, fontSize = 12.sp)
                }
            }
            item { SectionTitle(stringResource(R.string.connectors).uppercase()) }
            items(cloud) { n ->
                DarkCard(Modifier.clickable { }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = C.zinc300, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Badge(stringResource(R.string.unavailable_offline), C.zinc500)
                    }
                }
            }
        }
    }
}

/** AuditPanel.jsx */
@Composable
fun AuditPanel(vm: MainViewModel) {
    val events by vm.audit.collectAsState()
    val bots by vm.bots.collectAsState()
    val fmt = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.US) }
    PanelScaffold(stringResource(R.string.audit_trail), { vm.open(Screen.Chat) }) {
        if (events.isEmpty()) {
            Text(stringResource(R.string.no_audit), color = C.zinc300, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
            Text(stringResource(R.string.no_audit_hint), color = C.zinc500, fontSize = 12.sp)
        }
        LazyColumn {
            items(events, key = { it.id }) { e ->
                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.tool, color = C.cyan300, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Badge(e.type, when (e.type) { "tool.completed" -> C.emerald; "tool.failed", "tool.denied" -> C.rose400; else -> C.amber400 })
                    }
                    if (e.summary.isNotEmpty()) Text(e.summary, color = C.zinc400, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                    Text((bots.firstOrNull { it.id == e.botId }?.name ?: "") + " · " + fmt.format(Date(e.ts)), color = C.zinc500, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** AppSettingsDrawer.jsx – profile, default model, local data, connections. */
@Composable
fun SettingsPanel(vm: MainViewModel) {
    val name by vm.userName.collectAsState()
    val email by vm.userEmail.collectAsState()
    val sel by vm.selectedModel.collectAsState()
    var n by remember { mutableStateOf(name) }
    var e by remember { mutableStateOf(email) }
    PanelScaffold(stringResource(R.string.settings), { vm.open(Screen.Chat) }) {
        LazyColumn {
            item {
                SectionTitle(stringResource(R.string.profile).uppercase())
                OutlinedTextField(n, { n = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.your_name)) }, colors = darkFieldColors(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(e, { e = it }, Modifier.fillMaxWidth(), placeholder = { Text("you@example.com") }, colors = darkFieldColors(), singleLine = true)
                Button(onClick = { vm.saveProfile(n, e) }, Modifier.padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text(stringResource(R.string.save)) }

                SectionTitle(stringResource(R.string.model_provider).uppercase())
                SettingsRow(stringResource(R.string.default_model), sel?.substringAfterLast('/') ?: stringResource(R.string.no_model_short)) { vm.open(Screen.Models) }
                DarkCard { Text(stringResource(R.string.provider_desc), color = C.zinc400, fontSize = 12.sp) }

                SectionTitle(stringResource(R.string.local_data).uppercase())
                SettingsRow(stringResource(R.string.memory_title), "") { vm.open(Screen.Memory) }
                SettingsRow(stringResource(R.string.routines), "") { vm.open(Screen.Routines) }
                SettingsRow(stringResource(R.string.audit_trail), "") { vm.open(Screen.Audit) }

                SectionTitle(stringResource(R.string.connections).uppercase())
                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MUAPI · Composio", color = C.zinc300, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Badge(stringResource(R.string.unavailable_offline), C.zinc500)
                    }
                    Text(stringResource(R.string.connections_desc), color = C.zinc500, fontSize = 11.sp)
                }
                Text("OGBA v" + BuildConfigInfo.version + " · llama.cpp", color = C.zinc500, fontSize = 11.sp, modifier = Modifier.padding(vertical = 24.dp))
            }
        }
    }
}

object BuildConfigInfo { var version = "" }

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.padding(vertical = 4.dp).fillMaxWidth().background(C.popover, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.foreground, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.zinc400, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text("  ›", color = C.zinc500)
    }
}

@Composable
fun ModelsPanel(vm: MainViewModel) {
    val installed by vm.installed.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it) } }
    PanelScaffold(stringResource(R.string.models), { vm.open(Screen.Settings) }) {
        LazyColumn {
            item { SectionTitle(stringResource(R.string.installed_models).uppercase()); if (installed.isEmpty()) Text(stringResource(R.string.no_model), color = C.zinc400, fontSize = 12.sp) }
            items(installed, key = { it.absolutePath }) { f ->
                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == f.absolutePath, { vm.selectModel(f) })
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = C.foreground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("${f.length() / 1_000_000} MB", color = C.zinc500, fontSize = 11.sp)
                        }
                        IconButton(onClick = { vm.deleteModel(f) }) { Icon(Icons.Default.Delete, null, tint = C.zinc400) }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.padding(vertical = 8.dp)) { Text(stringResource(R.string.import_gguf)) }
                SectionTitle(stringResource(R.string.download_models).uppercase())
                Text(stringResource(R.string.download_hint), color = C.zinc500, fontSize = 11.sp)
            }
            items(Models.catalog, key = { it.id }) { m ->
                val p = downloads[m.id]
                val have = installed.any { it.name == m.fileName }
                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(m.title, color = C.foreground, fontSize = 13.sp); Text("~${m.sizeMb} MB", color = C.zinc500, fontSize = 11.sp) }
                        if (have) Badge(stringResource(R.string.ready), C.emerald)
                        else if (p == null) Button(onClick = { vm.download(m) }, colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text(stringResource(R.string.download), fontSize = 12.sp) }
                    }
                    if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 8.dp), color = C.blue)
                }
            }
        }
    }
}

@Composable
fun MemoryPanel(vm: MainViewModel) {
    val mems by vm.memories.collectAsState()
    val notes by vm.notes.collectAsState()
    var input by remember { mutableStateOf("") }
    PanelScaffold(stringResource(R.string.memory_title), { vm.open(Screen.Settings) }) {
        LazyColumn {
            item {
                Text(stringResource(R.string.memory_hint), color = C.zinc500, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.add_memory)) }, colors = darkFieldColors())
                    IconButton(onClick = { vm.addMemory(input); input = "" }) { Icon(Icons.Default.Add, null, tint = C.zinc300) }
                }
            }
            items(mems, key = { "m" + it.id }) { m -> DarkCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(m.text, color = C.foreground, fontSize = 13.sp, modifier = Modifier.weight(1f)); IconButton(onClick = { vm.deleteMemory(m) }) { Icon(Icons.Default.Delete, null, tint = C.zinc400) } } } }
            item { SectionTitle(stringResource(R.string.notes_title).uppercase()) }
            items(notes, key = { "n" + it.id }) { n -> DarkCard { Row(verticalAlignment = Alignment.CenterVertically) { Text(n.text, color = C.foreground, fontSize = 13.sp, modifier = Modifier.weight(1f)); IconButton(onClick = { vm.deleteNote(n) }) { Icon(Icons.Default.Delete, null, tint = C.zinc400) } } } }
        }
    }
}

@Composable
fun RoutinesPanel(vm: MainViewModel) {
    val routines by vm.routines.collectAsState()
    val bots by vm.bots.collectAsState()
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    PanelScaffold(stringResource(R.string.routines), { vm.open(Screen.Settings) }) {
        LazyColumn {
            item {
                Text(stringResource(R.string.routine_hint), color = C.zinc500, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) }, colors = darkFieldColors())
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.routine_prompt)) }, minLines = 2, colors = darkFieldColors())
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.interval_min)) }, colors = darkFieldColors())
                Button(onClick = { if (name.isNotBlank() && prompt.isNotBlank()) { vm.addRoutine(name, prompt, minutes.toLongOrNull() ?: 60); name = ""; prompt = "" } },
                    Modifier.padding(vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text(stringResource(R.string.add_routine)) }
            }
            items(routines, key = { it.id }) { r ->
                DarkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${r.name} · ${bots.firstOrNull { it.id == r.botId }?.name ?: "?"}", color = C.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${r.prompt}\n⏱ ${maxOf(15, r.intervalMinutes)} min", color = C.zinc400, fontSize = 11.sp)
                        }
                        IconButton(onClick = { vm.runRoutine(r) }) { Icon(Icons.Default.PlayArrow, null, tint = C.emerald) }
                        IconButton(onClick = { vm.deleteRoutine(r) }) { Icon(Icons.Default.Delete, null, tint = C.zinc400) }
                    }
                }
            }
        }
    }
}

/** Create bot (Dashboard.handleCreateNewBot: name, role, model) and edit (system prompt) dialog. */
@Composable
fun BotDialog(vm: MainViewModel, bot: Bot?, onDismiss: () -> Unit) {
    val installed by vm.installed.collectAsState()
    var name by remember { mutableStateOf(bot?.name ?: "New Assistant") }
    var role by remember { mutableStateOf(bot?.role ?: "General Intelligence") }
    var sp by remember { mutableStateOf(bot?.systemPrompt ?: "") }
    var model by remember { mutableStateOf(bot?.model ?: "") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.popover,
        title = { Text(stringResource(if (bot == null) R.string.create_bot else R.string.edit_bot), fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.bot_name)) }, colors = darkFieldColors(), singleLine = true)
                OutlinedTextField(role, { role = it }, label = { Text(stringResource(R.string.role)) }, colors = darkFieldColors(), singleLine = true)
                Box {
                    OutlinedButton(onClick = { menu = true }, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.model) + ": " + model.ifEmpty { stringResource(R.string.default_model) }.removeSuffix(".gguf"), fontSize = 12.sp) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.default_model)) }, onClick = { model = ""; menu = false })
                        installed.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { model = f.name; menu = false }) }
                    }
                }
                if (bot != null) OutlinedTextField(sp, { sp = it }, label = { Text(stringResource(R.string.system_prompt)) }, minLines = 4, colors = darkFieldColors())
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isNotBlank()) { if (bot == null) vm.createBot(name, role, model) else vm.updateBot(bot.copy(name = name, role = role, systemPrompt = sp, model = model)) }
                onDismiss()
            }, colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (bot != null) TextButton(onClick = { vm.deleteBot(bot); onDismiss() }) { Text(stringResource(R.string.delete), color = C.rose400) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        })
}
