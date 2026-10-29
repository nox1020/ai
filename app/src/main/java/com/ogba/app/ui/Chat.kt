package com.ogba.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ogba.app.*
import com.ogba.app.R
import com.ogba.app.data.Bot
import com.ogba.app.data.Message
import java.text.SimpleDateFormat
import java.util.*

private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

@Composable
fun ChatWindow(vm: MainViewModel, openDrawer: () -> Unit) {
    val bots by vm.bots.collectAsState()
    val botId by vm.currentBotId.collectAsState()
    val bot = bots.firstOrNull { it.id == botId } ?: bots.firstOrNull()
    val msgs by vm.messages.collectAsState()
    val streamMap by vm.streaming.collectAsState()
    val stream = streamMap[bot?.id]
    val approvals by vm.approvals.collectAsState()
    val events by vm.toolEvents.collectAsState()
    val myApprovals = approvals.filter { it.botId == bot?.id }
    val myEvents = events.filter { it.botId == bot?.id }
    val list = rememberLazyListState()
    val total = msgs.size + myApprovals.size + myEvents.size + (if (stream != null) 1 else 0)
    LaunchedEffect(total, stream?.length) { if (total > 0) list.animateScrollToItem(total) }

    Column(Modifier.fillMaxSize().background(C.background).imePadding()) {
        // Header bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Default.Menu, null, tint = C.zinc400) }
            MascotAvatar(if (msgs.lastOrNull()?.role == "error") "warning" else "blue", 28.dp)
            Spacer(Modifier.width(10.dp))
            Text(bot?.name ?: "OGBA", color = C.foreground, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (bot != null) ModelPicker(vm, bot)
            IconButton(onClick = { vm.open(Screen.Voice) }) { Text("🎙", fontSize = 16.sp) }
            IconButton(onClick = { vm.open(Screen.Computer) }) { Icon(Icons.Default.Info, stringResource(R.string.computer), tint = C.zinc400, modifier = Modifier.size(18.dp)) }
        }
        HorizontalDivider(color = C.headerBorder)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), state = list) {
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(headerDate(msgs), color = C.zinc500, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
            items(myApprovals, key = { it.requestId }) { ApprovalCard(it) { a -> vm.respondApproval(it.requestId, a) } }
            items(myEvents, key = { it.id }) { ToolEventCard(it) }
            items(msgs, key = { it.id }) { MessageItem(it) }
            if (stream != null) item {
                if (stream.isEmpty()) TypingIndicator() else MessageItem(Message(botId = 0, role = "bot", content = stream, ts = 0))
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Composer(vm, bot, streaming = stream != null)
    }
}

@Composable
private fun headerDate(msgs: List<Message>): String {
    val first = msgs.firstOrNull() ?: return stringResource(R.string.today)
    val d = Calendar.getInstance().apply { timeInMillis = first.ts }
    val now = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(d, now)) return stringResource(R.string.today)
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (same(d, now)) return stringResource(R.string.yesterday)
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(first.ts))
}

/** ModelPicker.jsx equivalent – lists on-device GGUF models instead of the MUAPI catalog. */
@Composable
fun ModelPicker(vm: MainViewModel, bot: Bot) {
    val installed by vm.installed.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    var open by remember { mutableStateOf(false) }
    val label = bot.model.ifEmpty { selected?.substringAfterLast('/') ?: stringResource(R.string.no_model_short) }.removeSuffix(".gguf")
    Box {
        Row(Modifier.background(C.popover, RoundedCornerShape(10.dp)).border(1.dp, C.pillBorder, RoundedCornerShape(10.dp)).clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (installed.isEmpty()) C.amber else C.emerald, CircleShape)); Spacer(Modifier.width(6.dp))
            Text(label.take(18), color = C.zinc300, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(" ▾", color = C.zinc500, fontSize = 11.sp)
        }
        DropdownMenu(open, { open = false }, Modifier.background(C.popover)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.default_model), fontSize = 12.sp) }, onClick = { vm.setBotModel(bot, ""); open = false },
                trailingIcon = { if (bot.model.isEmpty()) Icon(Icons.Default.Check, null, tint = C.emerald) })
            installed.forEach { f ->
                DropdownMenuItem(text = { Text(f.name.removeSuffix(".gguf"), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }, onClick = { vm.setBotModel(bot, f.name); open = false },
                    trailingIcon = { if (bot.model == f.name) Icon(Icons.Default.Check, null, tint = C.emerald) })
            }
            HorizontalDivider(color = C.pillBorder)
            DropdownMenuItem(text = { Text(stringResource(R.string.manage_models), fontSize = 12.sp, color = C.blue400) }, onClick = { vm.open(Screen.Models); open = false })
        }
    }
}

