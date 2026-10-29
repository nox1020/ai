package com.ogba.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ogba.app.MainViewModel
import com.ogba.app.R
import com.ogba.app.Screen
import com.ogba.app.data.Bot
import java.text.SimpleDateFormat
import java.util.*

/** Port of Sidebar.jsx: header dots + new bot, search, bot roster, Plugins / Audit trail / profile / settings footer. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Sidebar(vm: MainViewModel, onNewBot: () -> Unit, onEditBot: (Bot) -> Unit, close: () -> Unit) {
    val bots by vm.bots.collectAsState()
    val active by vm.currentBotId.collectAsState()
    val screen by vm.screen.collectAsState()
    val last by vm.lastPerBot.collectAsState()
    val user by vm.userName.collectAsState()
    var q by remember { mutableStateOf("") }
    val errorCount = last.count { it.role == "error" }
    var dismissed by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("h:mm a", Locale.US) }

    Column(Modifier.fillMaxHeight().width(300.dp).background(C.sidebar)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(0xFFFF5F57, 0xFFFEBC2E, 0xFF28C840).forEach {
                    Box(Modifier.padding(end = 8.dp).size(12.dp).background(Color(it), CircleShape))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onNewBot, Modifier.size(32.dp)) { Icon(Icons.Default.Add, stringResource(R.string.create_bot), tint = C.zinc400) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().background(C.search, RoundedCornerShape(12.dp)).border(1.dp, C.searchBorder, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = C.zinc500, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text(stringResource(R.string.search), color = C.zinc500, fontSize = 12.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color(0xFFE4E4E7), fontSize = 12.sp), cursorBrush = SolidColor(C.zinc300), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        val filtered = bots.filter { it.name.contains(q, true) || it.role.contains(q, true) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            items(filtered, key = { it.id }) { b ->
                val idx = bots.indexOf(b)
                val lm = last.firstOrNull { it.botId == b.id }
                val isErr = lm?.role == "error"
                val sel = b.id == active && screen == Screen.Chat
                Row(Modifier.padding(vertical = 3.dp).fillMaxWidth()
                    .background(if (sel) C.cardActive else Color.Transparent, RoundedCornerShape(12.dp))
                    .border(1.dp, if (sel) C.cardActiveBorder else Color.Transparent, RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { vm.selectBot(b.id); close() }, onLongClick = { onEditBot(b) })
                    .padding(10.dp)) {
                    MascotAvatar(if (isErr) "warning" else if (idx % 2 == 1) "pink" else "blue", 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.name, color = if (sel) Color.White else Color(0xFFE4E4E7), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(timeFmt.format(Date(lm?.ts ?: b.createdAt)), color = C.zinc400, fontSize = 10.sp)
                        }
                        Text(if (isErr) lm!!.content else b.role, color = C.zinc400, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(color = C.divider)
        Column(Modifier.padding(12.dp)) {
            FooterItem("🧩", stringResource(R.string.plugins), screen == Screen.Marketplace) { vm.open(Screen.Marketplace); close() }
            FooterItem(null, stringResource(R.string.audit_trail), screen == Screen.Audit) { vm.open(Screen.Audit); close() }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (errorCount > 0 && !dismissed) {
                    Row(Modifier.background(Color(0xE6E11D48), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color.White, CircleShape)); Spacer(Modifier.width(6.dp))
                        Text(pluralStringIssue(errorCount), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("  ✕", color = Color.White, fontSize = 11.sp, modifier = Modifier.clickable { dismissed = true })
                    }
                } else {
                    Row(Modifier.clickable { vm.open(Screen.Settings); close() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(Color(0xFF2A2A2E), CircleShape).border(1.dp, Color(0xFF333338), CircleShape), contentAlignment = Alignment.Center) {
                            Text(user.take(1).uppercase(), color = C.zinc400, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp)); Text(user, color = C.zinc300, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.open(Screen.Settings); close() }) { Icon(Icons.Default.Settings, stringResource(R.string.settings), tint = C.zinc400, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable private fun pluralStringIssue(n: Int) = if (n > 1) stringResource(R.string.issues, n) else stringResource(R.string.issue, n)

@Composable
private fun FooterItem(emoji: String?, label: String, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (active) C.bubbleBot else Color.Transparent, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (emoji != null) Text(emoji, fontSize = 14.sp) else Icon(Icons.Default.Refresh, null, tint = C.cyan400, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (active) Color.White else C.zinc300, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
