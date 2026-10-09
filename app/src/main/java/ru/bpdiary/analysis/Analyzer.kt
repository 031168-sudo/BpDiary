package ru.bpdiary.analysis

import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Measurement
import ru.bpdiary.data.Medication
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ───────────────────────── Категории давления ─────────────────────────
// Классификация по рекомендациям ESC/ESH и клиническим рекомендациям МЗ РФ
// «Артериальная гипертензия у взрослых». Целевое значение для ДОМАШНИХ
// измерений — < 135/85 мм рт. ст.

enum class BpCategory(val title: String, val argb: Long, val rank: Int) {
    HYPOTENSION("Пониженное", 0xFF5C6BC0, -1),
    OPTIMAL("Оптимальное", 0xFF2E7D32, 0),
    NORMAL("Нормальное", 0xFF66BB6A, 1),
    HIGH_NORMAL("Высокое нормальное", 0xFFF9A825, 2),
    GRADE1("АГ 1 степени", 0xFFEF6C00, 3),
    GRADE2("АГ 2 степени", 0xFFD84315, 4),
    GRADE3("АГ 3 степени", 0xFFB71C1C, 5),
}

object BpNorms {
    const val TARGET_SYS = 135
    const val TARGET_DIA = 85

    fun classify(sys: Int, dia: Int): BpCategory {
        val s = when {
            sys >= 180 -> BpCategory.GRADE3
            sys >= 160 -> BpCategory.GRADE2
            sys >= 140 -> BpCategory.GRADE1
            sys >= 130 -> BpCategory.HIGH_NORMAL
            sys >= 120 -> BpCategory.NORMAL
            else -> BpCategory.OPTIMAL
        }
        val d = when {
            dia >= 110 -> BpCategory.GRADE3
            dia >= 100 -> BpCategory.GRADE2
            dia >= 90 -> BpCategory.GRADE1
            dia >= 85 -> BpCategory.HIGH_NORMAL
            dia >= 80 -> BpCategory.NORMAL
            else -> BpCategory.OPTIMAL
        }
        val cat = if (s.rank >= d.rank) s else d
        if (cat.rank < BpCategory.GRADE1.rank && (sys < 90 || dia < 60)) return BpCategory.HYPOTENSION
        return cat
    }

    fun atTarget(sys: Int, dia: Int) = sys < TARGET_SYS && dia < TARGET_DIA && sys >= 90 && dia >= 60
}

// ───────────────────────── Аномалии ─────────────────────────

enum class AnomalyType(val title: String, val severity: Int) {
    CRISIS("Очень высокое давление (≥180/110)", 3),
    HYPOTENSION("Низкое давление (<90/60)", 2),
    IRREGULAR("Тонометр отметил аритмию", 2),
    SPIKE_UP("Резкий скачок вверх от вашей обычной нормы", 2),
    SPIKE_DOWN("Резкое снижение от вашей обычной нормы", 1),
    TACHYCARDIA("Учащённый пульс (>100)", 1),
    BRADYCARDIA("Редкий пульс (<50)", 1),
}

data class Anomaly(val m: Measurement, val type: AnomalyType, val detail: String)

// ───────────────────────── Статистика периода ─────────────────────────

data class PeriodStats(
    val from: LocalDate,
    val to: LocalDate,
    val count: Int,
    val daysWithData: Int,
    val sysMean: Double,
    val diaMean: Double,
    val pulseMean: Double?,
    val sysSd: Double,
    val diaSd: Double,
    val sysMin: Int, val sysMax: Int,
    val diaMin: Int, val diaMax: Int,
    val targetShare: Double,
    val morning: Pair<Double, Double>?,
    val evening: Pair<Double, Double>?,
    val anomalies: Int,
    val categories: Map<BpCategory, Int>,
)

data class Trend(val sysPerWeek: Double, val diaPerWeek: Double, val days: Int)

data class DailyMean(val day: LocalDate, val sys: Double, val dia: Double, val pulse: Double?, val n: Int)

// ───────────────────────── Стабилизация ─────────────────────────