/** MessageItem.jsx: user / bot (markdown) / error bubbles with time stamps. */
@Composable
fun MessageItem(m: Message) {
    val time = if (m.ts > 0) timeFmt.format(Date(m.ts)) else ""
    when {
        m.role == "user" -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.End) {
            Column(Modifier.widthIn(max = 300.dp).background(C.bubbleUser, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(m.content, color = Color.White, fontSize = 13.sp)
                if (time.isNotEmpty()) Text(time, color = Color(0xB3D4D4D8), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.End))
            }
        }
        m.role == "error" || m.content.lowercase().startsWith("error:") -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(Modifier.widthIn(max = 320.dp).background(C.bubbleError, RoundedCornerShape(18.dp)).border(1.dp, C.bubbleErrorBorder, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp)); Spacer(Modifier.width(8.dp))
                Text(m.content, color = C.errorText, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f, false))
                if (time.isNotEmpty()) Text("  $time", color = C.zinc500, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        else -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.widthIn(max = 340.dp).background(C.bubbleBot, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Markdown(m.content)
                if (time.isNotEmpty()) Text(time, color = C.zinc400, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
            }
        }
    }
}

/** Small markdown renderer: paragraphs, **bold**, *italic*, `code`, ``` blocks, lists, headings. */
@Composable
fun Markdown(src: String) {
    val parts = src.split("```")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) {
            val code = part.substringAfter('\n', part).trimEnd()
            Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().background(C.sidebar, RoundedCornerShape(12.dp)).border(1.dp, C.pillBorder, RoundedCornerShape(12.dp))
                .horizontalScroll(rememberScrollState()).padding(12.dp)) {
                Text(code, color = C.zinc300, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        } else {
            part.trim('\n').split("\n").forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.isBlank() -> Spacer(Modifier.height(6.dp))
                    line.startsWith("#") -> Text(inline(line.trimStart('#').trim()), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
                    Regex("^\\s*[-*•] ").containsMatchIn(line) -> Text(inline("•  " + line.trim().drop(2)), color = C.zinc300, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                    Regex("^\\s*\\d+[.)] ").containsMatchIn(line) -> Text(inline(line.trim()), color = C.zinc300, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
                    else -> Text(inline(line), color = C.foreground, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(s.substring(i + 2, e)) }; i = e + 2 }
            s[i] == '`' && s.indexOf('`', i + 1) > 0 -> { val e = s.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = C.cyan300, background = C.codeBg, fontSize = 11.sp)) { append(" " + s.substring(i + 1, e) + " ") }; i = e + 1 }
            else -> { append(s[i]); i++ }
        }
    }
}

