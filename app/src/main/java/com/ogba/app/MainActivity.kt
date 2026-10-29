package com.ogba.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ogba.app.data.Bot

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { OgbaApp(vm) }
            }
        }
    }
}

enum class Tab(val label: Int, val icon: ImageVector) {
    Chat(R.string.tab_chat, Icons.Default.Home), Bots(R.string.tab_bots, Icons.Default.Person),
    Memory(R.string.tab_memory, Icons.Default.Star), Routines(R.string.tab_routines, Icons.Default.DateRange),
    Models(R.string.tab_models, Icons.Default.Build)
}

@Composable
fun OgbaApp(vm: MainViewModel) {
    var tab by remember { mutableStateOf(Tab.Chat) }
    val err by vm.error.collectAsState()
    val tool by vm.pendingTool.collectAsState()
    Scaffold(bottomBar = {
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Chat -> ChatScreen(vm) { tab = Tab.Models }
                Tab.Bots -> BotsScreen(vm)
                Tab.Memory -> MemoryScreen(vm)
                Tab.Routines -> RoutinesScreen(vm)
                Tab.Models -> ModelsScreen(vm)
            }
        }
    }
    tool?.let { call ->
        AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.tool_approval)) },
            text = { Text(stringResource(R.string.tool_wants, call.name) + "\n\n" + call.arg) },
            confirmButton = { Button(onClick = { vm.resolveTool(true) }) { Text(stringResource(R.string.approve)) } },
            dismissButton = { TextButton(onClick = { vm.resolveTool(false) }) { Text(stringResource(R.string.deny)) } })
    }
    err?.let { e ->
        AlertDialog(onDismissRequest = { vm.error.value = null },
            text = { Text(if (e == "no_model") stringResource(R.string.no_model) else e) },
            confirmButton = { TextButton(onClick = { vm.error.value = null; if (e == "no_model") tab = Tab.Models }) { Text("OK") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel, openModels: () -> Unit) {
    val bots by vm.bots.collectAsState()
    val botId by vm.currentBotId.collectAsState()
    val msgs by vm.messages.collectAsState()
    val stream by vm.streaming.collectAsState()
    val model by vm.selectedModel.collectAsState()
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, stream) { val n = msgs.size + (if (stream != null) 1 else 0); if (n > 0) list.scrollToItem(n - 1) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = {
            Box {
                TextButton(onClick = { menu = true }) { Text((bots.firstOrNull { it.id == botId }?.name ?: "OGBA") + " ▾", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    bots.forEach { b -> DropdownMenuItem(text = { Text(b.name) }, onClick = { vm.selectBot(b.id); menu = false }) }
                }
            }
        }, actions = {
            IconButton(onClick = { vm.clearChat() }) { Icon(Icons.Default.Delete, stringResource(R.string.clear_chat)) }
        })
        if (model == null) {
            Card(Modifier.padding(8.dp).fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.no_model), Modifier.weight(1f))
                    TextButton(onClick = openModels) { Text(stringResource(R.string.tab_models)) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), state = list) {
            items(msgs, key = { it.id }) { Bubble(it.role, it.content) }
            stream?.let { s -> item { Bubble("assistant", s.ifEmpty { "…" }) } }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.type_message)) }, maxLines = 5)
            Spacer(Modifier.width(6.dp))
            if (stream != null) IconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Close, stringResource(R.string.stop)) }
            else IconButton(onClick = { vm.send(input); input = "" }) { Icon(Icons.Default.Send, stringResource(R.string.send)) }
        }
    }
}

@Composable
fun Bubble(role: String, text: String) {
    val user = role == "user"
    val color = when (role) {
        "user" -> MaterialTheme.colorScheme.primaryContainer
        "tool" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp).background(color, RoundedCornerShape(14.dp)).padding(10.dp)) {
            if (role == "tool") Text("🛠 tool", style = MaterialTheme.typography.labelSmall)
            Text(text)
        }
    }
}

