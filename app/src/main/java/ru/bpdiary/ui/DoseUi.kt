package ru.bpdiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Medication
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Medication
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val DONE = Color(0xFF2E7D32)

private fun timeOf(log: DoseLog) = Instant.ofEpochMilli(log.takenAt).atZone(ZoneId.systemDefault())

private fun dosesWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "приём"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "приёма"
    else -> "приёмов"
}

/**
 * Приём одного препарата за один день:
 *  • отмеченные приёмы — «✓ 09:12» (нажатие: изменить время или удалить);
 *  • пока не все приёмы сделаны — счётчик «осталось 2 из 3» и кнопка «Отметить приём»;
 *  • когда все сделаны — кнопка исчезает, вместо неё «все приёмы выполнены».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoseRow(med: Medication, day: LocalDate, logs: List<DoseLog>, vm: MainViewModel) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val d = day.toEpochDay()
    val taken = med.logsOn(logs, d)
    val remaining = (med.perDay - taken.size).coerceAtLeast(0)
    var askAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DoseLog?>(null) }

    fun at(t: LocalTime) = day.atTime(t).atZone(zone).toInstant().toEpochMilli()
    fun addWithPicker() = pickTime(ctx, if (day == today) LocalTime.now() else LocalTime.of(9, 0)) { vm.addDose(med, d, at(it)) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Medication, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("${med.name} ${med.dose}".trim(), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
            Text("  · ${med.perDayText()}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (taken.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            taken.forEach { log ->
                val t = timeOf(log)
                FilterChip(
                    selected = true,
                    onClick = { editing = log },
                    label = { Text(if (t.toLocalDate() == day) "принял в ${t.format(TIME_FMT)}" else "принял ${t.format(DATE_FMT)} ${t.format(TIME_FMT)}") },
                    leadingIcon = { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) },
                )
            }
        }
        if (remaining == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = DONE)
                Text(if (med.perDay == 1) "  Приём выполнен" else "  Все приёмы выполнены",
                    color = DONE, style = MaterialTheme.typography.bodySmall)
            }
        } else if (day == today) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (taken.isEmpty()) "Нужно принять ${med.perDayText().removeSuffix(" в день")} сегодня"
                    else "Осталось сегодня: $remaining ${dosesWord(remaining)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                FilledTonalButton(onClick = { askAdd = true }) { Text("Принял") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Пропущено: $remaining из ${med.perDay}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { addWithPicker() }) { Text("Отметить приём") }
            }
        }
    }

    if (askAdd) AlertDialog(
        onDismissRequest = { askAdd = false },
        title = { Text(med.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Когда приняли?")
                FilledTonalButton(onClick = { askAdd = false; vm.addDose(med, d, System.currentTimeMillis()) },
                    modifier = Modifier.fillMaxWidth()) { Text("Сейчас, ${LocalTime.now().format(TIME_FMT)}") }
                OutlinedButton(onClick = { askAdd = false; addWithPicker() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Указать время…")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { askAdd = false }) { Text("Отмена") } },
    )

    editing?.let { log ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(med.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Отмечен приём в ${timeOf(log).format(TIME_FMT)}")
                    OutlinedButton(onClick = {
                        editing = null
                        pickTime(ctx, timeOf(log).toLocalTime()) { vm.editDose(log, at(it)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Изменить время…") }
                    OutlinedButton(onClick = { editing = null; vm.deleteDose(log) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Удалить отметку (не принимал)", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Закрыть") } },
        )
    }
}
