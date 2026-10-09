package ru.bpdiary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.bpdiary.data.Medication
import java.time.LocalDate

@Composable
fun MedicationsScreen(vm: MainViewModel, modifier: Modifier) {
    val meds by vm.medications.collectAsState()
    val logs by vm.doseLogs.collectAsState()
    var editing by remember { mutableStateOf<Medication?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now().toEpochDay()

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Вносите каждый препарат с датой начала. Если врач заменил лекарство или дозу — " +
                        "укажите дату отмены у старого и добавьте новое. Так приложение сможет сравнить " +
                        "давление до и после каждого изменения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (meds.isEmpty()) item {
                Text("Список пуст", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            }
            items(meds, key = { it.id }) { m ->
                val active = m.isActiveOn(today)
                val takenToday = logs.count { it.medicationId == m.id && it.day == today }
                Card(Modifier.fillMaxWidth().clickable { editing = m }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${m.name} ${m.dose}", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Приём: ${m.slots().joinToString(", ")}")
                        Text(
                            "С ${LocalDate.ofEpochDay(m.startDay).format(DATE_FMT)}" +
                                (m.endDay?.let { " по ${LocalDate.ofEpochDay(it).format(DATE_FMT)} (отменён)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (active) Text("Сегодня отмечено: $takenToday из ${m.slots().size}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, "Добавить препарат")
        }
    }

    if (adding) MedicationDialog(null, { adding = false }, { vm.saveMedication(it); adding = false })
    editing?.let { e ->
        MedicationDialog(e, { editing = null }, { vm.saveMedication(it); editing = null },
            onDelete = { vm.deleteMedication(e); editing = null })
    }
}

@Composable
private fun MedicationDialog(
    initial: Medication?,
    onDismiss: () -> Unit,
    onSave: (Medication) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var dose by remember { mutableStateOf(initial?.dose ?: "") }
    var times by remember { mutableStateOf(initial?.times ?: "08:00") }
    var start by remember { mutableStateOf(initial?.let { LocalDate.ofEpochDay(it.startDay) } ?: LocalDate.now()) }
    var stopped by remember { mutableStateOf(initial?.endDay != null) }
    var end by remember { mutableStateOf(initial?.endDay?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    val timesOk = times.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        .all { Regex("""^([01]?\d|2[0-3]):[0-5]\d$""").matches(it) }
    val valid = name.isNotBlank() && timesOk && (!stopped || !end.isBefore(start))

    EditorDialog(
        title = if (initial == null) "Новый препарат" else "Препарат",
        saveEnabled = valid,
        onDismiss = onDismiss,
        onSave = {
            onSave(Medication(
                id = initial?.id ?: 0, name = name.trim(), dose = dose.trim(),
                times = times.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { t -> t.padStart(5, '0') }.joinToString(", "),
                startDay = start.toEpochDay(), endDay = if (stopped) end.toEpochDay() else null, note = note.trim(),
            ))
        },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dose, { dose = it }, label = { Text("Дозировка, например 50 мг") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(times, { times = it }, label = { Text("Время приёма через запятую") },
            supportingText = { Text(if (timesOk) "Например: 08:00, 20:00" else "Формат ЧЧ:ММ через запятую") },
            isError = !timesOk, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Начало: ", Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = { pickDate(ctx, start) { start = it } }) { Text(start.format(DATE_FMT)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(stopped, { stopped = it })
            Text("Отменён / заменён")
        }
        if (stopped) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Последний день: ", Modifier.padding(end = 8.dp))
            OutlinedButton(onClick = { pickDate(ctx, end) { end = it } }) { Text(end.format(DATE_FMT)) }
        }
        OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth())
        if (onDelete != null) TextButton(onClick = { confirmDelete = true }) {
            Text("Удалить препарат и его отметки", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить препарат?") },
        text = { Text("Если препарат просто отменили — лучше отметьте «Отменён», чтобы сохранить историю для анализа.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}
