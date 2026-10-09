package ru.bpdiary.data

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
 * times — время приёма через запятую, например "08:00, 20:00".
 * startDay / endDay — LocalDate.toEpochDay(); endDay == null — принимается сейчас.
 */
@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dose: String,
    val times: String,
    val startDay: Long,
    val endDay: Long? = null,
    val note: String = "",
) {
    fun slots(): List<String> =
        times.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("—") }

    fun isActiveOn(day: Long): Boolean = day >= startDay && (endDay == null || day <= endDay)
}

/** Отметка «принял» для конкретного препарата, дня и слота времени. */
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
