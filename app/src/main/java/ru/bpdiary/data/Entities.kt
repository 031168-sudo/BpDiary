package ru.bpdiary.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Один замер давления. timestamp — epoch millis. */
@Entity(tableName = "measurements", indices = [Index("timestamp")])
data class Measurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int? = null,
    /** "Л" / "П" / null */
    val arm: String? = null,
    /** Тонометр показал нерегулярный пульс (аритмию) */
    val irregular: Boolean = false,
    val note: String = "",
)

/**
 * Препарат в схеме лечения.
 * perDay — сколько раз в день принимать.
 * times — устаревшее поле (раньше: время приёма через запятую), оставлено для совместимости старых данных.
 * startDay / endDay — LocalDate.toEpochDay(); endDay == null — принимается сейчас.
 */
@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dose: String,
    val times: String = "",
    val startDay: Long,
    val endDay: Long? = null,
    val note: String = "",
    @ColumnInfo(defaultValue = "1") val perDay: Int = 1,
) {
    fun isActiveOn(day: Long): Boolean = day >= startDay && (endDay == null || day <= endDay)

    /** Отметки приёма этого препарата за день, по времени. */
    fun logsOn(logs: List<DoseLog>, day: Long) =
        logs.filter { it.medicationId == id && it.day == day }.sortedBy { it.takenAt }

    /** Свободный номер для новой отметки за день. */
    fun nextSlot(logs: List<DoseLog>, day: Long): String {
        val used = logs.filter { it.medicationId == id && it.day == day }.map { it.slot }.toSet()
        return generateSequence(1) { it + 1 }.map { "#$it" }.first { it !in used }
    }

    fun perDayText() = perDayText(perDay)
}

fun perDayText(n: Int) = when {
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n раза в день"
    else -> "$n раз в день"
}

/** Отметка «принял»: препарат, день, номер приёма за день (slot) и фактическое время приёма (takenAt). */
@Entity(
    tableName = "dose_logs",
    indices = [Index(value = ["medicationId", "day", "slot"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = Medication::class,
        parentColumns = ["id"],
        childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class DoseLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val day: Long,
    val slot: String,
    val takenAt: Long,
)
