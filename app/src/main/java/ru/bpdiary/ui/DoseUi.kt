package ru.bpdiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Medication
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun slotTime(slot: String): LocalTime? =
    runCatching { LocalTime.parse(slot.padStart(5, '0'), DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()

/**
 * Кнопка одного приёма. Нажатие открывает выбор: когда принял (сейчас / по графику / другое время),
 * а для уже отмеченного — изменить время или снять отметку.
 */
@Composable
fun DoseChip(
    med: Medication,
    day: LocalDate,
    slot: String,
    log: DoseLog?,
    onSet: (takenAt: Long) -> Unit,
    onClear: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val ctx = LocalContext.current
    val today = LocalDate.now()
    var dialog by remember { mutableStateOf(false) }

    val takenAt = log?.let { Instant.ofEpochMilli(it.takenAt).atZone(zone) }
    val planned = slotTime(slot)
    val overdue = day.isBefore(today) || (day == today && planned != null && LocalTime.now().isAfter(planned))
    val label = when {
        takenAt == null && day.isBefore(today) -> "$slot · пропущено"
        takenAt == null && overdue -> "$slot · не отмечено"
        takenAt == null -> slot
        takenAt.toLocalDate() == day -> "$slot · принял в ${takenAt.format(TIME_FMT)}"
        else -> "$slot · принял ${takenAt.format(DateTimeFormatter.ofPattern("d MMM HH:mm", RU))}"
    }
    val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
    FilterChip(
        selected = log != null,
        onClick = { dialog = true },
        label = {
            Text(label, color = when {
                log != null -> Color.Unspecified
                day.isBefore(today) -> MaterialTheme.colorScheme.error
                overdue -> Color(0xFFEF6C00)
                else -> Color.Unspecified
            })
        },
        leadingIcon = if (log != null) check else null,
    )

    if (dialog) {
        fun at(t: LocalTime) = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
        fun pickOther() {
            val initial = takenAt?.toLocalTime() ?: planned ?: LocalTime.now()
            pickTime(ctx, initial) { t -> onSet(at(t)) }
        }
        AlertDialog(
            onDismissRequest = { dialog = false },
            title = { Text("${med.name} · $slot") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (log == null) "Когда приняли?" else "Отмечено: ${takenAt!!.format(TIME_FMT)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (log == null && day == today) FilledTonalButton(
                        onClick = { dialog = false; onSet(System.currentTimeMillis()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Сейчас, ${LocalTime.now().format(TIME_FMT)}") }
                    if (log == null && planned != null && !(day == today && planned.isAfter(LocalTime.now()))) {
                        val btn: @Composable () -> Unit = { Text("По графику, ${planned.format(TIME_FMT)}") }
                        if (day == today) OutlinedButton(onClick = { dialog = false; onSet(at(planned)) },
                            modifier = Modifier.fillMaxWidth()) { btn() }
                        else FilledTonalButton(onClick = { dialog = false; onSet(at(planned)) },
                            modifier = Modifier.fillMaxWidth()) { btn() }
                    }
                    OutlinedButton(onClick = { dialog = false; pickOther() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (log == null) "Указать время…" else "Изменить время…")
                    }
                    if (log != null) OutlinedButton(onClick = { dialog = false; onClear() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Снять отметку (не принимал)", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialog = false }) { Text("Отмена") } },
        )
    }
}
