package ru.bpdiary.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.analysis.BpCategory
import ru.bpdiary.analysis.Comparison
import ru.bpdiary.analysis.StabilityState
import ru.bpdiary.analysis.Verdict
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val GOOD = Color(0xFF2E7D32)
private val BAD = Color(0xFFC62828)
private val WARN = Color(0xFFEF6C00)

@Composable
fun AnalysisScreen(vm: MainViewModel, modifier: Modifier) {
    val a by vm.analysis.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cmpMode by rememberSaveable { mutableIntStateOf(0) }
    var reportRange by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val r = a ?: return

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── стабилизация ──
        Section("Стабилизация давления (2 недели)") {
            val st = r.stability
            val color = when (st.state) {
                StabilityState.STABLE_AT_TARGET, StabilityState.IMPROVING -> GOOD
                StabilityState.WORSENING, StabilityState.UNSTABLE -> BAD
                StabilityState.STABLE_ABOVE_TARGET -> WARN
                StabilityState.INSUFFICIENT -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(st.state.title, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (st.text.isNotEmpty()) Text(st.text)
            else Text("Для оценки нужно хотя бы 7 дней с замерами за последние 2 недели.")
        }

        // ── сравнение ──
        Section("Лучше или хуже?") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(cmpMode == 0, { cmpMode = 0 }, { Text("Месяц к месяцу") })
                FilterChip(cmpMode == 1, { cmpMode = 1 }, { Text("30 дней к 30") })
            }
            ComparisonView(if (cmpMode == 0) r.monthCmp else r.rollingCmp)
        }

        // ── тренд ──
        r.trend30?.let { t ->
            Section("Тенденция за 30 дней") {
                fun word(v: Double) = when {
                    v <= -1 -> "снижается на ${abs(v).roundToInt().coerceAtLeast(1)} мм в неделю"
                    v >= 1 -> "растёт на ${v.roundToInt().coerceAtLeast(1)} мм в неделю"
                    else -> "почти не меняется"
                }
                Text("Верхнее: ${word(t.sysPerWeek)}")
                Text("Нижнее: ${word(t.diaPerWeek)}")
            }
        }

        // ── утро/вечер, категории ──
        r.last30?.let { s ->
            Section("Последние 30 дней") {
                StatRow("Среднее", "${s.sysMean.roundToInt()}/${s.diaMean.roundToInt()}")
                StatRow("В цели (<135/85)", "${(s.targetShare * 100).roundToInt()}% замеров")
                s.morning?.let { StatRow("Утром", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
                s.evening?.let { StatRow("Вечером", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
                val m = s.morning; val e = s.evening
                if (m != null && e != null && m.first - e.first >= 10)
                    Text("Утром давление заметно выше вечернего — это стоит показать врачу: " +
                        "иногда меняют время приёма препаратов.", color = WARN, style = MaterialTheme.typography.bodySmall)
                CategoryBar(s.categories, s.count)
            }
        }

        // ── лекарства ──
        if (r.medEffects.isNotEmpty()) Section("Эффект лекарств") {
            r.medEffects.forEach { e ->
                val period = "с ${LocalDate.ofEpochDay(e.med.startDay).format(DATE_FMT)}" +
                    (e.med.endDay?.let { " по ${LocalDate.ofEpochDay(it).format(DATE_FMT)}" } ?: "")
                Text("${e.med.name} ${e.med.dose}", fontWeight = FontWeight.SemiBold)
                Text(period, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.text, modifier = Modifier.padding(bottom = 8.dp))
            }
        }

        // ── аномалии ──
        Section("Аномалии за 30 дней") {
            val since = r.today.minusDays(29)
            val list = r.anomalies.filter { !Analyzer.dateOf(it.m).isBefore(since) }
            if (list.isEmpty()) Text("Не найдено. Отлично!", color = GOOD)
            val fmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", RU)
            list.take(30).forEach { an ->
                val c = when (an.type.severity) { 3 -> BAD; 2 -> WARN; else -> MaterialTheme.colorScheme.onSurface }
                Text("${Analyzer.dateTimeOf(an.m).format(fmt)} — ${an.m.systolic}/${an.m.diastolic}", fontWeight = FontWeight.Medium)
                Text("${an.type.title} (${an.detail})", color = c, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            if (list.size > 30) Text("…и ещё ${list.size - 30}")
        }

        // ── PDF ──
        Section("Отчёт для врача (PDF)") {
            val options = listOf("30 дней" to 30L, "Этот месяц" to -1L, "90 дней" to 90L)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, (label, _) ->
                    FilterChip(reportRange == i, { reportRange = i }, { Text(label) })
                }
            }
            Button(enabled = !busy, onClick = {
                val today = LocalDate.now()
                val days = options[reportRange].second
                val from = if (days < 0) today.withDayOfMonth(1) else today.minusDays(days - 1)
                busy = true
                scope.launch {
                    try {
                        val uri = vm.buildReport(from, today)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Не удалось создать отчёт: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally { busy = false }
                }
            }) {
                Icon(Icons.Filled.PictureAsPdf, null)
                Text(if (busy) "  Формирую…" else "  Создать и отправить")
            }
        }

        Text(
            "Анализ носит информационный характер и не заменяет консультацию врача. " +
                "Не меняйте дозировку и схему приёма самостоятельно. При давлении выше 180/110 с головной болью, " +
                "болью в груди, нарушением зрения или речи — вызывайте скорую (103 / 112).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ComparisonView(c: Comparison) {
    val color = when (c.verdict) {
        Verdict.BETTER -> GOOD; Verdict.WORSE -> BAD
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(c.verdict.title, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
    Text(c.summary)
    if (c.items.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("", Modifier.weight(2.2f))
            Text(c.prevLabel.take(12), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(c.curLabel.take(12), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("Δ", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        }
        c.items.forEach { item ->
            val ic = when (item.better) { true -> GOOD; false -> BAD; null -> MaterialTheme.colorScheme.onSurface }
            Row(Modifier.fillMaxWidth()) {
                Text(item.label, Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall)
                Text(item.prev, Modifier.weight(1f))
                Text(item.cur, Modifier.weight(1f))
                Text(item.diffText, Modifier.weight(1f), color = ic, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun CategoryBar(cats: Map<BpCategory, Int>, total: Int) {
    if (total == 0) return
    Text("Распределение замеров", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
        BpCategory.entries.forEach { c ->
            val n = cats[c] ?: 0
            if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(Color(c.argb)))
        }
    }
    BpCategory.entries.filter { (cats[it] ?: 0) > 0 }.forEach { c ->
        val n = cats[c]!!
        CategoryLabel("${c.title}: ${(n * 100.0 / total).roundToInt()}%", Color(c.argb))
    }
}
