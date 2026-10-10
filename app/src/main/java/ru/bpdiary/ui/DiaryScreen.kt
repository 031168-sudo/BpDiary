package ru.bpdiary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.analysis.BpNorms
import ru.bpdiary.analysis.StabilityState
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Measurement
import ru.bpdiary.data.Medication
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.roundToInt

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DiaryScreen(vm: MainViewModel, modifier: Modifier) {
    val list by vm.measurements.collectAsState()
    val meds by vm.medications.collectAsState()
    val logs by vm.doseLogs.collectAsState()
    val analysis by vm.analysis.collectAsState()
    var editing by remember { mutableStateOf<Measurement?>(null) }
    var adding by remember { mutableStateOf(false) }

    val today = LocalDate.now()
    val activeMeds = meds.filter { it.isActiveOn(today.toEpochDay()) }
    val grouped = list.groupBy { Analyzer.dateOf(it) }
    // Дни, где показываем приём лекарств: с первого дня пользования приложением (не раньше 60 дней назад)
    val trackingStart = (list.map { Analyzer.dateOf(it).toEpochDay() } + logs.map { it.day }).minOrNull() ?: today.toEpochDay()
    val doseDays = (maxOf(trackingStart, today.minusDays(60).toEpochDay()) until today.toEpochDay())
        .filter { d -> meds.any { it.isActiveOn(d) } }
        .map { LocalDate.ofEpochDay(it) }
    val allDays = (grouped.keys + doseDays + today).toSortedSet(compareByDescending { it })

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // приём лекарств сегодня
            if (activeMeds.isNotEmpty()) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Лекарства сегодня", style = MaterialTheme.typography.titleMedium)
                        activeMeds.forEach { med ->
                            Spacer(Modifier.size(8.dp))
                            Text("${med.name} ${med.dose}", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                med.slots().forEach { slot ->
                                    val taken = logs.any { it.medicationId == med.id && it.day == today.toEpochDay() && it.slot == slot }
                                    val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                                    FilterChip(
                                        selected = taken,
                                        onClick = { vm.setDoseTaken(med, today.toEpochDay(), slot, !taken) },
                                        label = { Text(if (taken) "$slot — принял" else slot) },
                                        leadingIcon = if (taken) check else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // сводка
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        val last = list.firstOrNull()
                        if (last == null) {
                            Text("Пока нет замеров", style = MaterialTheme.typography.titleMedium)
                            Text("Нажмите «+», чтобы добавить первый. Лучше мерить утром и вечером, " +
                                "после 5 минут покоя, сидя, на одной и той же руке.",
                                style = MaterialTheme.typography.bodyMedium)
                        } else {
                            val cat = BpNorms.classify(last.systolic, last.diastolic)
                            Text("Последний замер · ${Analyzer.dateTimeOf(last).format(DateTimeFormatter.ofPattern("d MMM, HH:mm", RU))}",
                                style = MaterialTheme.typography.labelMedium)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${last.systolic}/${last.diastolic}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                                last.pulse?.let { Text("  пульс $it", style = MaterialTheme.typography.titleMedium) }
                            }
                            CategoryLabel(cat.title, Color(cat.argb))
                            analysis?.last7?.let {
                                Spacer(Modifier.size(8.dp))
                                Text("Среднее за 7 дней: ${it.sysMean.roundToInt()}/${it.diaMean.roundToInt()}" +
                                    (it.pulseMean?.let { p -> ", пульс ${p.roundToInt()}" } ?: ""))
                            }
                            analysis?.stability?.takeIf { it.state != StabilityState.INSUFFICIENT }?.let {
                                Text("Динамика: ${it.state.title.lowercase()}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            // список по дням
            allDays.forEach { day ->
                val dayItems = grouped[day].orEmpty()
                val dayMeds = if (day == today) emptyList() else meds.filter { it.isActiveOn(day.toEpochDay()) }
                if (dayItems.isEmpty() && dayMeds.isEmpty()) return@forEach
                item(key = "h$day") {
                    Text(dayTitle(day, today), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                if (dayMeds.isNotEmpty()) item(key = "d$day") {
                    DoseDayBlock(day, dayMeds, logs) { med, slot, taken -> vm.setDoseTaken(med, day.toEpochDay(), slot, taken) }
                }
                items(dayItems, key = { it.id }) { m ->
                    MeasurementRow(m, analysis?.anomalyIds?.containsKey(m.id) == true) { editing = m }
                }
            }
        }
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Добавить замер") }
    }

    if (adding) MeasurementDialog(null, onDismiss = { adding = false }, onSave = { vm.saveMeasurement(it); adding = false })
    editing?.let { e ->
        MeasurementDialog(
            e, onDismiss = { editing = null },
            onSave = { vm.saveMeasurement(it); editing = null },
            onDelete = { vm.deleteMeasurement(e); editing = null },
            anomalies = analysis?.anomalyIds?.get(e.id)?.map { "${it.type.title}: ${it.detail}" } ?: emptyList(),
        )
    }
}

/** Приём лекарств за прошедший день: нажатие ставит или снимает отметку. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DoseDayBlock(
    day: LocalDate, meds: List<Medication>, logs: List<DoseLog>,
    onToggle: (Medication, String, Boolean) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            meds.forEach { med ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Medication, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("${med.name} ${med.dose}".trim(), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    med.slots().forEach { slot ->
                        val log = logs.firstOrNull { it.medicationId == med.id && it.day == day.toEpochDay() && it.slot == slot }
                        val label = if (log != null) {
                            val at = java.time.Instant.ofEpochMilli(log.takenAt).atZone(zone)
                            if (at.toLocalDate() == day) "$slot · принято в ${at.format(TIME_FMT)}"
                            else "$slot · принято (отмечено позже)"
                        } else "$slot · пропущено"
                        val check: @Composable () -> Unit = { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }
                        FilterChip(
                            selected = log != null,
                            onClick = { onToggle(med, slot, log == null) },
                            label = {
                                Text(label, color = if (log == null) MaterialTheme.colorScheme.error else Color.Unspecified)
                            },
                            leadingIcon = if (log != null) check else null,
                        )
                    }
                }
            }
        }
    }
}

private fun dayTitle(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMMM" else "d MMMM yyyy", RU)) +
        ", " + d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU)
}

@Composable
fun CategoryLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun MeasurementRow(m: Measurement, anomaly: Boolean, onClick: () -> Unit) {
    val cat = BpNorms.classify(m.systolic, m.diastolic)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Analyzer.dateTimeOf(m).format(TIME_FMT), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(52.dp))
            Box(Modifier.size(10.dp).background(Color(cat.argb), CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("${m.systolic}/${m.diastolic}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                m.pulse?.let { Text("пульс $it", style = MaterialTheme.typography.bodyMedium) }
                val extra = listOfNotNull(m.arm?.let { "рука $it" }, "аритмия".takeIf { m.irregular }, m.note.takeIf { it.isNotBlank() })
                if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (anomaly) Icon(Icons.Filled.Warning, "Аномалия", tint = Color(0xFFFF6F00))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MeasurementDialog(
    initial: Measurement?,
    onDismiss: () -> Unit,
    onSave: (Measurement) -> Unit,
    onDelete: (() -> Unit)? = null,
    anomalies: List<String> = emptyList(),
) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    var sys by remember { mutableStateOf(initial?.systolic?.toString() ?: "") }
    var dia by remember { mutableStateOf(initial?.diastolic?.toString() ?: "") }
    var pulse by remember { mutableStateOf(initial?.pulse?.toString() ?: "") }
    var dt by remember { mutableStateOf(initial?.let { Analyzer.dateTimeOf(it) } ?: LocalDateTime.now().withSecond(0).withNano(0)) }
    var arm by remember { mutableStateOf(initial?.arm) }
    var irregular by remember { mutableStateOf(initial?.irregular ?: false) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    val s = sys.toIntOrNull(); val d = dia.toIntOrNull(); val p = pulse.toIntOrNull()
    val error = when {
        s == null || d == null -> null
        s !in 60..300 -> "Верхнее давление вне диапазона 60–300"
        d !in 30..200 -> "Нижнее давление вне диапазона 30–200"
        d >= s -> "Нижнее должно быть меньше верхнего"
        pulse.isNotBlank() && (p == null || p !in 25..250) -> "Пульс вне диапазона 25–250"
        else -> null
    }
    val valid = s != null && d != null && error == null

    EditorDialog(
        title = if (initial == null) "Новый замер" else "Замер",
        saveEnabled = valid,
        onDismiss = onDismiss,
        onSave = {
            onSave(Measurement(
                id = initial?.id ?: 0,
                timestamp = dt.atZone(zone).toInstant().toEpochMilli(),
                systolic = s!!, diastolic = d!!, pulse = p,
                arm = arm, irregular = irregular, note = note.trim(),
            ))
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("Верхнее", sys, Modifier.weight(1f)) { sys = it }
            NumField("Нижнее", dia, Modifier.weight(1f)) { dia = it }
            NumField("Пульс", pulse, Modifier.weight(1f)) { pulse = it }
        }
        if (s != null && d != null && error == null) {
            val cat = BpNorms.classify(s, d)
            CategoryLabel(cat.title, Color(cat.argb))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate(ctx, dt.toLocalDate()) { dt = it.atTime(dt.toLocalTime()) } }) {
                Text(dt.format(DateTimeFormatter.ofPattern("d MMM yyyy", RU)))
            }
            OutlinedButton(onClick = { pickTime(ctx, dt.toLocalTime()) { dt = dt.toLocalDate().atTime(it) } }) {
                Text(dt.format(TIME_FMT))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Рука:")
            listOf("Л", "П").forEach { a ->
                FilterChip(selected = arm == a, onClick = { arm = if (arm == a) null else a }, label = { Text(a) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = irregular, onCheckedChange = { irregular = it })
            Text("Тонометр показал аритмию")
        }
        OutlinedTextField(note, { note = it }, label = { Text("Заметка (самочувствие, нагрузка…)") },
            modifier = Modifier.fillMaxWidth())
        if (anomalies.isNotEmpty()) {
            anomalies.forEach { Text("⚠ $it", color = Color(0xFFE65100), style = MaterialTheme.typography.bodySmall) }
        }
        if (onDelete != null) {
            TextButton(onClick = { confirmDelete = true }) {
                Text("Удалить замер", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Удалить замер?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
    )
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    // Подпись над полем в одну строку, цифры крупно по центру — без переносов при любом размере шрифта
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