@Composable
fun BotsScreen(vm: MainViewModel) {
    val bots by vm.bots.collectAsState()
    var editing by remember { mutableStateOf<Bot?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_bots), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = { editing = Bot(name = "", systemPrompt = "") }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.new_bot)) }
        }
        LazyColumn {
            items(bots, key = { it.id }) { b ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleMedium)
                            Text(b.systemPrompt, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editing = b }) { Icon(Icons.Default.Edit, null) }
                        IconButton(onClick = { vm.deleteBot(b) }) { Icon(Icons.Default.Delete, null) }
                    }
                }
            }
        }
    }
    editing?.let { b ->
        var name by remember(b) { mutableStateOf(b.name) }
        var sp by remember(b) { mutableStateOf(b.systemPrompt) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.edit_bot)) }, text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) })
                OutlinedTextField(sp, { sp = it }, label = { Text(stringResource(R.string.system_prompt)) }, minLines = 4)
            }
        }, confirmButton = { Button(onClick = { if (name.isNotBlank()) vm.saveBot(b.copy(name = name, systemPrompt = sp)); editing = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
fun MemoryScreen(vm: MainViewModel) {
    val mems by vm.memories.collectAsState()
    val notes by vm.notes.collectAsState()
    var input by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.memory_hint), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.add_memory)) })
                IconButton(onClick = { vm.addMemory(input); input = "" }) { Icon(Icons.Default.Add, null) }
            }
        }
        items(mems, key = { "m" + it.id }) { m ->
            ListItem(headlineContent = { Text(m.text) }, trailingContent = { IconButton(onClick = { vm.deleteMemory(m) }) { Icon(Icons.Default.Delete, null) } })
        }
        item { Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.headlineSmall) }
        items(notes, key = { "n" + it.id }) { n ->
            ListItem(headlineContent = { Text(n.text) }, trailingContent = { IconButton(onClick = { vm.deleteNote(n) }) { Icon(Icons.Default.Delete, null) } })
        }
    }
}

@Composable
fun RoutinesScreen(vm: MainViewModel) {
    val routines by vm.routines.collectAsState()
    val bots by vm.bots.collectAsState()
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(stringResource(R.string.tab_routines), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.routine_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) })
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.routine_prompt)) }, minLines = 2)
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.interval_min)) })
            Button(onClick = {
                if (name.isNotBlank() && prompt.isNotBlank()) { vm.addRoutine(name, prompt, minutes.toLongOrNull() ?: 60); name = ""; prompt = "" }
            }, Modifier.padding(vertical = 8.dp)) { Text(stringResource(R.string.add_routine)) }
        }
        items(routines, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.name} · ${bots.firstOrNull { it.id == r.botId }?.name ?: "?"}", style = MaterialTheme.typography.titleMedium)
                        Text("${r.prompt}\n⏱ ${maxOf(15, r.intervalMinutes)} min", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { vm.runRoutine(r) }) { Icon(Icons.Default.PlayArrow, null) }
                    IconButton(onClick = { vm.deleteRoutine(r) }) { Icon(Icons.Default.Delete, null) }
                }
            }
        }
    }
}

@Composable
fun ModelsScreen(vm: MainViewModel) {
    val installed by vm.installed.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it) } }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        item {
            Text(stringResource(R.string.installed_models), style = MaterialTheme.typography.headlineSmall)
            if (installed.isEmpty()) Text(stringResource(R.string.no_model))
        }
        items(installed, key = { it.absolutePath }) { f ->
            ListItem(headlineContent = { Text(f.name) }, supportingContent = { Text("${f.length() / 1_000_000} MB") },
                leadingContent = { RadioButton(selected = selected == f.absolutePath, onClick = { vm.selectModel(f) }) },
                trailingContent = { IconButton(onClick = { vm.deleteModel(f) }) { Icon(Icons.Default.Delete, null) } })
        }
        item {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.padding(vertical = 8.dp)) { Text(stringResource(R.string.import_gguf)) }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.download_models), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.download_hint), style = MaterialTheme.typography.bodySmall)
        }
        items(Models.catalog, key = { it.id }) { m ->
            val p = downloads[m.id]
            val have = installed.any { it.name == m.fileName }
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(m.title); Text("~${m.sizeMb} MB", style = MaterialTheme.typography.bodySmall) }
                        if (have) Icon(Icons.Default.Check, null)
                        else if (p == null) Button(onClick = { vm.download(m) }) { Text(stringResource(R.string.download)) }
                    }
                    if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}