enum class StabilityState(val title: String) {
    INSUFFICIENT("Мало данных"),
    STABLE_AT_TARGET("Стабилизировалось в целевых значениях"),
    STABLE_ABOVE_TARGET("Стабильно, но выше цели"),
    IMPROVING("Снижается — стабилизация идёт"),
    WORSENING("Растёт"),
    UNSTABLE("Нестабильно — сильные колебания"),
}

data class Stability(
    val state: StabilityState,
    val text: String,
    val sysMean: Double? = null,
    val diaMean: Double? = null,
    val dailySd: Double? = null,
    val stableSince: LocalDate? = null,
)

// ───────────────────────── Сравнение периодов ─────────────────────────

enum class Verdict(val title: String) {
    BETTER("Лучше"), SAME("Без существенных изменений"), WORSE("Хуже"), NO_DATA("Недостаточно данных")
}

data class CompareItem(
    val label: String,
    val prev: String,
    val cur: String,
    val diffText: String,
    /** true — улучшение, false — ухудшение, null — незначимо/нейтрально */
    val better: Boolean?,
)

data class Comparison(
    val curLabel: String,
    val prevLabel: String,
    val cur: PeriodStats?,
    val prev: PeriodStats?,
    val verdict: Verdict,
    val items: List<CompareItem>,
    val summary: String,
)

// ───────────────────────── Эффект лекарств ─────────────────────────

data class MedEffect(
    val med: Medication,
    val baseline: PeriodStats?,
    val treatment: PeriodStats?,
    val preliminary: Boolean,
    val daysOnTreatment: Long,
    val adherence: Double?,
    /** Среднее верхнее в дни, когда все приёмы отмечены / когда был пропуск */
    val takenVsMissed: Pair<Double, Double>?,
    val text: String,
)

data class AnalysisResult(
    val today: LocalDate,
    val anomalies: List<Anomaly>,
    val anomalyIds: Map<Long, List<Anomaly>>,
    val last7: PeriodStats?,
    val last30: PeriodStats?,
    val trend30: Trend?,
    val stability: Stability,
    val monthCmp: Comparison,
    val rollingCmp: Comparison,
    val medEffects: List<MedEffect>,
)

object Analyzer {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val ru: Locale = Locale.forLanguageTag("ru")
    val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", ru)

    fun dateOf(m: Measurement): LocalDate = Instant.ofEpochMilli(m.timestamp).atZone(zone).toLocalDate()
    fun dateTimeOf(m: Measurement): LocalDateTime = Instant.ofEpochMilli(m.timestamp).atZone(zone).toLocalDateTime()

    fun inRange(list: List<Measurement>, from: LocalDate, to: LocalDate) =
        list.filter { val d = dateOf(it); !d.isBefore(from) && !d.isAfter(to) }

    // ── аномалии ──
    fun detectAnomalies(all: List<Measurement>): List<Anomaly> {
        val sorted = all.sortedBy { it.timestamp }
        val out = ArrayList<Anomaly>()
        val windowMs = 14L * 24 * 3600 * 1000
        var lo = 0
        for ((i, m) in sorted.withIndex()) {
            val found = ArrayList<Anomaly>()
            if (m.systolic >= 180 || m.diastolic >= 110)
                found += Anomaly(m, AnomalyType.CRISIS, "${m.systolic}/${m.diastolic}")
            if (m.systolic < 90 || m.diastolic < 60)
                found += Anomaly(m, AnomalyType.HYPOTENSION, "${m.systolic}/${m.diastolic}")
            if (m.irregular)
                found += Anomaly(m, AnomalyType.IRREGULAR, "по данным тонометра")
            m.pulse?.let { p ->
                if (p > 100) found += Anomaly(m, AnomalyType.TACHYCARDIA, "пульс $p")
                if (p < 50) found += Anomaly(m, AnomalyType.BRADYCARDIA, "пульс $p")
            }
            // Личная норма: медиана предыдущих 14 дней, порог по MAD (устойчив к выбросам)
            while (lo < i && sorted[lo].timestamp < m.timestamp - windowMs) lo++
            if (i - lo >= 6) {
                val prev = sorted.subList(lo, i).map { it.systolic.toDouble() }
                val med = median(prev)
                val mad = median(prev.map { abs(it - med) })
                val thr = max(3.0 * 1.4826 * mad, 15.0)
                val dev = m.systolic - med
                val crisisOrLow = found.any { it.type == AnomalyType.CRISIS || it.type == AnomalyType.HYPOTENSION }
                if (!crisisOrLow) {
                    if (dev > thr) found += Anomaly(m, AnomalyType.SPIKE_UP,
                        "на ${dev.roundToInt()} выше обычных ${med.roundToInt()}")
                    if (dev < -thr) found += Anomaly(m, AnomalyType.SPIKE_DOWN,
                        "на ${(-dev).roundToInt()} ниже обычных ${med.roundToInt()}")
                }
            }
            out += found
        }
        return out.sortedByDescending { it.m.timestamp }
    }