/** ApprovalCard.jsx – "Permission Broker Request". */
@Composable
fun ApprovalCard(a: Approval, onRespond: (String) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp).fillMaxWidth().background(C.slate900, RoundedCornerShape(16.dp)).border(1.dp, C.amber.copy(alpha = .3f), RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = C.amber400, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.permission_request).uppercase(), color = C.amber400, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(a.call.tool, color = Color(0xFFFCD34D), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.background(C.amber.copy(alpha = .1f), RoundedCornerShape(4.dp)).border(1.dp, C.amber.copy(alpha = .2f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = C.slate800)
        Row {
            Box(Modifier.size(32.dp).background(C.slate800, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text(">_", color = C.amber400, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(a.call.summary, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.permission_desc), color = C.slate400, fontSize = 11.sp)
                if (a.call.content.isNotEmpty()) Text(a.call.content.take(400), color = C.slate400, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp), color = C.slate800)
        if (a.status == "pending") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { onRespond("deny") }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = C.slate800, contentColor = C.rose400)) {
                    Icon(Icons.Default.Close, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.deny), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onRespond("allow") }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = C.emerald600, contentColor = Color.White)) {
                    Icon(Icons.Default.Check, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.allow_execution), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            val ok = a.status == "allowed"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.status), color = C.slate400, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text((if (ok) "✓ " else "✕ ") + stringResource(when (a.status) { "allowed" -> R.string.approved; "denied" -> R.string.denied; else -> R.string.expired }),
                    color = if (ok) Color(0xFF34D399) else C.rose400, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background((if (ok) C.emerald else C.rose).copy(alpha = .2f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun ToolEventCard(e: ToolEvent) {
    Column(Modifier.padding(vertical = 6.dp).fillMaxWidth().background(C.slate900, RoundedCornerShape(12.dp)).border(1.dp, C.slate800, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row {
            Text(e.tool, color = C.cyan300, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(e.type.removePrefix("tool."), color = if (e.type == "tool.completed") Color(0xFF34D399) else C.amber400, fontSize = 11.sp)
        }
        e.error?.let { Text(it, color = Color(0xFFFDA4AF), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        e.result?.let { Text(it.take(600), color = C.slate400, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
fun TypingIndicator() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        MascotAvatar("blue", 28.dp); Spacer(Modifier.width(12.dp))
        Row(Modifier.background(Color(0xFF18181B), RoundedCornerShape(16.dp)).border(1.dp, C.cardActive, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
            repeat(3) { i ->
                val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
                Box(Modifier.padding(horizontal = 3.dp).size(8.dp).alpha(a).background(C.blue400, CircleShape))
            }
        }
    }
}

/** Floating pill composer: + (attachments), input "Message {bot}", mic dictation, send/stop. */
@Composable
fun Composer(vm: MainViewModel, bot: Bot?, streaming: Boolean) {
    var input by remember { mutableStateOf("") }
    val noVision = stringResource(R.string.no_vision)
    val noVoice = stringResource(R.string.no_voice)
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = (input + " " + it).trim() }
    }
    Row(Modifier.padding(16.dp).fillMaxWidth().background(C.popover, CircleShape).border(1.dp, C.pillBorder, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.toast.value = noVision }, Modifier.size(36.dp)) { Icon(Icons.Default.Add, null, tint = C.zinc400) }
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
            if (input.isEmpty()) Text(stringResource(R.string.message_bot, bot?.name ?: "OGBA"), color = C.zinc500, fontSize = 13.sp)
            BasicTextField(input, { input = it }, textStyle = TextStyle(color = C.foreground, fontSize = 13.sp), cursorBrush = SolidColor(C.zinc300), maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" }), modifier = Modifier.fillMaxWidth())
        }
        if (input.isBlank() && !streaming) {
            IconButton(onClick = {
                try {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
                } catch (e: ActivityNotFoundException) { vm.toast.value = noVoice }
            }, Modifier.size(36.dp)) { Icon(Icons.Default.Phone, stringResource(R.string.dictate), tint = C.zinc400) }
        } else if (streaming) {
            IconButton(onClick = { vm.stop() }, Modifier.size(36.dp).background(C.cardActive, CircleShape)) { Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        } else {
            IconButton(onClick = { vm.send(input); input = "" }, Modifier.size(36.dp).background(Color.White, CircleShape)) { Icon(Icons.Default.Send, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
        }
    }
}
