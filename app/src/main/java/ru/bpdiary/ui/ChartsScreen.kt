package ru.bpdiary.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.report.ChartRenderer
import java.time.LocalDate
import kotlin.math.roundToInt

private val RANGES = listOf(7 to "7 дней", 30 to "30 дней", 90 to "90 дней", 365 to "Год")

@Composable
fun ChartsScreen(vm: MainViewModel, modifier: Modifier) {
    val list by vm.measurements.collectAsState()
    val meds by vm.medications.collectAsState()
    val analysis by vm.analysis.collectAsState()
    var range by rememberSaveable { mutableIntStateOf(1) }
    val days = RANGES[range].first
    val today = LocalDate.now()
    val from = today.minusDays(days - 1L)
    val palette = if (isSystemInDarkTheme()) ChartRenderer.DARK else ChartRenderer.LIGHT
    val density = LocalDensity.current.density
    val anomalyIds = analysis?.anomalyIds?.keys ?: emptySet()
    val medStarts = meds.map { LocalDate.ofEpochDay(it.startDay) to it.name }
    val stats = analysis?.let { Analyzer.stats(list, from, today, it.anomalies) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RANGES.forEachIndexed { i, (_, label) ->
                FilterChip(selected = i == range, onClick = { range = i }, label = { Text(label) })
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Давление", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(260.dp)) {
                    drawIntoCanvas {
                        ChartRenderer.drawBp(it.nativeCanvas, RectF(0f, 0f, size.width, size.height),
                            list, from, today, anomalyIds, medStarts, palette, density)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(Color(palette.sys), "верхнее")
                    Legend(Color(palette.dia), "нижнее")
                    Legend(Color(palette.target), "цель 135/85")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Legend(Color(palette.anomaly), "аномалия")
                    Legend(Color(palette.med), "начало приёма")
                }
                if (days > 90 && list.size > 150) Text("Для длинного периода показаны средние значения по дням",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Пульс", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                    drawIntoCanvas {
                        ChartRenderer.drawPulse(it.nativeCanvas, RectF(0f, 0f, size.width, size.height),
                            list, from, today, palette, density)
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
                    StatRow("В цели (<135/85)", "${(s.targetShare * 100).roundToInt()}%")
                    s.morning?.let { StatRow("Утром (4–12 ч)", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
                    s.evening?.let { StatRow("Вечером (17–24 ч)", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
                    StatRow("Аномалий", s.anomalies.toString())
                }
            }
        }
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
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
