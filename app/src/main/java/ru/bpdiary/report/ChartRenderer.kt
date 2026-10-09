package ru.bpdiary.report

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.data.Measurement
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Рисует графики на обычном android.graphics.Canvas — один и тот же код
 * используется и на экране (через Compose nativeCanvas), и в PDF-отчёте.
 */
object ChartRenderer {

    data class Palette(
        val grid: Int,
        val text: Int,
        val sys: Int = 0xFFD32F2F.toInt(),
        val dia: Int = 0xFF1976D2.toInt(),
        val pulse: Int = 0xFF7B1FA2.toInt(),
        val target: Int = 0xFF2E7D32.toInt(),
        val anomaly: Int = 0xFFFF6F00.toInt(),
        val med: Int = 0xFF00897B.toInt(),
    )

    val LIGHT = Palette(grid = 0x22000000, text = 0xFF555555.toInt())
    val DARK = Palette(grid = 0x33FFFFFF, text = 0xFFBBBBBB.toInt(),
        sys = 0xFFEF5350.toInt(), dia = 0xFF64B5F6.toInt(), pulse = 0xFFBA68C8.toInt(),
        target = 0xFF81C784.toInt(), med = 0xFF4DB6AC.toInt())

    private data class Pt(val t: Long, val v: Double, val id: Long)
    private class Series(val pts: List<Pt>, val color: Int)
    private class RefLine(val v: Double, val color: Int, val label: String)

    fun drawBp(
        c: Canvas, area: RectF, all: List<Measurement>, from: LocalDate, to: LocalDate,
        anomalyIds: Set<Long>, medStarts: List<Pair<LocalDate, String>>, p: Palette, density: Float,
    ) {
        val list = Analyzer.inRange(all, from, to)
        val (sys, dia) = if (list.size > 150) {
            val daily = Analyzer.dailyMeans(list)
            daily.map { Pt(noon(it.day), it.sys, -1) } to daily.map { Pt(noon(it.day), it.dia, -1) }
        } else {
            list.map { Pt(it.timestamp, it.systolic.toDouble(), it.id) } to
                list.map { Pt(it.timestamp, it.diastolic.toDouble(), it.id) }
        }
        val lo = min(dia.minOfOrNull { it.v } ?: 70.0, 70.0) - 5
        val hi = max(sys.maxOfOrNull { it.v } ?: 150.0, 150.0) + 5
        drawChart(
            c, area, from, to, floor(lo / 10) * 10, ceil(hi / 10) * 10,
            listOf(Series(sys.sortedBy { it.t }, p.sys), Series(dia.sortedBy { it.t }, p.dia)),
            listOf(RefLine(135.0, p.target, "135"), RefLine(85.0, p.target, "85")),
            anomalyIds, medStarts, p, density,
        )
    }

    fun drawPulse(
        c: Canvas, area: RectF, all: List<Measurement>, from: LocalDate, to: LocalDate,
        p: Palette, density: Float,
    ) {
        val list = Analyzer.inRange(all, from, to).filter { it.pulse != null }
        val pts = if (list.size > 150) Analyzer.dailyMeans(list).mapNotNull { d -> d.pulse?.let { Pt(noon(d.day), it, -1) } }
        else list.map { Pt(it.timestamp, it.pulse!!.toDouble(), it.id) }
        val lo = min(pts.minOfOrNull { it.v } ?: 50.0, 50.0) - 5
        val hi = max(pts.maxOfOrNull { it.v } ?: 100.0, 100.0) + 5
        drawChart(
            c, area, from, to, floor(lo / 10) * 10, ceil(hi / 10) * 10,
            listOf(Series(pts.sortedBy { it.t }, p.pulse)),
            listOf(RefLine(100.0, p.anomaly, "100"), RefLine(50.0, p.anomaly, "50")),
            emptySet(), emptyList(), p, density,
        )
    }

    private fun noon(d: LocalDate) = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun drawChart(
        c: Canvas, area: RectF, from: LocalDate, to: LocalDate, yMin: Double, yMax: Double,
        series: List<Series>, refs: List<RefLine>, anomalyIds: Set<Long>,
        medStarts: List<Pair<LocalDate, String>>, p: Palette, d: Float,
    ) {
        val zone = ZoneId.systemDefault()
        val t0 = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val t1 = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val plot = RectF(area.left + 30 * d, area.top + 14 * d, area.right - 6 * d, area.bottom - 18 * d)
        fun x(t: Long) = plot.left + (t - t0).toFloat() / (t1 - t0) * plot.width()
        fun y(v: Double) = plot.bottom - ((v - yMin) / (yMax - yMin)).toFloat() * plot.height()

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; textSize = 10 * d }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.grid; strokeWidth = 1 * d }
        val dash = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)

        // горизонтальная сетка
        val step = if (yMax - yMin > 100) 20.0 else 10.0
        var v = ceil(yMin / step) * step
        text.textAlign = Paint.Align.RIGHT
        while (v <= yMax) {
            c.drawLine(plot.left, y(v), plot.right, y(v), grid)
            c.drawText(v.toInt().toString(), plot.left - 4 * d, y(v) + 3.5f * d, text)
            v += step
        }
        // подписи дат
        val days = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
        val ticks = min(days, 6).coerceAtLeast(1)
        val fmt = DateTimeFormatter.ofPattern("d.MM")
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until ticks) {
            val day = from.plusDays((i.toLong() * (days - 1)) / max(ticks - 1, 1))
            val tx = x(noon(day))
            c.drawLine(tx, plot.top, tx, plot.bottom, grid)
            c.drawText(day.format(fmt), tx, plot.bottom + 13 * d, text)
        }
        // целевые линии
        refs.forEach { r ->
            if (r.v in yMin..yMax) {
                val rp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = r.color; strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = dash
                }
                c.drawLine(plot.left, y(r.v), plot.right, y(r.v), rp)
            }
        }
        // начало приёма лекарств
        medStarts.forEach { (day, name) ->
            val t = day.atStartOfDay(zone).toInstant().toEpochMilli()
            if (t in t0..t1) {
                val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = p.med; strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = dash
                }
                c.drawLine(x(t), plot.top, x(t), plot.bottom, mp)
                val tp = Paint(text).apply { color = p.med; textAlign = Paint.Align.LEFT; textSize = 9 * d }
                c.drawText("▼ $name", x(t) + 2 * d, area.top + 10 * d, tp)
            }
        }
        // ряды
        series.forEach { s ->
            if (s.pts.isEmpty()) return@forEach
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color; strokeWidth = 1.6f * d; style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND; alpha = 170
            }
            val path = Path()
            s.pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(x(pt.t), y(pt.v)) else path.lineTo(x(pt.t), y(pt.v)) }
            c.drawPath(path, line)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color }
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = p.anomaly; style = Paint.Style.STROKE; strokeWidth = 2 * d
            }
            val r = if (s.pts.size > 60) 1.8f * d else 2.6f * d
            s.pts.forEach { pt ->
                c.drawCircle(x(pt.t), y(pt.v), r, dot)
                if (pt.id in anomalyIds) c.drawCircle(x(pt.t), y(pt.v), 5.5f * d, ring)
            }
        }
        if (series.all { it.pts.isEmpty() }) {
            text.textAlign = Paint.Align.CENTER; text.textSize = 13 * d
            c.drawText("Нет замеров за период", plot.centerX(), plot.centerY(), text)
        }
    }
}
