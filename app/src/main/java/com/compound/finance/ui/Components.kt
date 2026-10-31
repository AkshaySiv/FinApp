@file:OptIn(ExperimentalMaterial3Api::class)

package com.compound.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.compound.finance.core.Categories
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// ------------------------------------------------------------------ formatting
fun money(v: Double, ccy: String, decimals: Boolean? = null, sign: Boolean = false): String {
    val d = decimals ?: (abs(v) < 1000)
    val body = if (d) "%,.2f".format(Locale.US, abs(v)) else "%,.0f".format(Locale.US, abs(v))
    val s = when { v < 0 -> "-"; sign && v > 0 -> "+"; else -> "" }
    return "$s$ccy $body"
}
fun compact(v: Double): String = when {
    abs(v) >= 1_000_000 -> "%.1fM".format(Locale.US, v / 1_000_000)
    abs(v) >= 10_000 -> "%.0fK".format(Locale.US, v / 1000)
    abs(v) >= 1000 -> "%.1fK".format(Locale.US, v / 1000)
    else -> "%.0f".format(Locale.US, v)
}
private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
fun fmtDay(d: LocalDate): String = if (d.year == LocalDate.now().year) d.format(dayFmt) else d.format(dayYearFmt)
fun pct(v: Double): String = "%.0f%%".format(Locale.US, v * 100)

/** A stable colour per category, so Groceries is always the same colour everywhere. */
@Composable
fun categoryColor(category: String): Color {
    val p = LocalPalette.current
    val idx = Categories.spending.indexOf(category).let { if (it < 0) category.hashCode() else it }
    return p.series[Math.floorMod(idx, p.series.size)]
}

// ------------------------------------------------------------------ layout pieces
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Bar(fraction: Double, color: Color, modifier: Modifier = Modifier, height: Int = 8) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(4.dp)).background(p.track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier = modifier, color = LocalPalette.current.muted, style = MaterialTheme.typography.bodySmall,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(body, color = LocalPalette.current.muted, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

// ------------------------------------------------------------------ inputs
@Composable
fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) =
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }) }, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier.fillMaxWidth())

@Composable
fun InputField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = singleLine, modifier = modifier.fillMaxWidth())

fun String.toAmount(): Double? = replace(",", "").trim().toDoubleOrNull()

/** Tap-to-choose field: shows the current value, opens a list dialog. */
@Composable
fun ChoiceField(label: String, value: String, options: List<String>, onChoose: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(value, {}, label = { Text(label) }, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
            title = { Text(label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(options) { o ->
                        Row(Modifier.fillMaxWidth().clickable { onChoose(o); open = false }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = o == value, onClick = { onChoose(o); open = false })
                            Text(o, fontWeight = if (o == value) FontWeight.Medium else FontWeight.Normal)
                        }
                    }
                }
            },
        )
    }
}

@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(fmtDay(date), {}, label = { Text(label) }, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(LocalDate.ofEpochDay(it / 86_400_000L)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Muted(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) }, text = { Text(body) },
    )
