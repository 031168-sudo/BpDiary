package ru.bpdiary.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<Measurement>>

    @Upsert
    suspend fun upsert(m: Measurement)

    @Delete
    suspend fun delete(m: Measurement)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications ORDER BY (endDay IS NOT NULL), startDay DESC")
    fun observeAll(): Flow<List<Medication>>

    @Upsert
    suspend fun upsert(m: Medication)

    @Delete
    suspend fun delete(m: Medication)
}

@Dao
abstract class DoseLogDao {
    @Query("SELECT * FROM dose_logs")
    abstract fun observeAll(): Flow<List<DoseLog>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(log: DoseLog)

    @Query("DELETE FROM dose_logs WHERE medicationId = :medId AND day = :day AND slot = :slot")
    abstract suspend fun remove(medId: Long, day: Long, slot: String)

    /** Поставить отметку или поменять время уже поставленной. */
    @Transaction
    open suspend fun replace(log: DoseLog) {
        remove(log.medicationId, log.day, log.slot)
        insert(log)
    }
}

@Database(
    entities = [Measurement::class, Medication::class, DoseLog::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
    abstract fun medications(): MedicationDao
    abstract fun doseLogs(): DoseLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "bp_diary.db"
                ).build().also { instance = it }
            }
    }
}
