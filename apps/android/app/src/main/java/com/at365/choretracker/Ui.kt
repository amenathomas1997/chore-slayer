package com.at365.choretracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// AAA-contrast palette, the same as the iOS app (≥ 7:1 with white text).
object Palette {
    val brand = Color(0xFF0D47A1)
    val brandPurple = Color(0xFF4527A0)
    val muted = Color(0xFF545454)
    val danger = Color(0xFFB00020)
    val goodGreen = Color(0xFF1B5E20)
    val errorBg = Color(0xFFFFE3E3)
    val errorInk = Color(0xFF6E1414)
    val noticeBg = Color(0xFFE3F2E5)
    val taskDone = Color(0xFFD7F5DD)
    val taskPartial = Color(0xFFFFF3CD)
    val card = Color(0xFFF1F3F8)
}

fun hexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")) }.getOrDefault(Palette.brand)

fun colorName(hex: String) = when (hex.lowercase()) {
    "#5b8def" -> "Blue"; "#e8618c" -> "Pink"; "#33b679" -> "Green"
    "#f5a623" -> "Orange"; "#6d4fc0" -> "Purple"; "#16a3b8" -> "Teal"
    else -> "Color"
}

@Composable
fun ChoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.brand, onPrimary = Color.White,
            secondary = Palette.brandPurple, onSecondary = Color.White,
            error = Palette.danger, background = Color.White, surface = Color.White,
        ),
        content = content,
    )
}

@Composable
fun Title(text: String, a11y: String? = null, center: Boolean = false) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
        modifier = Modifier.fillMaxWidth().semantics { heading(); if (a11y != null) contentDescription = a11y })
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { heading() })
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, color: Color = Palette.brand, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text) }
}

@Composable
fun DangerButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    PrimaryButton(text, enabled, Palette.danger, onClick)
}

@Composable
fun LinkButton(text: String, color: Color = Palette.brand, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(text, color = color) }
}

/** Called on typing in any field, so on-screen-keyboard typing counts as parent activity for the idle lock. */
val LocalUserActivity = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true,
          keyboard: KeyboardOptions = KeyboardOptions.Default) {
    val active = LocalUserActivity.current
    OutlinedTextField(value = value, onValueChange = { active(); onChange(it) }, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
}

/** A numeric, hidden PIN field limited to 8 digits. */
@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    val active = LocalUserActivity.current
    OutlinedTextField(
        value = value, onValueChange = { active(); onChange(it.filter(Char::isDigit).take(8)) },
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth())
}

@Composable
fun Card(modifier: Modifier = Modifier, color: Color = Palette.card, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

/** A tappable card row (list item that opens something). */
@Composable
fun RowCard(label: String, hint: String? = null, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.card)
            .clickable(onClickLabel = hint, role = Role.Button) { onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .heightIn(min = 56.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun SelectChip(label: String, selected: Boolean, a11y: String? = null, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else Color.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.brand else Palette.card)
            .border(1.dp, if (selected) Color.Transparent else Color(0x40000000), RoundedCornerShape(50))
            .clickable(role = Role.Button) { onClick() }
            .semantics { this.selected = selected; if (a11y != null) contentDescription = a11y }
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    )
}

@Composable
fun Banner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (isError) Palette.errorBg else Palette.noticeBg)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (isError) "Error: $text" else text, color = if (isError) Palette.errorInk else Palette.goodGreen,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/**
 * The app-wide error and notice lines under the top bar. Both Text nodes are always composed and
 * carry the live region themselves, so a new message is a text change TalkBack reliably announces
 * (assertive for errors, polite for notices). Empty messages take no space.
 */
@Composable
fun StatusArea(m: AppModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusLine(m.error?.let { "Error: $it" } ?: "", isError = true) { m.error = null }
        StatusLine(m.notice ?: "", isError = false) { m.notice = null }
    }
}

@Composable
private fun StatusLine(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val shown = text.isNotEmpty()
    Row(
        (if (shown) Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (isError) Palette.errorBg else Palette.noticeBg).padding(start = 12.dp)
        else Modifier.fillMaxWidth()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (isError) Palette.errorInk else Palette.goodGreen,
            modifier = Modifier.weight(1f)
                .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite }
                .then(if (shown) Modifier.padding(vertical = 10.dp) else Modifier))
        if (shown) TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Dismiss", modifier = Modifier.semantics { contentDescription = if (isError) "Dismiss error" else "Dismiss message" })
        }
    }
}

@Composable
fun Gap(h: Int = 4) = Spacer(Modifier.padding(top = h.dp).width(1.dp))

/**
 * Returns a function that asks for notification permission (Android 13+) and
 * then calls [onResult]. Older Android versions don't need to ask.
 */
@Composable
fun rememberNotificationPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return remember(launcher) {
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) onResult(true)
            else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

val slotIds = listOf("morning", "midday", "afternoon", "evening", "anytime")
fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

/** Shift a yyyy-MM-dd date by a number of days. */
fun shiftDate(ymd: String, days: Int): String =
    runCatching { java.time.LocalDate.parse(ymd).plusDays(days.toLong()).toString() }.getOrDefault(ymd)
