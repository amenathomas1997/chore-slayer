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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectChip("✨ Create a family", create, "Create a family") { create = true }
        SelectChip("🔗 Connect this device", !create, "Connect this device") { create = false }
    }
    if (create) CreateFamilyForm(m) else ConnectFamilyForm(m)
}

@Composable
private fun CreateFamilyForm(m: AppModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var pin2 by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    SectionHeader("Create your family")
    Muted("Choose a parent PIN (4–8 digits). You'll get a Family Code to add more devices, sign in on the web, or recover.")
    Field(name, { name = it }, "Family name (optional)")
    PinField(pin, { pin = it }, "Parent PIN")
    PinField(pin2, { pin2 = it }, "Confirm PIN")
    problem?.let { Banner(it, true) { problem = null } }
    PrimaryButton("Create family", enabled = !m.busy) {
        problem = when {
            pin.length < 4 -> "PIN must be 4 to 8 digits."
            pin != pin2 -> "The two PINs do not match."
            else -> null
        }
        if (problem == null) m.createFamily(name.trim(), pin)
    }
}

@Composable
private fun ConnectFamilyForm(m: AppModel) {
    var code by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    SectionHeader("Connect this device")
    Muted("Enter your Family Code and parent PIN to link this device to an existing family.")
    Field(code, { code = it.uppercase() }, "Family code",
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false))
    PinField(pin, { pin = it }, "Parent PIN")
    PrimaryButton("Connect", enabled = !m.busy && code.isNotBlank() && pin.length >= 4) { m.connectDevice(code.trim(), pin) }
}

/** Shows the family code, like the web app's "Your Family Code" box. */
@Composable
fun FamilyCodeDialog(code: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
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
                }) { Text("Copy code") }
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
    var problem by remember { mutableStateOf<String?>(null) }
    Title("Change PIN")
    PinField(current, { current = it }, "Current PIN")
    PinField(new1, { new1 = it }, "New PIN")
    PinField(new2, { new2 = it }, "Confirm new PIN")
    problem?.let { Banner(it, true) { problem = null } }
    PrimaryButton("Change PIN", enabled = !m.busy && current.length >= 4) {
        problem = when {
            new1.length < 4 -> "New PIN must be 4–8 digits."
            new1 != new2 -> "The two new PINs do not match."
            else -> null
        }
        if (problem == null) m.changePin(current, new1)
    }
}

/** Account deletion: removes the family, children, tasks and history for good. */
@Composable
fun DeleteFamilyScreen(m: AppModel) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    Title("Delete family")
    Text("This permanently deletes your family account, every child, all tasks, history and reports, on every device and on the web. It cannot be undone.")
    PinField(pin, { pin = it }, "Parent PIN")
    DangerButton("Delete everything", enabled = !m.busy && pin.length >= 4) { confirming = true }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this family and all its data?") },
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
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Send me parent alerts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = { on = it },
            modifier = Modifier.semantics { contentDescription = "Send me parent alerts" })
    }
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