    // ── статистика ──
    fun stats(all: List<Measurement>, from: LocalDate, to: LocalDate, anomalies: List<Anomaly>): PeriodStats? {
        val list = inRange(all, from, to)
        if (list.isEmpty()) return null
        val sys = list.map { it.systolic.toDouble() }
        val dia = list.map { it.diastolic.toDouble() }
        val pulses = list.mapNotNull { it.pulse?.toDouble() }
        val hourOf = { m: Measurement -> dateTimeOf(m).hour }
        val morning = list.filter { hourOf(it) in 4..11 }
        val evening = list.filter { hourOf(it) in 17..23 }
        val ids = list.map { it.id }.toSet()
        return PeriodStats(
            from = from, to = to,
            count = list.size,
            daysWithData = list.map { dateOf(it) }.distinct().size,
            sysMean = sys.average(), diaMean = dia.average(),
            pulseMean = pulses.takeIf { it.isNotEmpty() }?.average(),
            sysSd = sd(sys), diaSd = sd(dia),
            sysMin = list.minOf { it.systolic }, sysMax = list.maxOf { it.systolic },
            diaMin = list.minOf { it.diastolic }, diaMax = list.maxOf { it.diastolic },
            targetShare = list.count { BpNorms.atTarget(it.systolic, it.diastolic) }.toDouble() / list.size,
            morning = morning.takeIf { it.isNotEmpty() }
                ?.let { l -> l.map { it.systolic }.average() to l.map { it.diastolic }.average() },
            evening = evening.takeIf { it.isNotEmpty() }
                ?.let { l -> l.map { it.systolic }.average() to l.map { it.diastolic }.average() },
            anomalies = anomalies.filter { it.m.id in ids }.map { it.m.id }.distinct().size,
            categories = list.groupingBy { BpNorms.classify(it.systolic, it.diastolic) }.eachCount(),
        )
    }

    fun dailyMeans(list: List<Measurement>): List<DailyMean> =
        list.groupBy { dateOf(it) }.map { (d, l) ->
            DailyMean(
                d, l.map { it.systolic }.average(), l.map { it.diastolic }.average(),
                l.mapNotNull { it.pulse }.takeIf { it.isNotEmpty() }?.average(), l.size
            )
        }.sortedBy { it.day }

    fun trend(all: List<Measurement>, from: LocalDate, to: LocalDate): Trend? {
        val daily = dailyMeans(inRange(all, from, to))
        if (daily.size < 5) return null
        val xs = daily.map { it.day.toEpochDay().toDouble() }
        return Trend(theilSen(xs, daily.map { it.sys }) * 7, theilSen(xs, daily.map { it.dia }) * 7, daily.size)
    }

