package ru.bpdiary.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import ru.bpdiary.analysis.BpNorms
import ru.bpdiary.ble.TonometerLink

private val WARN = Color(0xFFE65100)

/** Окно поверх приложения: давление в манжете в реальном времени, затем результат замера. */
@Composable
fun LiveMeasureDialog(live: TonometerLink.Live) {
    val done = live.result != null || live.aborted
    // результат сам закрывается через минуту
    if (live.result != null) LaunchedEffect(live.result) { delay(60_000); TonometerLink.dismissLive() }

    Dialog(
        onDismissRequest = { if (done) TonometerLink.dismissLive() },
        properties = DialogProperties(dismissOnClickOutside = done, dismissOnBackPress = true),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    live.result != null -> Result(live)
                    live.aborted -> Aborted(live)
                    else -> Measuring(live)
                }
            }
        }
    }
}

@Composable
private fun Measuring(live: TonometerLink.Live) {
    val beat = rememberInfiniteTransition(label = "beat")
    val s by beat.animateFloat(1f, 1.18f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "s")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Favorite, null, tint = Color(0xFFD32F2F), modifier = Modifier.size(22.dp).scale(s))
        Text("  Идёт замер", style = MaterialTheme.typography.titleLarge)
    }
    Text("${live.cuff ?: 0}", fontSize = 88.sp, fontWeight = FontWeight.Bold, lineHeight = 92.sp)
    Text("мм рт. ст. в манжете", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Sparkline(live.history)
    Text("Сидите спокойно, рука на уровне сердца, не разговаривайте",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Result(live: TonometerLink.Live) {
    val r = live.result!!
    val cat = BpNorms.classify(r.systolic, r.diastolic)
    val atTarget = BpNorms.atTarget(r.systolic, r.diastolic)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32))
        Text("  Замер готов", style = MaterialTheme.typography.titleLarge)
    }
    Text("${r.systolic}/${r.diastolic}", fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 68.sp)
    r.pulse?.let { Text("пульс $it", style = MaterialTheme.typography.headlineSmall) }
    CategoryLabel(BpNorms.categoryText(r.systolic, r.diastolic), Color(cat.argb))
    Text(if (atTarget) "в цели (${BpNorms.targetText()})" else "выше цели (${BpNorms.targetText()})",
        style = MaterialTheme.typography.bodyMedium,
        color = if (atTarget) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant)
    val warnings = listOfNotNull("нерегулярный пульс (аритмия)".takeIf { r.irregular }) + r.warnings
    warnings.forEach { w ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = WARN, modifier = Modifier.size(18.dp))
            Text("  ${w.replaceFirstChar { it.uppercase() }}", color = WARN)
        }
    }
    if (r.userId != null) Text("Пользователь тонометра № ${r.userId}", style = MaterialTheme.typography.bodySmall)
    Text("Сохранено в дневник", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { TonometerLink.dismissLive() }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
}

@Composable
private fun Aborted(live: TonometerLink.Live) {
    Text("Замер прерван", style = MaterialTheme.typography.titleLarge)
    Text("Тонометр перестал присылать данные. Если на его экране ошибка — проверьте манжету и повторите замер.",
        style = MaterialTheme.typography.bodyMedium)
    Button(onClick = { TonometerLink.dismissLive() }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
}

/** Кривая давления в манжете за время замера. */
@Composable
private fun Sparkline(values: List<Int>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        if (values.size < 2) return@Canvas
        val maxV = maxOf(values.max(), 160).toFloat()
        val n = maxOf(values.size, 60)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * size.width / (n - 1), size.height - v / maxV * size.height)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx()))
    }
}
