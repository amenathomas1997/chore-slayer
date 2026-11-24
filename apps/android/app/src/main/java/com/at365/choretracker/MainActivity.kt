package com.at365.choretracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds only: `adb shell am start -n com.at365.choreslayer/com.at365.choretracker.MainActivity -e apiBase <url>`
        if (BuildConfig.DEBUG) intent?.getStringExtra("apiBase")?.let { model.session.debugBase = it }
        Push.createChannel(this)
        setContent { ChoreTheme { Root(model) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(m: AppModel) {
    var showCode by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (m.statusInfo == null) m.connect() }
    // The 10-minute parent lock: check every 30 s and whenever the app comes back.
    LaunchedEffect(Unit) { while (true) { delay(30_000); m.checkParentIdle() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { m.checkParentIdle(); m.refreshPushAllowed() }

    val canGoBack = when (m.screen) {
        Screen.Connecting, Screen.ConnectDevice, Screen.Role, Screen.ParentHome -> false
        else -> true
    }
    BackHandler(enabled = canGoBack) { m.back() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Chore Slayer") },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = { m.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (m.isParent) {
                        TextButton(onClick = { m.handToKid() },
                            modifier = Modifier.semantics { contentDescription = "Kid sign-in: sign the parent out and let a kid sign in" }) {
                            Text("🧒 Kid sign-in")
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Parent options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Family code") }, onClick = { menu = false; showCode = true })
                            DropdownMenuItem(text = { Text("Notifications") }, onClick = { menu = false; m.openNotifications() })
                            DropdownMenuItem(text = { Text("Change PIN") }, onClick = { menu = false; m.openChangePin() })
                            DropdownMenuItem(text = { Text("Delete family…", color = Palette.danger) }, onClick = { menu = false; m.openDeleteFamily() })
                            DropdownMenuItem(text = { Text("Log out") }, onClick = { menu = false; m.logout() })
                        }
                    } else if (m.screen == Screen.KidBoard || m.screen == Screen.KidSummary) {
                        TextButton(onClick = { m.kidSwitchUser() },
                            modifier = Modifier.semantics { contentDescription = "Switch user or log out" }) { Text("Switch user") }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).imePadding()
            // Any touch counts as parent activity for the idle lock.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(); m.markParentActivity() } }
            }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                m.error?.let { Banner(it, true) { m.error = null } }
                m.notice?.let { Banner(it, false) { m.notice = null } }
                if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" })
                when (m.screen) {
                    Screen.Connecting -> ConnectingScreen(m)
                    Screen.ConnectDevice -> ConnectDeviceScreen(m)
                    Screen.Role -> RoleScreen(m)
                    Screen.KidLogin -> KidLoginScreen(m)
                    Screen.KidBoard -> BoardScreen(m)
                    Screen.KidSummary -> KidSummaryScreen(m)
                    Screen.ChangePin -> ChangePinScreen(m)
                    Screen.DeleteFamily -> DeleteFamilyScreen(m)
                    Screen.Notifications -> NotificationsScreen(m)
                    Screen.ParentLogin -> ParentLoginScreen(m)
                    Screen.ParentHome -> ChildrenScreen(m)
                    Screen.ChildHub -> ChildHubScreen(m)
                    Screen.ChildEditor -> ChildEditorScreen(m)
                    Screen.Tasks -> TasksScreen(m)
                    Screen.TaskEditor -> TaskEditorScreen(m)
                    Screen.DailyReport -> DailyReportScreen(m)
                    Screen.Bhis -> BhisScreen(m)
                    Screen.AdjustDay -> AdjustDayScreen(m)
                    Screen.OverrideEditor -> OverrideEditorScreen(m)
                }
            }
        }
    }

    if (showCode) FamilyCodeDialog(m.family?.code ?: "—") { showCode = false }
    m.newFamilyCode?.let { code -> FamilyCodeDialog(code) { m.newFamilyCode = null } }
}