    // ── стабилизация ──
    fun stability(all: List<Measurement>, today: LocalDate): Stability {
        val from = today.minusDays(13)
        val daily = dailyMeans(inRange(all, from, today))
        if (daily.size < 7) return Stability(
            StabilityState.INSUFFICIENT,
            "Для оценки нужно хотя бы 7 дней с замерами за последние 2 недели (сейчас ${daily.size}). " +
                "Меряйте утром и вечером каждый день."
        )
        val sysMean = daily.map { it.sys }.average()
        val diaMean = daily.map { it.dia }.average()
        // устойчивый разброс (MAD) — один случайный скачок не делает картину «нестабильной»
        val dsd = daily.map { it.sys }.let { v -> val m = median(v); 1.4826 * median(v.map { abs(it - m) }) }
        val xs = daily.map { it.day.toEpochDay().toDouble() }
        // Тейл–Сен: медиана попарных наклонов, не реагирует на единичные выбросы
        val slopeW = theilSen(xs, daily.map { it.sys }) * 7
        val avg = "${sysMean.roundToInt()}/${diaMean.roundToInt()}"

        val state = when {
            slopeW <= -3.0 -> StabilityState.IMPROVING
            slopeW >= 3.0 -> StabilityState.WORSENING
            dsd > 8.0 -> StabilityState.UNSTABLE
            sysMean < BpNorms.TARGET_SYS && diaMean < BpNorms.TARGET_DIA -> StabilityState.STABLE_AT_TARGET
            else -> StabilityState.STABLE_ABOVE_TARGET
        }
        val since = if (state == StabilityState.STABLE_AT_TARGET || state == StabilityState.STABLE_ABOVE_TARGET)
            stableSince(all, today) else null
        val sinceText = since?.let { " Держится на этом уровне примерно с ${it.format(dayFmt)}." } ?: ""
        val text = when (state) {
            StabilityState.IMPROVING ->
                "За 2 недели верхнее давление снижается примерно на ${abs(slopeW).roundToInt()} мм рт. ст. в неделю " +
                    "(в среднем $avg). Так бывает в первые недели после начала или смены лечения — стабилизация ещё идёт."
            StabilityState.WORSENING ->
                "За 2 недели верхнее давление растёт примерно на ${slopeW.roundToInt()} мм рт. ст. в неделю " +
                    "(в среднем $avg). Стоит обсудить с врачом, особенно если были пропуски приёма."
            StabilityState.UNSTABLE ->
                "Средние значения по дням сильно колеблются (разброс ±${dsd.roundToInt()}), в среднем $avg. " +
                    "Проверьте регулярность приёма и условия измерения (покой 5 минут, одна рука, одно время)."
            StabilityState.STABLE_AT_TARGET ->
                "Среднее за 2 недели $avg — в пределах цели для домашних измерений (<135/85), " +
                    "колебания небольшие (±${dsd.roundToInt()}).$sinceText"
            StabilityState.STABLE_ABOVE_TARGET ->
                "Давление ровное (±${dsd.roundToInt()}), но в среднем $avg — выше цели для домашних измерений " +
                    "(<135/85). Возможно, схему лечения стоит обсудить с врачом.$sinceText"
            StabilityState.INSUFFICIENT -> ""
        }
        return Stability(state, text, sysMean, diaMean, dsd, since)
    }

    /** Самый ранний день, начиная с которого скользящая 7-дневная медиана держится в ±5 от текущей. */
    private fun stableSince(all: List<Measurement>, today: LocalDate): LocalDate? {
        val daily = dailyMeans(inRange(all, today.minusDays(180), today))
        if (daily.size < 7) return null
        val rolling = daily.map { d ->
            val win = daily.filter { !it.day.isAfter(d.day) && it.day.isAfter(d.day.minusDays(7)) }
            d.day to median(win.map { it.sys })
        }
        val last = rolling.last().second
        var since = rolling.last().first
        for (i in rolling.indices.reversed()) {
            if (abs(rolling[i].second - last) <= 5.0) since = rolling[i].first else break
        }
        return if (since.isBefore(today.minusDays(7))) since else null
    }

