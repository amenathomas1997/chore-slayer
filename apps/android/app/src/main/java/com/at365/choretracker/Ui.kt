package com.at365.choretracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
fun SectionHeader(text: String, icon: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically) {
        // The emoji is decoration: TalkBack reads only the words ("Morning", not "sunrise Morning").
        if (icon != null) Text("$icon ", style = MaterialTheme.typography.titleSmall, modifier = Modifier.clearAndSetSemantics { })
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
}

/** A text line with a decorative emoji in front that screen readers skip. */
@Composable
fun IconText(icon: String, text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
             style: TextStyle = MaterialTheme.typography.bodyLarge) {
    Row(modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Text("$icon ", style = style, modifier = Modifier.clearAndSetSemantics { })
        Text(text, style = style, color = color)
    }
}

/** Button content: an optional decorative emoji (not read aloud) and the words, which are the accessible name. */
@Composable
private fun ButtonLabel(text: String, icon: String?, iconAfter: Boolean = false) {
    if (icon != null && !iconAfter) Text("$icon ", modifier = Modifier.clearAndSetSemantics { })
    Text(text)
    if (icon != null && iconAfter) Text(" $icon", modifier = Modifier.clearAndSetSemantics { })
}

@Composable
fun PrimaryButton(text: String, icon: String? = null, enabled: Boolean = true, color: Color = Palette.brand,
                  modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = color),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { ButtonLabel(text, icon) }
}

@Composable
fun SecondaryButton(text: String, icon: String? = null, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) { ButtonLabel(text, icon) }
}

@Composable
fun DangerButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    PrimaryButton(text, enabled = enabled, color = Palette.danger, onClick = onClick)
}

@Composable
fun LinkButton(text: String, color: Color = Palette.brand, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(text, color = color) }
}

/** Called on typing in any field, so on-screen-keyboard typing counts as parent activity for the idle lock. */
val LocalUserActivity = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * A labelled text field. [error] shows under the field (and is the field's error for TalkBack);
 * otherwise [supporting] (a hint such as a format) shows there.
 */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, singleLine: Boolean = true,
          keyboard: KeyboardOptions = KeyboardOptions.Default, error: String? = null, supporting: String? = null,
          focus: FocusRequester? = null) {
    val active = LocalUserActivity.current
    OutlinedTextField(value = value, onValueChange = { active(); onChange(it) }, label = { Text(label) }, singleLine = singleLine,
        keyboardOptions = keyboard, isError = error != null,
        supportingText = (error ?: supporting)?.let { t -> { Text(t) } },
        modifier = Modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { if (error != null) this.error(error) })
}

/** A numeric PIN field limited to 8 digits, hidden unless "Show PIN" is on. */
@Composable
fun PinField(value: String, onChange: (String) -> Unit, label: String, error: String? = null,
             focus: FocusRequester? = null) {
    val active = LocalUserActivity.current
    var show by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = { active(); onChange(it.filter(Char::isDigit).take(8)) },
        label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        isError = error != null,
        supportingText = { Text(error ?: "4 to 8 digits") },
        trailingIcon = {
            TextButton(onClick = { show = !show }, modifier = Modifier.heightIn(min = 48.dp)
                .semantics { contentDescription = if (show) "Hide PIN" else "Show PIN" }) {
                Text(if (show) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { if (error != null) this.error(error) })
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

/** Shared look of choice chips: selected is filled and ticked, so it differs by more than colour. */
@Composable
private fun ChipBody(label: String, selected: Boolean, icon: String?, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.brand else Palette.card)
            // Unselected outline: Palette.muted is well over 3:1 against white and the card colour.
            .border(1.dp, if (selected) Palette.brand else Palette.muted, RoundedCornerShape(50))
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (selected) Color.White else Color.Black
        if (selected) Text("✓ ", color = ink, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics { })
        if (icon != null) Text("$icon ", modifier = Modifier.clearAndSetSemantics { })
        Text(label, color = ink)
    }
}

/** One choice in a single-choice group (TalkBack: radio button, selected / not selected). Put it in a [ChoiceGroup]. */
@Composable
fun RadioChip(label: String, selected: Boolean, icon: String? = null, a11y: String? = null, onClick: () -> Unit) {
    ChipBody(label, selected, icon,
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .then(if (a11y != null) Modifier.semantics { contentDescription = a11y } else Modifier))
}

/** One option in a multi-select group (TalkBack: checkbox, checked / not checked). */
@Composable
fun CheckChip(label: String, checked: Boolean, a11y: String? = null, onChange: (Boolean) -> Unit) {
    ChipBody(label, checked, null,
        Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .then(if (a11y != null) Modifier.semantics { contentDescription = a11y } else Modifier))
}

/**
 * A named group of chips. The heading right before the group names it; single-choice groups are
 * marked as a selectable group so TalkBack treats the items as one set of radio buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceGroup(label: String, single: Boolean = true, hint: String? = null, content: @Composable () -> Unit) {
    SectionHeader(label)
    if (hint != null) Muted(hint)
    FlowRow(
        Modifier.fillMaxWidth().then(if (single) Modifier.selectableGroup() else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** A switch with its label as one focus stop (the Row is the switch; the Switch itself is just the picture). */
@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
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

/** "Tuesday, November 24" for a yyyy-MM-dd date (as typed if it can't be read). */
fun prettyDate(ymd: String, withYear: Boolean = false): String = runCatching {
    LocalDate.parse(ymd).format(
        if (withYear) DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
        else DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
}.getOrDefault(ymd)

/** "7:30 AM" (or "07:30" on 24-hour phones) for an HH:MM time. */
fun prettyTime(hm: String?): String? {
    if (hm.isNullOrBlank()) return null
    return runCatching { LocalTime.parse(hm.padStart(5, '0')).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }
        .getOrDefault(hm)
}

private fun ymdToMillis(ymd: String): Long? =
    runCatching { LocalDate.parse(ymd).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()

/**
 * A date chooser: a button showing "[label]: Tuesday, November 24" that opens the Material date
 * picker (which also has a keyboard entry mode). Value is yyyy-MM-dd.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = if (value.isBlank()) "not set" else prettyDate(value, withYear = true)
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { onClick(label = "Change $label") { open = true; true } }) {
        Text("$label: $shown", modifier = Modifier.fillMaxWidth())
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = ymdToMillis(value))
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state, title = { Text(label, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }
}

/**
 * A time chooser: shows "[label]: 7:30 AM" (or [noneText] when blank), "Set time" opens the
 * Material time input, "Clear time" goes back to no specific time. Value is HH:MM or "".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimeField(label: String, value: String, noneText: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Text("$label: ${prettyTime(value) ?: noneText}", style = MaterialTheme.typography.bodyLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (value.isBlank()) "Set time" else "Change time")
        }
        if (value.isNotBlank()) TextButton(onClick = { onChange("") }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Clear time")
        }
    }
    if (open) {
        val t = runCatching { LocalTime.parse(value.padStart(5, '0')) }.getOrNull()
        val state = rememberTimePickerState(t?.hour ?: 8, t?.minute ?: 0, DateFormat.is24HourFormat(ctx))
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimeInput(state = state) },
            confirmButton = {
                TextButton(onClick = { onChange("%02d:%02d".format(state.hour, state.minute)); open = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

/** Shift a yyyy-MM-dd date by a number of days. */
fun shiftDate(ymd: String, days: Int): String =
    runCatching { java.time.LocalDate.parse(ymd).plusDays(days.toLong()).toString() }.getOrDefault(ymd)
