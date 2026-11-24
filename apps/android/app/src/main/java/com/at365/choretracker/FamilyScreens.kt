package com.at365.choretracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ConnectingScreen(m: AppModel) {
    Title("Chore Slayer")
    if (m.error != null && !m.busy) {
        Text("Couldn't reach Chore Slayer. Check your internet connection, then try again.")
        PrimaryButton("Try again") { m.connect() }
    } else Muted("Connecting…")
}

/** First run: create a new family, or link this device to an existing one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectDeviceScreen(m: AppModel) {
    var create by rememberSaveable { mutableStateOf(true) }
    Title("Welcome to Chore Slayer")
    Muted("Set up your family to get started.")
    ChoiceGroup("What would you like to do?") {
        RadioChip("Create a family", create, icon = "✨") { create = true }
        RadioChip("Connect this device", !create, icon = "🔗") { create = false }
    }
    if (create) CreateFamilyForm(m) else ConnectFamilyForm(m)
}

@Composable
private fun CreateFamilyForm(m: AppModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var pin2 by rememberSaveable { mutableStateOf("") }
    // Which field is wrong ("pin" or "pin2") and why; the error shows under it and focus moves there.
    var problem by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tries by remember { mutableStateOf(0) }
    val pinFocus = remember { FocusRequester() }
    val pin2Focus = remember { FocusRequester() }
    LaunchedEffect(tries) { when (problem?.first) { "pin" -> pinFocus.requestFocus(); "pin2" -> pin2Focus.requestFocus() } }
    SectionHeader("Create your family")
    Muted("Choose a parent PIN. You'll get a Family Code to add more devices, sign in on the web, or recover.")
    Field(name, { name = it }, "Family name (optional)")
    PinField(pin, { pin = it; if (problem?.first == "pin") problem = null }, "Parent PIN",
        error = problem?.takeIf { it.first == "pin" }?.second, focus = pinFocus)
    PinField(pin2, { pin2 = it; if (problem?.first == "pin2") problem = null }, "Confirm PIN",
        error = problem?.takeIf { it.first == "pin2" }?.second, focus = pin2Focus)
    PrimaryButton("Create family", enabled = !m.busy) {
        problem = when {
            pin.length < 4 -> "pin" to "PIN must be 4 to 8 digits."
            pin != pin2 -> "pin2" to "The two PINs do not match."
            else -> null
        }
        if (problem == null) m.createFamily(name.trim(), pin) else tries++
    }
}

@Composable
private fun ConnectFamilyForm(m: AppModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    val pinFocus = remember { FocusRequester() }
    val err = m.fieldError?.takeIf { it.field == "pin" }
    LaunchedEffect(err?.seq) { if (err != null) pinFocus.requestFocus() }
    SectionHeader("Connect this device")
    Muted("Enter your Family Code and parent PIN to link this device to an existing family.")
    Field(code, { code = it.uppercase() }, "Family code",
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false))
    PinField(pin, { pin = it }, "Parent PIN", error = err?.message, focus = pinFocus)
    PrimaryButton("Connect", enabled = !m.busy && code.isNotBlank() && pin.length >= 4) { m.connectDevice(code.trim(), pin) }
}

/** Shows the family code, like the web app's "Your Family Code" box. */
@Composable
fun FamilyCodeDialog(code: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Your Family Code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Save this Family Code somewhere safe. You'll need it to add another device, sign in on the web, or recover your account:",
                    textAlign = TextAlign.Center)
                Text(code, fontSize = 32.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics { contentDescription = "Family code: " + code.toList().joinToString(" ") })
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Family code", code))
                    copied = true
                }) { Text("Copy code") }
                // Always composed, so "Copied" is a text change the polite live region announces.
                Text(if (copied) "Copied" else "", color = Palette.goodGreen, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Muted("From now on, your PIN unlocks the parent area on this device.")
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Got it") } },
    )
}

@Composable
fun ChangePinScreen(m: AppModel) {
    var current by rememberSaveable { mutableStateOf("") }
    var new1 by rememberSaveable { mutableStateOf("") }
    var new2 by rememberSaveable { mutableStateOf("") }
    // Local problems ("new1", "new2") and the server's wrong-current-PIN answer both show under their field.
    var problem by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tries by remember { mutableStateOf(0) }
    val focus = remember { mapOf("currentPin" to FocusRequester(), "new1" to FocusRequester(), "new2" to FocusRequester()) }
    val server = m.fieldError?.takeIf { it.field == "currentPin" }
    LaunchedEffect(tries) { problem?.let { focus[it.first]?.requestFocus() } }
    LaunchedEffect(server?.seq) { if (server != null) focus["currentPin"]?.requestFocus() }
    fun err(f: String) = problem?.takeIf { it.first == f }?.second
    Title("Change PIN")
    PinField(current, { current = it; m.fieldError = null }, "Current PIN", error = server?.message, focus = focus["currentPin"])
    PinField(new1, { new1 = it; if (problem?.first == "new1") problem = null }, "New PIN", error = err("new1"), focus = focus["new1"])
    PinField(new2, { new2 = it; if (problem?.first == "new2") problem = null }, "Confirm new PIN", error = err("new2"), focus = focus["new2"])
    PrimaryButton("Change PIN", enabled = !m.busy && current.length >= 4) {
        problem = when {
            new1.length < 4 -> "new1" to "New PIN must be 4 to 8 digits."
            new1 != new2 -> "new2" to "The two new PINs do not match."
            else -> null
        }
        if (problem == null) m.changePin(current, new1) else tries++
    }
}

/** Account deletion: removes the family, children, tasks and history for good. */
@Composable
fun DeleteFamilyScreen(m: AppModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    val pinFocus = remember { FocusRequester() }
    val err = m.fieldError?.takeIf { it.field == "pin" }
    LaunchedEffect(err?.seq) { if (err != null) pinFocus.requestFocus() }
    Title("Delete family")
    Text("This permanently deletes your family account, every child, all tasks, history and reports, on every device and on the web. It cannot be undone.")
    PinField(pin, { pin = it; m.fieldError = null }, "Parent PIN", error = err?.message, focus = pinFocus)
    DangerButton("Delete everything", enabled = !m.busy && pin.length >= 4) { confirming = true }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this family and all its data?") },
            text = { Text("Every child, task, check-off and report is erased on every device. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirming = false; m.deleteFamily(pin) }) { Text("Delete forever", color = Palette.danger) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/** Parent options → Notifications. */
@Composable
fun NotificationsScreen(m: AppModel) {
    val ctx = LocalContext.current
    var on by rememberSaveable { mutableStateOf(Push.parentAlerts(ctx)) }
    val ask = rememberNotificationPermission { granted -> m.saveParentAlerts(true, granted) }
    Title("Notifications")
    if (!m.pushAvailable) {
        Text("Notifications aren't available in this version of the app yet.")
        return
    }
    Text("Get alerts on this device:")
    Text("• when a kid finishes everything on their list for the day")
    Text("• once in the evening if chores are still left to do")
    SwitchRow("Send me parent alerts", on) { on = it }
    Muted("Kids turn on their own chore reminders from their board after they sign in.")
    if (m.pushAllowed == false) {
        Text("Notifications are turned off for Chore Slayer. Turn them on in the phone's Settings first.",
            color = Palette.danger, fontWeight = FontWeight.SemiBold)
        LinkButton("Open Settings") { ctx.startActivity(appNotificationSettings(ctx)) }
    }
    PrimaryButton("Save", enabled = !m.busy) { if (on) ask() else m.saveParentAlerts(false, true) }
}

fun appNotificationSettings(ctx: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .takeIf { android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O }
        ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
