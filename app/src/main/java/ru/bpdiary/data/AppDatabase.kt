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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<Measurement>>

    @Upsert
    suspend fun upsert(m: Measurement)

    @Delete
    suspend fun delete(m: Measurement)

    @Query("SELECT * FROM measurements ORDER BY timestamp")
    suspend fun all(): List<Measurement>

    @Query("SELECT COUNT(*) FROM measurements WHERE systolic = :sys AND diastolic = :dia AND timestamp BETWEEN :from AND :to")
    suspend fun countSimilar(sys: Int, dia: Int, from: Long, to: Long): Int

    @Insert
    suspend fun insertAll(list: List<Measurement>)

    @Query("DELETE FROM measurements")
    suspend fun clear()
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications ORDER BY (endDay IS NOT NULL), startDay DESC")
    fun observeAll(): Flow<List<Medication>>

    @Upsert
    suspend fun upsert(m: Medication)

    @Delete
    suspend fun delete(m: Medication)

    @Query("SELECT * FROM medications ORDER BY id")
    suspend fun all(): List<Medication>

    @Insert
    suspend fun insertAll(list: List<Medication>)

    @Query("DELETE FROM medications")
    suspend fun clear()
}

@Dao
abstract class DoseLogDao {
    @Query("SELECT * FROM dose_logs")
    abstract fun observeAll(): Flow<List<DoseLog>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(log: DoseLog)

    @Query("DELETE FROM dose_logs WHERE medicationId = :medId AND day = :day AND slot = :slot")
    abstract suspend fun remove(medId: Long, day: Long, slot: String)

    @Query("DELETE FROM dose_logs WHERE id = :id")
    abstract suspend fun removeById(id: Long)

    @Query("UPDATE dose_logs SET takenAt = :takenAt WHERE id = :id")
    abstract suspend fun updateTakenAt(id: Long, takenAt: Long)

    @Query("SELECT * FROM dose_logs ORDER BY day, slot")
    abstract suspend fun all(): List<DoseLog>

    @Insert
    abstract suspend fun insertAll(list: List<DoseLog>)

    @Query("DELETE FROM dose_logs")
    abstract suspend fun clear()

    /** Поставить отметку или поменять время уже поставленной. */
    @Transaction
    open suspend fun replace(log: DoseLog) {
        log.medicationId?.let { remove(it, log.day, log.slot) }
        insert(log)
    }
}

@Database(
    entities = [Measurement::class, Medication::class, DoseLog::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
    abstract fun medications(): MedicationDao
    abstract fun doseLogs(): DoseLogDao

    companion object {
        /**
         * v1 → v2: вместо времени приёма — «сколько раз в день».
         * Число приёмов берём из старого списка времени («08:00, 20:00» → 2). Остальные данные не трогаются.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medications ADD COLUMN perDay INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "UPDATE medications SET perDay = " +
                        "max(1, length(trim(times)) - length(replace(trim(times), ',', '')) + 1) " +
                        "WHERE trim(times) <> ''"
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        /**
         * v2 → v3: приём может быть «вне схемы» — препарат не заводится,
         * название хранится в freeName. Поэтому medicationId становится необязательным,
         * а таблицу приходится пересоздавать (SQLite не умеет снимать NOT NULL).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dose_logs_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`medicationId` INTEGER, " +
                        "`day` INTEGER NOT NULL, " +
                        "`slot` TEXT NOT NULL, " +
                        "`takenAt` INTEGER NOT NULL, " +
                        "`freeName` TEXT NOT NULL, " +
                        "FOREIGN KEY(`medicationId`) REFERENCES `medications`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `dose_logs_new` (`id`, `medicationId`, `day`, `slot`, `takenAt`, `freeName`) " +
                        "SELECT `id`, `medicationId`, `day`, `slot`, `takenAt`, '' FROM `dose_logs`"
                )
                db.execSQL("DROP TABLE `dose_logs`")
                db.execSQL("ALTER TABLE `dose_logs_new` RENAME TO `dose_logs`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dose_logs_medicationId_day_slot` " +
                        "ON `dose_logs` (`medicationId`, `day`, `slot`)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "bp_diary.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