    // ── сравнение периодов ──
    fun compare(
        all: List<Measurement>, anomalies: List<Anomaly>,
        curFrom: LocalDate, curTo: LocalDate, prevFrom: LocalDate, prevTo: LocalDate,
        curLabel: String, prevLabel: String,
    ): Comparison {
        val cur = stats(all, curFrom, curTo, anomalies)
        val prev = stats(all, prevFrom, prevTo, anomalies)
        if (cur == null || prev == null || cur.count < 5 || prev.count < 5) {
            return Comparison(
                curLabel, prevLabel, cur, prev, Verdict.NO_DATA, emptyList(),
                "Для сравнения нужно хотя бы по 5 замеров в каждом периоде " +
                    "($prevLabel: ${prev?.count ?: 0}, $curLabel: ${cur?.count ?: 0})."
            )
        }
        val items = ArrayList<CompareItem>()
        var score = 0

        val i0: (Double) -> String = { it.roundToInt().toString() }
        fun add(label: String, p: Double, c: Double, fmt: (Double) -> String, unit: String,
                threshold: Double, lowerIsBetter: Boolean?) {
            val diff = if (fmt === i0) (c.roundToInt() - p.roundToInt()).toDouble() else c - p
            val better = when {
                lowerIsBetter == null || abs(diff) < threshold -> null
                lowerIsBetter -> diff < 0
                else -> diff > 0
            }
            if (better == true) score++ else if (better == false) score--
            val sign = if (diff > 0) "+" else if (diff < 0) "−" else "±"
            items += CompareItem(label, fmt(p), fmt(c), "$sign${fmt(abs(diff))}$unit", better)
        }
        add("Среднее верхнее", prev.sysMean, cur.sysMean, i0, "", 3.0, true)
        add("Среднее нижнее", prev.diaMean, cur.diaMean, i0, "", 2.0, true)
        add("Замеров в цели (<135/85)", prev.targetShare * 100, cur.targetShare * 100, i0, " п.п.", 10.0, false)
        add("Разброс верхнего (±)", prev.sysSd, cur.sysSd, i0, "", 2.0, true)
        add("Аномалий на 10 замеров", prev.anomalies * 10.0 / prev.count, cur.anomalies * 10.0 / cur.count,
            { String.format(ru, "%.1f", it) }, "", 1.0, true)
        if (prev.pulseMean != null && cur.pulseMean != null)
            add("Средний пульс", prev.pulseMean, cur.pulseMean, i0, "", 0.0, null)

        val verdict = when {
            score >= 1 -> Verdict.BETTER
            score <= -1 -> Verdict.WORSE
            else -> Verdict.SAME
        }
        val dS = cur.sysMean.roundToInt() - prev.sysMean.roundToInt()
        val dD = cur.diaMean.roundToInt() - prev.diaMean.roundToInt()
        val summary = when (verdict) {
            Verdict.BETTER -> "$curLabel лучше, чем $prevLabel: среднее давление изменилось на " +
                "${signed(dS)}/${signed(dD)} мм рт. ст., в цели ${(cur.targetShare * 100).roundToInt()}% замеров."
            Verdict.WORSE -> "$curLabel хуже, чем $prevLabel: среднее давление изменилось на " +
                "${signed(dS)}/${signed(dD)} мм рт. ст., в цели ${(cur.targetShare * 100).roundToInt()}% замеров."
            else -> "$curLabel примерно как $prevLabel: среднее ${cur.sysMean.roundToInt()}/${cur.diaMean.roundToInt()} " +
                "против ${prev.sysMean.roundToInt()}/${prev.diaMean.roundToInt()}."
        }
        return Comparison(curLabel, prevLabel, cur, prev, verdict, items, summary)
    }

