package com.ogba.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ogba.app.data.Bot
import com.ogba.app.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BuildConfigInfo.version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { OgbaTheme { Surface(Modifier.fillMaxSize(), color = C.background) { Dashboard(vm) } } }
    }
}

/** Dashboard.jsx: Sidebar (as navigation drawer on phones) + main workspace area. */
@Composable
fun Dashboard(vm: MainViewModel) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val screen by vm.screen.collectAsState()
    val toast by vm.toast.collectAsState()
    val ctx = LocalContext.current
    var dialogBot by remember { mutableStateOf<Bot?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(toast) { toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toast.value = null } }
    BackHandler(screen != Screen.Chat || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else vm.open(if (screen in listOf(Screen.Models, Screen.Memory, Screen.Routines)) Screen.Settings else Screen.Chat)
    }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        Box(Modifier.systemBarsPadding()) {
            Sidebar(vm, onNewBot = { creating = true }, onEditBot = { dialogBot = it }) { scope.launch { drawer.close() } }
        }
    }) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            when (screen) {
                Screen.Chat -> ChatWindow(vm) { scope.launch { drawer.open() } }
                Screen.Computer -> ComputerPanel(vm)
                Screen.Browser -> BrowserPanel(vm)
                Screen.Marketplace -> Marketplace(vm)
                Screen.Audit -> AuditPanel(vm)
                Screen.Settings -> SettingsPanel(vm)
                Screen.Models -> ModelsPanel(vm)
                Screen.Memory -> MemoryPanel(vm)
                Screen.Routines -> RoutinesPanel(vm)
            }
        }
    }
    if (creating) BotDialog(vm, null) { creating = false }
    dialogBot?.let { b -> BotDialog(vm, b) { dialogBot = null } }
}
