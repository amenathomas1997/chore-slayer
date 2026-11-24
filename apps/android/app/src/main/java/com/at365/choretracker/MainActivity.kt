package com.at365.choretracker

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is light-only: keep dark status/nav bar icons even when the phone is in dark mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // Debug builds only: `adb shell am start -n com.at365.choreslayer/com.at365.choretracker.MainActivity -e apiBase <url>`
        if (BuildConfig.DEBUG) intent?.getStringExtra("apiBase")?.let { model.session.debugBase = it }
        Push.createChannels(this)
        handleNotificationIntent(intent)
        // The parent idle lock must count TalkBack, Switch Access and Voice Access actions too.
        // Those don't always reach onUserInteraction or pointerInput, but they do send these events.
        window.decorView.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: AccessibilityEvent): Boolean {
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED -> model.markParentActivity()
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        }
        setContent {
            CompositionLocalProvider(LocalUserActivity provides model::markParentActivity) {
                ChoreTheme { Root(model) }
            }
        }
    }

    /** A tapped notification while the app is already open (singleTop): no restart, the kid stays signed in. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: android.content.Intent?) {
        val childId = intent?.getStringExtra(Push.EXTRA_CHILD_ID) ?: return
        if (intent.getStringExtra(Push.EXTRA_TYPE) == "reminder") model.openFromNotification(childId)
        intent.removeExtra(Push.EXTRA_CHILD_ID)
    }

    /** Touches, hardware keys and trackball: all count as parent activity for the idle lock. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        model.markParentActivity()
    }
}

/** The name of each screen: shown in the top bar and announced by TalkBack when the screen changes. */
fun screenTitle(m: AppModel): String = when (m.screen) {
    Screen.Connecting -> "Chore Slayer"
    Screen.ConnectDevice -> "Welcome to Chore Slayer"
    Screen.Role -> "Who's using the app?"
    Screen.KidLogin -> "Kid sign-in"
    Screen.KidBoard -> m.child?.let { "${it.firstName}'s chores" } ?: "My chores"
    Screen.KidSummary -> m.child?.let { "${it.firstName}'s day" } ?: "My day"
    Screen.ChangePin -> "Change PIN"
    Screen.DeleteFamily -> "Delete family"
    Screen.Notifications -> "Notifications"
    Screen.ParentLogin -> "Parent sign-in"
    Screen.ParentHome -> "Children"
    Screen.ChildHub -> m.managedChild?.let { "${it.firstName} ${it.lastName}" } ?: "Child"
    Screen.ChildEditor -> if (m.editingChild == null) "Add a child" else "Edit child"
    Screen.Tasks -> "Tasks & activities"
    Screen.TaskEditor -> if (m.editingTask == null) "Add task or activity" else "Edit task"
    Screen.DailyReport -> "Daily report"
    Screen.Bhis -> "BHIS report"
    Screen.AdjustDay -> "Adjust a day"
    Screen.OverrideEditor -> "Adjust for one day"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(m: AppModel) {
    var showCode by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (m.statusInfo == null) m.connect() }
    // The 10-minute parent lock: check every 30 s and whenever the app comes back.
    LaunchedEffect(Unit) { while (true) { delay(5_000); m.checkParentIdle() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { m.checkParentIdle(); m.refreshPushAllowed() }

    val canGoBack = when (m.screen) {
        Screen.Connecting, Screen.ConnectDevice, Screen.Role, Screen.ParentHome -> false
        else -> true
    }
    // Back from an editor with unsaved changes asks first (top bar and system Back alike).
    val goBack = { if (m.unsavedChanges) askDiscard = true else m.back() }
    BackHandler(enabled = canGoBack) { goBack() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Chore Slayer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = { goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (m.isParent) {
                        TextButton(onClick = { m.handToKid() },
                            modifier = Modifier.semantics { contentDescription = "Kid sign-in: sign the parent out and let a kid sign in" }) {
                            Text("Kid sign-in")
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
            Column(Modifier.fillMaxSize()) {
                // Always composed, outside the scrolling content: errors and notices stay on screen
                // and are announced (live regions) wherever the button that caused them is.
                StatusArea(m)
                // The bar's space is always reserved, so content doesn't jump when loading starts or ends.
                Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 16.dp)) {
                    if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" })
                }
                // A fresh scroll position per screen, and a pane title so TalkBack announces the new screen.
                key(m.screen) {
                    val scroll = rememberScrollState()
                    val title = screenTitle(m)
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(scroll).padding(16.dp)
                            .semantics { paneTitle = title },
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
        }
    }

    if (m.idleWarning) {
        AlertDialog(
            onDismissRequest = { m.stayParentSignedIn() },
            title = { Text("Still there?") },
            text = { Text("The parent area locks in 60 seconds.") },
            confirmButton = { TextButton(onClick = { m.stayParentSignedIn() }) { Text("Stay signed in") } },
        )
    }

    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have changes that aren't saved. If you go back now, they'll be lost.") },
            confirmButton = { TextButton(onClick = { askDiscard = false; m.discardAndGoBack() }) { Text("Discard", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { askDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (showCode) FamilyCodeDialog(m.family?.code ?: "—") { showCode = false }
    m.newFamilyCode?.let { code -> FamilyCodeDialog(code) { m.newFamilyCode = null } }
}
