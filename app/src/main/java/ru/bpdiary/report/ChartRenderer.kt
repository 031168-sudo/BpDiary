package ru.bpdiary.report

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.analysis.BpNorms
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Measurement
import ru.bpdiary.data.Medication
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

    /** Цвета препаратов на графике приёма (по порядку). */
    fun medColor(i: Int, dark: Boolean): Int {
        val light = intArrayOf(0xFF00897B.toInt(), 0xFF5E35B1.toInt(), 0xFFF57C00.toInt(), 0xFF1E88E5.toInt(), 0xFF6D4C41.toInt(), 0xFFC2185B.toInt())
        val darkC = intArrayOf(0xFF4DB6AC.toInt(), 0xFF9575CD.toInt(), 0xFFFFB74D.toInt(), 0xFF64B5F6.toInt(), 0xFFA1887F.toInt(), 0xFFF06292.toInt())
        return (if (dark) darkC else light)[i % light.size]
    }
    const val MISSED_COLOR = 0xFFE53935.toInt()

    /**
     * График времени приёма лекарств: по X — дни, по Y — часы суток (0…24).
     * Каждый отмеченный приём — точка; день с недобранными приёмами — красный «×» внизу.
     */
    fun drawDoses(
        c: Canvas, area: RectF, meds: List<Medication>, logs: List<DoseLog>,
        from: LocalDate, to: LocalDate, trackingStart: Long, p: Palette, dark: Boolean, d: Float,
        highlightDay: Long? = null,
    ) {
        val zone = ZoneId.systemDefault()
        val t0 = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val t1 = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val plot = RectF(area.left + DOSE_LEFT * d, area.top + 8 * d, area.right - 6 * d, area.bottom - 18 * d)
        fun x(day: Long) = plot.left + (noon(LocalDate.ofEpochDay(day)) - t0).toFloat() / (t1 - t0) * plot.width()
        fun y(hours: Float) = plot.bottom - hours / 24f * plot.height()

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; textSize = 10 * d }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.grid; strokeWidth = 1 * d }

        // часы по вертикали
        text.textAlign = Paint.Align.RIGHT
        for (h in 0..24 step 3) {
            c.drawLine(plot.left, y(h.toFloat()), plot.right, y(h.toFloat()), grid)
            c.drawText("%d:00".format(h), plot.left - 4 * d, y(h.toFloat()) + 3.5f * d, text)
        }
        // даты по горизонтали
        val days = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
        val ticks = min(days, 6).coerceAtLeast(1)
        val fmt = DateTimeFormatter.ofPattern("d.MM")
        text.textAlign = Paint.Align.CENTER
        for (i in 0 until ticks) {
            val day = from.plusDays((i.toLong() * (days - 1)) / max(ticks - 1, 1))
            val tx = x(day.toEpochDay())
            c.drawLine(tx, plot.top, tx, plot.bottom, grid)
            c.drawText(day.format(fmt), tx, plot.bottom + 13 * d, text)
        }

        // выбранный пальцем день — подсветка столбца
        if (highlightDay != null && highlightDay in from.toEpochDay()..to.toEpochDay()) {
            val w = max(plot.width() / days, 6f * d)
            val band = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; alpha = 40 }
            c.drawRect(x(highlightDay) - w / 2, plot.top, x(highlightDay) + w / 2, plot.bottom, band)
        }
        val r = when { days > 120 -> 1.8f; days > 40 -> 2.6f; else -> 3.6f } * d
        val fromD = from.toEpochDay(); val toD = to.toEpochDay()
        val today = LocalDate.now().toEpochDay()
        var any = false
        // пропуски
        val missedDays = HashSet<Long>()
        meds.forEach { m ->
            val first = maxOf(fromD, m.startDay, trackingStart)
            val last = minOf(toD, today - 1, m.endDay ?: Long.MAX_VALUE)
            for (day in first..last) if (logs.count { it.medicationId == m.id && it.day == day } < m.perDay) missedDays += day
        }
        val miss = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MISSED_COLOR; strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
        val mr = max(r, 2.6f * d)
        missedDays.forEach { day ->
            val cx = x(day); val cy = plot.bottom - mr - 1.5f * d
            c.drawLine(cx - mr, cy - mr, cx + mr, cy + mr, miss)
            c.drawLine(cx - mr, cy + mr, cx + mr, cy - mr, miss)
        }
        // приёмы
        meds.forEachIndexed { i, m ->
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = medColor(i, dark) }
            logs.filter { it.medicationId == m.id && it.day in fromD..toD }.forEach { l ->
                val t = java.time.Instant.ofEpochMilli(l.takenAt).atZone(zone).toLocalTime()
                val h = t.hour + t.minute / 60f
                c.drawCircle(x(l.day), y(h), r, dot)
                any = true
            }
        }
        if (!any && missedDays.isEmpty()) {
            text.textAlign = Paint.Align.CENTER; text.textSize = 13 * d
            c.drawText("Нет отметок приёма за период", plot.centerX(), plot.centerY(), text)
        }
    }

    /** Точка графика давления/пульса: один замер или (на длинных периодах) среднее за день. */
    data class BpPoint(val t: Long, val sys: Double, val dia: Double, val pulse: Double?, val id: Long, val n: Int)

    const val BP_LEFT = 30f
    const val DOSE_LEFT = 34f

    fun bpPoints(all: List<Measurement>, from: LocalDate, to: LocalDate): List<BpPoint> {
        val list = Analyzer.inRange(all, from, to)
        return if (list.size > 150) Analyzer.dailyMeans(list).map { BpPoint(noon(it.day), it.sys, it.dia, it.pulse, -1, it.n) }
        else list.map { BpPoint(it.timestamp, it.systolic.toDouble(), it.diastolic.toDouble(), it.pulse?.toDouble(), it.id, 1) }
            .sortedBy { it.t }
    }

    fun pulsePoints(all: List<Measurement>, from: LocalDate, to: LocalDate): List<BpPoint> {
        val list = Analyzer.inRange(all, from, to).filter { it.pulse != null }
        return if (list.size > 150) Analyzer.dailyMeans(list).filter { it.pulse != null }
            .map { BpPoint(noon(it.day), it.sys, it.dia, it.pulse, -1, it.n) }
        else list.map { BpPoint(it.timestamp, it.systolic.toDouble(), it.diastolic.toDouble(), it.pulse!!.toDouble(), it.id, 1) }
            .sortedBy { it.t }
    }

    /** Момент времени под пальцем: x — координата на графике шириной width (в пикселях). */
    fun timeAt(x: Float, width: Float, from: LocalDate, to: LocalDate, d: Float, left: Float): Long {
        val zone = ZoneId.systemDefault()
        val t0 = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val t1 = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val l = left * d; val r = width - 6 * d
        val f = ((x - l) / (r - l)).coerceIn(0f, 1f)
        return t0 + ((t1 - t0) * f).toLong()
    }

    private data class Pt(val t: Long, val v: Double, val id: Long)
    private class Series(val pts: List<Pt>, val color: Int)
    private class RefLine(val v: Double, val color: Int, val label: String)

    fun drawBp(
        c: Canvas, area: RectF, all: List<Measurement>, from: LocalDate, to: LocalDate,
        anomalyIds: Set<Long>, medStarts: List<Pair<LocalDate, String>>, p: Palette, density: Float,
        targetSys: Int = BpNorms.targetSys, targetDia: Int = BpNorms.targetDia, highlight: Long? = null,
    ) {
        val pts = bpPoints(all, from, to)
        val sys = pts.map { Pt(it.t, it.sys, it.id) }
        val dia = pts.map { Pt(it.t, it.dia, it.id) }
        val lo = min(dia.minOfOrNull { it.v } ?: 70.0, minOf(70.0, targetDia - 5.0)) - 5
        val hi = max(sys.maxOfOrNull { it.v } ?: 150.0, 150.0) + 5
        drawChart(
            c, area, from, to, floor(lo / 10) * 10, ceil(hi / 10) * 10,
            listOf(Series(sys, p.sys), Series(dia, p.dia)),
            listOf(RefLine(targetSys.toDouble(), p.target, ""), RefLine(targetDia.toDouble(), p.target, "")),
            anomalyIds, medStarts, p, density, highlight,
        )
    }

    fun drawPulse(
        c: Canvas, area: RectF, all: List<Measurement>, from: LocalDate, to: LocalDate,
        p: Palette, density: Float, highlight: Long? = null,
    ) {
        val pts = pulsePoints(all, from, to).map { Pt(it.t, it.pulse!!, it.id) }
        val lo = min(pts.minOfOrNull { it.v } ?: 50.0, 50.0) - 5
        val hi = max(pts.maxOfOrNull { it.v } ?: 100.0, 100.0) + 5
        drawChart(
            c, area, from, to, floor(lo / 10) * 10, ceil(hi / 10) * 10,
            listOf(Series(pts, p.pulse)),
            listOf(RefLine(100.0, p.anomaly, "100"), RefLine(50.0, p.anomaly, "50")),
            emptySet(), emptyList(), p, density, highlight,
        )
    }

    private fun noon(d: LocalDate) = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun drawChart(
        c: Canvas, area: RectF, from: LocalDate, to: LocalDate, yMin: Double, yMax: Double,
        series: List<Series>, refs: List<RefLine>, anomalyIds: Set<Long>,
        medStarts: List<Pair<LocalDate, String>>, p: Palette, d: Float, highlight: Long? = null,
    ) {
        val zone = ZoneId.systemDefault()
        val t0 = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val t1 = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val plot = RectF(area.left + BP_LEFT * d, area.top + 14 * d, area.right - 6 * d, area.bottom - 18 * d)
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
        // выбранная пальцем точка: вертикальная линия и увеличенные маркеры
        if (highlight != null && highlight in t0..t1) {
            val hx = x(highlight)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; strokeWidth = 1.2f * d; alpha = 150 }
            c.drawLine(hx, plot.top, hx, plot.bottom, line)
            series.forEach { s ->
                s.pts.firstOrNull { it.t == highlight }?.let { pt ->
                    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; alpha = 60 }
                    c.drawCircle(hx, y(pt.v), 10f * d, halo)
                    c.drawCircle(hx, y(pt.v), 5f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color })
                }
            }
        }
        if (series.all { it.pts.isEmpty() }) {
            text.textAlign = Paint.Align.CENTER; text.textSize = 13 * d
            c.drawText("Нет замеров за период", plot.centerX(), plot.centerY(), text)
        }
    }
}
