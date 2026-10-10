package ru.bpdiary.report

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import ru.bpdiary.analysis.AnalysisResult
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.analysis.AnomalyType
import ru.bpdiary.analysis.BpNorms
import ru.bpdiary.analysis.StabilityState
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Measurement
import ru.bpdiary.data.Medication
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private val ru: Locale = Locale.forLanguageTag("ru")
    private val dFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)

    private val SHORT = mapOf(
        AnomalyType.CRISIS to "≥180/110",
        AnomalyType.HYPOTENSION to "низкое",
        AnomalyType.IRREGULAR to "аритмия",
        AnomalyType.SPIKE_UP to "скачок ↑",
        AnomalyType.SPIKE_DOWN to "снижение ↓",
        AnomalyType.TACHYCARDIA to "пульс >100",
        AnomalyType.BRADYCARDIA to "пульс <50",
    )

    /** Пишет страницы сверху вниз и сам переносит на новую страницу. */
    private class Writer(val doc: PdfDocument) {
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
        val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val h1 = TextPaint(bold).apply { textSize = 18f }
        val h2 = TextPaint(bold).apply { textSize = 12.5f; color = 0xFFB71C1C.toInt() }
        val small = TextPaint(body).apply { textSize = 8f; color = Color.DKGRAY }

        init { newPage() }

        fun newPage() {
            if (pageNo > 0) finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M
        }

        fun finishPage() {
            c.drawText("Давление · стр. $pageNo", M, H - 20f, small)
            doc.finishPage(page)
        }

        fun ensure(h: Float) { if (y + h > H - M) newPage() }

        fun para(text: String, paint: TextPaint = body, gapAfter: Float = 4f) {
            val width = (W - 2 * M).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
            ensure(layout.height.toFloat())
            c.save(); c.translate(M, y); layout.draw(c); c.restore()
            y += layout.height + gapAfter
        }

        fun heading(text: String) { ensure(40f); y += 8f; para(text, h2, 4f) }

        fun kv(k: String, v: String) {
            ensure(14f)
            c.drawText(k, M, y + 10f, body)
            c.drawText(v, M + 200f, y + 10f, bold)
            y += 14f
        }
    }

    fun create(
        ctx: Context, from: LocalDate, to: LocalDate,
        all: List<Measurement>, meds: List<Medication>, logs: List<DoseLog>, a: AnalysisResult,
    ): Uri {
        val doc = PdfDocument()
        val w = Writer(doc)

        // ── шапка ──
        w.para("Дневник артериального давления", w.h1, 2f)
        w.para("Период: ${from.format(dFmt)} — ${to.format(dFmt)}. Сформирован ${LocalDate.now().format(dFmt)}.", w.small, 6f)

        val s = Analyzer.stats(all, from, to, a.anomalies)
        if (s == null) {
            w.para("За выбранный период замеров нет.")
        } else {
            w.heading("Сводка")
            w.kv("Замеров / дней с замерами", "${s.count} / ${s.daysWithData}")
            w.kv("Среднее АД", "${s.sysMean.roundToInt()}/${s.diaMean.roundToInt()} мм рт. ст.")
            w.kv("Верхнее: мин–макс (разброс)", "${s.sysMin}–${s.sysMax} (±${s.sysSd.roundToInt()})")
            w.kv("Нижнее: мин–макс (разброс)", "${s.diaMin}–${s.diaMax} (±${s.diaSd.roundToInt()})")
            s.pulseMean?.let { w.kv("Средний пульс", "${it.roundToInt()} уд/мин") }
            w.kv("Замеров в цели (<135/85)", "${(s.targetShare * 100).roundToInt()}%")
            s.morning?.let { w.kv("Утро (4–12 ч), среднее", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
            s.evening?.let { w.kv("Вечер (17–24 ч), среднее", "${it.first.roundToInt()}/${it.second.roundToInt()}") }
            w.kv("Замеров с отклонениями", s.anomalies.toString())
            val cats = s.categories.entries.sortedBy { it.key.rank }
                .joinToString("; ") { "${it.key.title} — ${(it.value * 100.0 / s.count).roundToInt()}%" }
            w.para("Распределение: $cats", w.small, 4f)

            // ── сравнение с предыдущим таким же периодом ──
            val len = ChronoUnit.DAYS.between(from, to) + 1
            val cmp = Analyzer.compare(all, a.anomalies, from, to, from.minusDays(len), from.minusDays(1),
                "Этот период", "предыдущие $len дн.")
            w.heading("Сравнение с предыдущими $len дн.")
            w.para("${cmp.verdict.title}. ${cmp.summary}")
            cmp.items.forEach { w.para("• ${it.label}: ${it.prev} → ${it.cur} (${it.diffText})", w.body, 1f) }

            if (to == a.today && a.stability.state != StabilityState.INSUFFICIENT) {
                w.heading("Стабилизация (последние 2 недели)")
                w.para("${a.stability.state.title}. ${a.stability.text}")
            }
        }

        // ── лекарства ──
        val relevant = meds.filter { m ->
            m.startDay <= to.toEpochDay() && (m.endDay == null || m.endDay >= from.minusDays(30).toEpochDay())
        }
        if (relevant.isNotEmpty()) {
            w.heading("Лекарства")
            relevant.sortedBy { it.startDay }.forEach { m ->
                val period = "с ${LocalDate.ofEpochDay(m.startDay).format(dFmt)}" +
                    (m.endDay?.let { " по ${LocalDate.ofEpochDay(it).format(dFmt)}" } ?: " — по настоящее время")
                w.para("${m.name} ${m.dose}, приём: ${m.slots().joinToString(", ")}; $period", w.bold, 1f)
                a.medEffects.firstOrNull { it.med.id == m.id }?.let { w.para(it.text, w.body, 2f) }
                adherenceLine(m, from, to, all, logs)?.let { w.para(it, w.body, 6f) }
            }
        }

        // ── графики ──
        if (s != null) {
            val medStarts = meds.map { LocalDate.ofEpochDay(it.startDay) to it.name }
            val ids = a.anomalyIds.keys
            w.heading("График давления")
            w.ensure(220f)
            ChartRenderer.drawBp(w.c, RectF(M, w.y, W - M, w.y + 210f), all, from, to, ids, medStarts, ChartRenderer.LIGHT, 0.9f)
            w.y += 214f
            w.para("Красная — верхнее, синяя — нижнее, зелёный пунктир — цель 135/85, оранжевые кольца — отклонения, " +
                "бирюзовый пунктир — начало приёма препарата.", w.small, 6f)
            w.heading("Пульс")
            w.ensure(140f)
            ChartRenderer.drawPulse(w.c, RectF(M, w.y, W - M, w.y + 130f), all, from, to, ChartRenderer.LIGHT, 0.9f)
            w.y += 134f
        }

        // ── таблица замеров ──
        val rows = Analyzer.inRange(all, from, to).sortedBy { it.timestamp }
        if (rows.isNotEmpty()) {
            w.newPage()
            w.heading("Все замеры")
            val cols = floatArrayOf(M, M + 80, M + 125, M + 185, M + 225, M + 330)
            fun header() {
                w.ensure(16f)
                listOf("Дата", "Время", "АД", "Пульс", "Категория", "Отметки").forEachIndexed { i, t ->
                    w.c.drawText(t, cols[i], w.y + 10f, w.bold)
                }
                w.c.drawLine(M, w.y + 13f, W - M, w.y + 13f, w.body)
                w.y += 16f
            }
            header()
            val hl = Paint().apply { color = 0x33FF9800 }
            val dateF = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val timeF = DateTimeFormatter.ofPattern("HH:mm")
            rows.forEach { m ->
                if (w.y + 13f > H - M) { w.newPage(); header() }
                val dt = Analyzer.dateTimeOf(m)
                val an = a.anomalyIds[m.id].orEmpty()
                if (an.isNotEmpty()) w.c.drawRect(M - 2, w.y, W - M + 2, w.y + 13f, hl)
                val cat = BpNorms.classify(m.systolic, m.diastolic)
                val marks = (an.mapNotNull { SHORT[it.type] } + listOfNotNull(m.arm?.let { "рука $it" }, m.note.takeIf { it.isNotBlank() }))
                    .joinToString(", ")
                val cells = listOf(dt.format(dateF), dt.format(timeF), "${m.systolic}/${m.diastolic}",
                    m.pulse?.toString() ?: "—", cat.title, ellipsize(marks, w.body, W - M - cols[5]))
                cells.forEachIndexed { i, t -> w.c.drawText(t, cols[i], w.y + 10f, if (i == 2) w.bold else w.body) }
                w.y += 13f
            }
        }

        w.y += 10f
        w.para("Отчёт сформирован приложением по данным самоконтроля и не является медицинским заключением. " +
            "Категории — по классификации ESC/ESH; цель для домашних измерений — менее 135/85 мм рт. ст.", w.small)
        w.finishPage()

        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "davlenie_${from}_${to}.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    }

    /** «Приёмов отмечено N из M (P%). Пропуски: …» за период отчёта. */
    private fun adherenceLine(
        m: Medication, from: LocalDate, to: LocalDate, all: List<Measurement>, logs: List<DoseLog>,
    ): String? {
        val medLogs = logs.filter { it.medicationId == m.id }
        if (medLogs.isEmpty()) return null
        // считаем с первого дня пользования приложением, чтобы не записывать в пропуски время до установки
        val trackingStart = (all.map { Analyzer.dateOf(it).toEpochDay() } + logs.map { it.day }).minOrNull() ?: return null
        val today = LocalDate.now().toEpochDay()
        val first = maxOf(from.toEpochDay(), m.startDay, trackingStart)
        val last = minOf(to.toEpochDay(), m.endDay ?: today, today)
        if (first > last) return null
        val slots = m.slots()
        val taken = medLogs.map { it.day to it.slot }.toSet()
        val missed = ArrayList<String>()
        var expected = 0
        val f = DateTimeFormatter.ofPattern("d MMM", ru)
        for (d in first..last) for (slot in slots) {
            // сегодняшний приём, время которого ещё не наступило, не считаем
            if (d == today && slot.length == 5 && slot > java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))) continue
            expected++
            if ((d to slot) !in taken) missed += "${LocalDate.ofEpochDay(d).format(f)} $slot"
        }
        if (expected == 0) return null
        val pct = ((expected - missed.size) * 100.0 / expected).roundToInt()
        return buildString {
            append("Приём отмечен: ${expected - missed.size} из $expected ($pct%).")
            if (missed.isNotEmpty()) {
                append(" Пропуски: ")
                append(missed.take(40).joinToString(", "))
                if (missed.size > 40) append(" и ещё ${missed.size - 40}")
                append(".")
            }
        }
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
