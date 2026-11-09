package ru.bpdiary.ble

import java.time.LocalDateTime
import kotlin.math.pow
import kotlin.math.roundToInt

/** Один замер, полученный от тонометра. */
data class BleReading(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    /** Время из тонометра (его часы), если он его прислал. */
    val time: LocalDateTime?,
    val userId: Int?,
    val irregular: Boolean,
    /** Предупреждения тонометра из поля «статус замера» (кроме аритмии — она в irregular). */
    val warnings: List<String> = emptyList(),
)

/**
 * Разбор характеристики Blood Pressure Measurement (0x2A35) стандартного
 * профиля Bluetooth для тонометров (Bluetooth SIG, Blood Pressure Service 0x1810).
 */
object BpParser {
    private const val KPA_TO_MMHG = 7.50062

    fun parse(b: ByteArray): BleReading? {
        if (b.size < 7) return null
        val flags = b[0].toInt() and 0xFF
        val kpa = flags and 0x01 != 0
        val hasTime = flags and 0x02 != 0
        val hasPulse = flags and 0x04 != 0
        val hasUser = flags and 0x08 != 0
        val hasStatus = flags and 0x10 != 0

        var i = 1
        fun need(n: Int) = i + n <= b.size
        val sys = sfloat(b, i) ?: return null; i += 2
        val dia = sfloat(b, i) ?: return null; i += 2
        i += 2 // среднее артериальное давление — не используем

        var time: LocalDateTime? = null
        if (hasTime) {
            if (!need(7)) return null
            val year = u16(b, i)
            time = runCatching {
                LocalDateTime.of(year, b[i + 2].toInt(), b[i + 3].toInt(), b[i + 4].toInt(), b[i + 5].toInt(), b[i + 6].toInt())
            }.getOrNull()
            i += 7
        }
        var pulse: Int? = null
        if (hasPulse) {
            if (!need(2)) return null
            pulse = sfloat(b, i)?.roundToInt()?.takeIf { it in 20..250 }
            i += 2
        }
        var user: Int? = null
        if (hasUser) {
            if (!need(1)) return null
            user = (b[i].toInt() and 0xFF).takeIf { it != 0xFF }
            i += 1
        }
        var irregular = false
        val warnings = ArrayList<String>()
        if (hasStatus && need(2)) {
            val st = u16(b, i)
            irregular = st and 0x0004 != 0
            if (st and 0x0001 != 0) warnings += "движение во время замера"
            if (st and 0x0002 != 0) warnings += "манжета надета слишком свободно"
            when ((st shr 3) and 0x03) {
                1 -> warnings += "пульс выше допустимого диапазона"
                2 -> warnings += "пульс ниже допустимого диапазона"
            }
            if (st and 0x0020 != 0) warnings += "неправильное положение при замере"
        }

        val k = if (kpa) KPA_TO_MMHG else 1.0
        val s = (sys * k).roundToInt()
        val d = (dia * k).roundToInt()
        if (s !in 40..300 || d !in 20..200) return null
        return BleReading(s, d, pulse, time, user, irregular, warnings)
    }

    /** Промежуточное давление в манжете во время замера (0x2A36), мм рт. ст. */
    fun parseCuff(b: ByteArray): Int? {
        if (b.size < 3) return null
        val kpa = (b[0].toInt() and 0x01) != 0
        val v = sfloat(b, 1) ?: return null
        return (v * if (kpa) KPA_TO_MMHG else 1.0).roundToInt().takeIf { it in 0..320 }
    }

    /** Что умеет определять тонометр (0x2A49, Blood Pressure Feature). */
    fun parseFeature(b: ByteArray): List<String> {
        if (b.size < 2) return emptyList()
        val f = u16(b, 0)
        return buildList {
            if (f and 0x01 != 0) add("движение во время замера")
            if (f and 0x02 != 0) add("правильность надевания манжеты")
            if (f and 0x04 != 0) add("нерегулярный пульс (аритмия)")
            if (f and 0x08 != 0) add("пульс вне диапазона")
            if (f and 0x10 != 0) add("положение при замере")
            if (f and 0x20 != 0) add("несколько сопряжённых телефонов")
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** IEEE-11073 16-битное число: 12 бит мантисса, 4 бита степень десятки. */
    fun sfloat(b: ByteArray, i: Int): Double? {
        if (i + 2 > b.size) return null
        val raw = u16(b, i)
        if (raw == 0x07FF || raw == 0x0800 || raw == 0x07FE || raw == 0x0802 || raw == 0x0801) return null
        var mantissa = raw and 0x0FFF
        var exponent = raw shr 12
        if (mantissa >= 0x0800) mantissa -= 0x1000
        if (exponent >= 0x8) exponent -= 0x10
        return mantissa * 10.0.pow(exponent)
    }

    fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