    // ── лекарства ──
    fun medEffect(
        med: Medication, all: List<Measurement>, logs: List<DoseLog>,
        anomalies: List<Anomaly>, today: LocalDate,
    ): MedEffect {
        val start = LocalDate.ofEpochDay(med.startDay)
        val end = med.endDay?.let { LocalDate.ofEpochDay(it) }?.let { if (it.isAfter(today)) today else it } ?: today
        val daysOn = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
        val baseline = stats(all, start.minusDays(14), start.minusDays(1), anomalies)
        // Эффект большинства гипотензивных препаратов устанавливается за 2–4 недели,
        // поэтому первые 14 дней приёма в оценку не берём (если уже есть что брать).
        val effFrom = start.plusDays(14)
        val preliminary = effFrom.isAfter(end)
        val treatment = stats(all, if (preliminary) start else effFrom, end, anomalies)

        // приверженность за последние 30 дней приёма
        val medLogs = logs.filter { it.medicationId == med.id }
        val slots = med.slots()
        val adhFrom = maxOf(start, end.minusDays(29))
        val adhDays = (adhFrom.toEpochDay()..end.toEpochDay())
        val adherence = if (medLogs.isEmpty()) null else {
            val expected = adhDays.count() * slots.size
            val taken = medLogs.count { it.day in adhDays && it.slot in slots }
            if (expected > 0) taken.toDouble() / expected else null
        }

        // давление в дни без пропусков vs дни с пропусками (последние 60 дней)
        var takenVsMissed: Pair<Double, Double>? = null
        if (medLogs.isNotEmpty()) {
            val byDay = dailyMeans(inRange(all, maxOf(start, end.minusDays(59)), end)).associateBy { it.day.toEpochDay() }
            val logsByDay = medLogs.groupBy { it.day }
            val full = ArrayList<Double>(); val missed = ArrayList<Double>()
            for ((day, dm) in byDay) {
                val n = logsByDay[day]?.count { it.slot in slots } ?: 0
                if (n >= slots.size) full += dm.sys else missed += dm.sys
            }
            if (full.size >= 3 && missed.size >= 3) takenVsMissed = full.average() to missed.average()
        }

        val text = buildString {
            when {
                baseline == null || baseline.count < 3 ->
                    append("Нет замеров за 2 недели до начала приёма — сравнить «до/после» не с чем.")
                treatment == null || treatment.count < 3 ->
                    append("Пока мало замеров на фоне приёма.")
                else -> {
                    val dS = treatment.sysMean.roundToInt() - baseline.sysMean.roundToInt()
                    val dD = treatment.diaMean.roundToInt() - baseline.diaMean.roundToInt()
                    append("До начала: ${baseline.sysMean.roundToInt()}/${baseline.diaMean.roundToInt()}, ")
                    append(if (preliminary) "сейчас: " else "на фоне приёма: ")
                    append("${treatment.sysMean.roundToInt()}/${treatment.diaMean.roundToInt()} ")
                    append("(${signed(dS)}/${signed(dD)}).")
                    if (preliminary) append(" Оценка предварительная: прошло $daysOn дн., полный эффект обычно через 2–4 недели.")
                    else when {
                        dS <= -5 -> append(" Заметное снижение давления.")
                        dS >= 5 -> append(" Давление не снизилось, а выросло.")
                        else -> append(" Существенного изменения нет.")
                    }
                }
            }
            adherence?.let { append(" Отмечено приёмов: ${(it * 100).roundToInt()}% за последние 30 дней.") }
            takenVsMissed?.let { (t, m) ->
                append(" В дни без пропусков верхнее в среднем ${t.roundToInt()}, в дни с пропусками — ${m.roundToInt()}.")
            }
        }
        return MedEffect(med, baseline, treatment, preliminary, daysOn, adherence, takenVsMissed, text)
    }

    // ── всё вместе ──
    fun analyze(
        all: List<Measurement>, meds: List<Medication>, logs: List<DoseLog>,
        today: LocalDate = LocalDate.now(),
    ): AnalysisResult {
        val anomalies = detectAnomalies(all)
        val monthStart = today.withDayOfMonth(1)
        val prevMonthStart = monthStart.minusMonths(1)
        val monthName = { d: LocalDate -> d.month.getDisplayName(TextStyle.FULL_STANDALONE, ru) }
        return AnalysisResult(
            today = today,
            anomalies = anomalies,
            anomalyIds = anomalies.groupBy { it.m.id },
            last7 = stats(all, today.minusDays(6), today, anomalies),
            last30 = stats(all, today.minusDays(29), today, anomalies),
            trend30 = trend(all, today.minusDays(29), today),
            stability = stability(all, today),
            monthCmp = compare(
                all, anomalies, monthStart, today, prevMonthStart, monthStart.minusDays(1),
                monthName(monthStart).replaceFirstChar { it.uppercase() }, monthName(prevMonthStart)
            ),
            rollingCmp = compare(
                all, anomalies, today.minusDays(29), today, today.minusDays(59), today.minusDays(30),
                "Последние 30 дней", "предыдущие 30 дней"
            ),
            medEffects = meds.sortedByDescending { it.startDay }.map { medEffect(it, all, logs, anomalies, today) },
        )
    }

    // ── математика ──
    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun theilSen(x: List<Double>, y: List<Double>): Double {
        val slopes = ArrayList<Double>()
        for (i in x.indices) for (j in i + 1 until x.size)
            if (x[j] != x[i]) slopes += (y[j] - y[i]) / (x[j] - x[i])
        return median(slopes)
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else if (v < 0) "−${-v}" else "0"
}
