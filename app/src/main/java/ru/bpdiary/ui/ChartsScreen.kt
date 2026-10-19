package ru.bpdiary.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.analysis.BpNorms
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Medication
import ru.bpdiary.report.ChartRenderer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val RANGES = listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней", 365 to "Год")
private val TIP_DT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, EEE, HH:mm", RU)
private val TIP_D: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, EEE", RU)

/** Палец на графике: x — где коснулись, w — ширина графика. */
private data class Touch(val x: Float, val w: Float)

/** Нажатие и ведение пальцем по горизонтали; вертикальная прокрутка экрана при этом продолжает работать. */
private fun Modifier.scrub(onTouch: (Touch) -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onPress = { onTouch(Touch(it.x, size.width.toFloat())) })
    }
    .pointerInput(Unit) {
        detectHorizontalDragGestures(onDragStart = { onTouch(Touch(it.x, size.width.toFloat())) }) { change, _ ->
            change.consume()
            onTouch(Touch(change.position.x, size.width.toFloat()))
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartsScreen(vm: MainViewModel, modifier: Modifier) {
    val list by vm.measurements.collectAsState()
    val meds by vm.medications.collectAsState()
    val logs by vm.doseLogs.collectAsState()
    val target by vm.target.collectAsState()
    val dark = isSystemInDarkTheme()
    val analysis by vm.analysis.collectAsState()
    var range by rememberSaveable { mutableIntStateOf(1) }
    val days = RANGES[range].first
    val today = LocalDate.now()
    val from = today.minusDays(days - 1L)
    val palette = if (dark) ChartRenderer.DARK else ChartRenderer.LIGHT
    val density = LocalDensity.current.density
    val anomalyIds = analysis?.anomalyIds?.keys ?: emptySet()
    val stats = analysis?.let { Analyzer.stats(list, from, today, it.anomalies) }

    // касания графиков (сбрасываются при смене периода)
    var bpTouch by remember(range) { mutableStateOf<Touch?>(null) }
    var pulseTouch by remember(range) { mutableStateOf<Touch?>(null) }
    var doseTouch by remember(range) { mutableStateOf<Touch?>(null) }

    val bpPts = remember(list, range) { ChartRenderer.bpPoints(list, from, today) }
    val pulsePts = remember(list, range) { ChartRenderer.pulsePoints(list, from, today) }
    fun nearest(t: Touch?, pts: List<ChartRenderer.BpPoint>, left: Float): ChartRenderer.BpPoint? {
        if (t == null || pts.isEmpty()) return null
        val time = ChartRenderer.timeAt(t.x, t.w, from, today, density, left)
        return pts.minByOrNull { abs(it.t - time) }
    }
    val bpSel = nearest(bpTouch, bpPts, ChartRenderer.BP_LEFT)
    val pulseSel = nearest(pulseTouch, pulsePts, ChartRenderer.BP_LEFT)
    val doseDay = doseTouch?.let { t ->
        val time = ChartRenderer.timeAt(t.x, t.w, from, today, density, ChartRenderer.DOSE_LEFT)
        Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEachIndexed { i, (_, label) ->
                FilterChip(selected = i == range, onClick = { range = i }, label = { Text(label) })
            }
        }

        // ── давление ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Давление", style = MaterialTheme.typography.titleMedium)
                Hint()
                ChartBox(260, bpTouch, { bpTouch = it }, { bpTouch = null }, tip = bpSel?.let { sel -> @Composable { BpTip(sel, list, analysis) } }) {
                    ChartRenderer.drawBp(it, RectF(0f, 0f, size.width, size.height),
                        list, from, today, anomalyIds, emptyList(), palette, density,
                        target.sys, target.dia, bpSel?.t)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(Color(palette.sys), "верхнее")
                    Legend(Color(palette.dia), "нижнее")
                    Legend(Color(palette.target), "цель ${target.sys}/${target.dia}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(Color(palette.anomaly), "аномалия")
                }
                if (days > 90 && list.size > 150) Text("Для длинного периода показаны средние значения по дням",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ── пульс ──
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Пульс", style = MaterialTheme.typography.titleMedium)
                ChartBox(180, pulseTouch, { pulseTouch = it }, { pulseTouch = null }, tip = pulseSel?.takeIf { it.pulse != null }?.let { sel -> @Composable { PulseTip(sel) } }) {
                    ChartRenderer.drawPulse(it, RectF(0f, 0f, size.width, size.height),
                        list, from, today, palette, density, pulseSel?.t)
                }
            }
        }

        // ── время приёма лекарств ──
        val shownMeds = meds.filter { m ->
            m.startDay <= today.toEpochDay() && (m.endDay ?: Long.MAX_VALUE) >= from.toEpochDay()
        }.sortedBy { it.startDay }
        if (shownMeds.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val track = Analyzer.trackingStart(list, logs)
                Text("Время приёма лекарств", style = MaterialTheme.typography.titleMedium)
                ChartBox(240, doseTouch, { doseTouch = it }, { doseTouch = null }, tip = doseDay?.let { day -> @Composable { DoseTip(LocalDate.ofEpochDay(day), shownMeds, logs, track, dark) } }) {
                    ChartRenderer.drawDoses(it, RectF(0f, 0f, size.width, size.height),
                        shownMeds, logs, from, today, track, palette, dark, density, doseDay)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    shownMeds.forEachIndexed { i, m -> Dot(Color(ChartRenderer.medColor(i, dark)), m.name) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("×", color = Color(ChartRenderer.MISSED_COLOR), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(4.dp))
                        Text("пропущен приём", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        stats?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("За период", style = MaterialTheme.typography.titleMedium)
                    StatRow("Замеров", "${s.count} (дней с замерами: ${s.daysWithData})")
                    StatRow("Среднее", "${s.sysMean.roundToInt()}/${s.diaMean.roundToInt()}")
                    StatRow("Диапазон верхнего", "${s.sysMin}–${s.sysMax}")
                    StatRow("Диапазон нижнего", "${s.diaMin}–${s.diaMax}")
                    s.pulseMean?.let { StatRow("Средний пульс", it.roundToInt().toString()) }
                    StatRow("В цели (${BpNorms.targetText()})", "${(s.targetShare * 100).roundToInt()}%")
                    s.morning?.let { StatRow("Утром (4–12 ч)", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
                    s.evening?.let { StatRow("Вечером (17–24 ч)", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
                    StatRow("Аномалий", s.anomalies.toString())
                }
            }
        }
    }
}

private fun timeOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
private fun dateOf(ms: Long) = timeOf(ms).toLocalDate()

@Composable
private fun Hint() {
    Text("Нажмите на график и ведите пальцем — покажу значения",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * График с касанием: палец ставит/двигает выбранную точку, а плашка с увеличенными значениями
 * появляется поверх графика с противоположной от пальца стороны, чтобы не закрывать его.
 */
@Composable
private fun ChartBox(
    heightDp: Int,
    touch: Touch?,
    onTouch: (Touch) -> Unit,
    onClose: () -> Unit,
    tip: (@Composable () -> Unit)?,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(android.graphics.Canvas) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(heightDp.dp)) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp).scrub(onTouch)) {
            drawIntoCanvas { draw(it.nativeCanvas) }
        }
        if (tip != null && touch != null) {
            val onLeft = touch.x > touch.w / 2
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.95f),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(if (onLeft) Alignment.TopStart else Alignment.TopEnd)
                    .fillMaxWidth(0.66f)
                    .padding(4.dp),
            ) {
                Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                    Column(Modifier.weight(1f)) { tip() }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Скрыть", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BpTip(sel: ChartRenderer.BpPoint, list: List<ru.bpdiary.data.Measurement>, analysis: ru.bpdiary.analysis.AnalysisResult?) {
        Text(
            if (sel.n > 1 || sel.id < 0) "${dateOf(sel.t).format(TIP_D)} — среднее за день (${sel.n})"
            else timeOf(sel.t).format(TIP_DT),
            style = MaterialTheme.typography.labelLarge,
        )
        Text("${sel.sys.roundToInt()}/${sel.dia.roundToInt()}",
            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 1)
        sel.pulse?.let { Text("пульс ${it.roundToInt()}", style = MaterialTheme.typography.titleLarge) }
        val cat = BpNorms.classify(sel.sys.roundToInt(), sel.dia.roundToInt())
        CategoryLabel(cat.title, Color(cat.argb))
        val atTarget = BpNorms.atTarget(sel.sys.roundToInt(), sel.dia.roundToInt())
        Text(if (atTarget) "в цели (${BpNorms.targetText()})" else "выше цели (${BpNorms.targetText()})",
            style = MaterialTheme.typography.bodySmall,
            color = if (atTarget) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant)
        analysis?.anomalyIds?.get(sel.id)?.forEach {
            Text("⚠ ${it.type.title}", color = Color(0xFFE65100), style = MaterialTheme.typography.bodySmall)
        }
        list.firstOrNull { it.id == sel.id }?.note?.takeIf { it.isNotBlank() }?.let {
            Text("«$it»", style = MaterialTheme.typography.bodySmall)
        }
}

@Composable
private fun PulseTip(sel: ChartRenderer.BpPoint) {
        Text(
            if (sel.n > 1 || sel.id < 0) "${dateOf(sel.t).format(TIP_D)} — среднее за день"
            else timeOf(sel.t).format(TIP_DT),
            style = MaterialTheme.typography.labelLarge,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${sel.pulse!!.roundToInt()}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(" уд/мин", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text("давление ${sel.sys.roundToInt()}/${sel.dia.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DoseTip(day: LocalDate, meds: List<Medication>, logs: List<DoseLog>, trackingStart: Long, dark: Boolean) {
    val d = day.toEpochDay()
    Text(day.format(TIP_D), style = MaterialTheme.typography.labelLarge)
    meds.forEachIndexed { i, m ->
        if (!m.isActiveOn(d)) return@forEachIndexed
        val taken = m.logsOn(logs, d)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color(ChartRenderer.medColor(i, dark)), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(m.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Text(
            if (taken.isEmpty()) "не отмечено"
            else taken.joinToString(", ") { timeOf(it.takenAt).format(TIME_FMT) },
            style = MaterialTheme.typography.headlineSmall,
        )
        val missing = m.perDay - taken.size
        if (missing > 0 && d < LocalDate.now().toEpochDay() && d >= trackingStart)
            Text("пропущено $missing из ${m.perDay}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun Dot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
