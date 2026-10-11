package ru.bpdiary.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Все данные приложения в одном файле JSON — для переноса на новый телефон и защиты от удаления. */
data class Snapshot(
    val measurements: List<Measurement>,
    val medications: List<Medication>,
    val doseLogs: List<DoseLog>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isEmpty get() = measurements.isEmpty() && medications.isEmpty() && doseLogs.isEmpty()
    fun summary() = "замеров: ${measurements.size}, лекарств: ${medications.size}, отметок приёма: ${doseLogs.size}"
}

class BackupFormatException(msg: String) : Exception(msg)

object BackupCodec {
    private const val APP = "ru.bpdiary"
    private const val FORMAT = 1

    fun encode(s: Snapshot): String {
        val root = JSONObject()
        root.put("app", APP)
        root.put("format", FORMAT)
        root.put("createdAt", s.createdAt)
        root.put("measurements", JSONArray().apply {
            s.measurements.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id); put("timestamp", m.timestamp)
                    put("systolic", m.systolic); put("diastolic", m.diastolic)
                    put("pulse", m.pulse ?: JSONObject.NULL)
                    put("arm", m.arm ?: JSONObject.NULL)
                    put("irregular", m.irregular); put("note", m.note)
                })
            }
        })
        root.put("medications", JSONArray().apply {
            s.medications.forEach { m ->
                put(JSONObject().apply {
                    put("id", m.id); put("name", m.name); put("dose", m.dose); put("times", m.times)
                    put("startDay", m.startDay); put("endDay", m.endDay ?: JSONObject.NULL); put("note", m.note)
                })
            }
        })
        root.put("doseLogs", JSONArray().apply {
            s.doseLogs.forEach { l ->
                put(JSONObject().apply {
                    put("medicationId", l.medicationId); put("day", l.day); put("slot", l.slot); put("takenAt", l.takenAt)
                })
            }
        })
        return root.toString(1)
    }

    fun decode(text: String): Snapshot {
        val root = try { JSONObject(text) } catch (e: Exception) {
            throw BackupFormatException("Файл повреждён или это не резервная копия")
        }
        if (root.optString("app") != APP) throw BackupFormatException("Это не резервная копия приложения «Давление»")
        if (root.optInt("format") > FORMAT) throw BackupFormatException("Копия сделана более новой версией приложения — сначала обновите приложение")

        fun JSONObject.optIntOrNull(k: String) = if (isNull(k)) null else getInt(k)
        fun JSONObject.optLongOrNull(k: String) = if (isNull(k)) null else getLong(k)
        fun JSONObject.optStrOrNull(k: String) = if (isNull(k)) null else getString(k)
        fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        val ms = root.optJSONArray("measurements")?.objects().orEmpty().map { o ->
            Measurement(
                id = o.getLong("id"), timestamp = o.getLong("timestamp"),
                systolic = o.getInt("systolic"), diastolic = o.getInt("diastolic"),
                pulse = o.optIntOrNull("pulse"), arm = o.optStrOrNull("arm"),
                irregular = o.optBoolean("irregular"), note = o.optString("note"),
            )
        }
        val meds = root.optJSONArray("medications")?.objects().orEmpty().map { o ->
            Medication(
                id = o.getLong("id"), name = o.getString("name"), dose = o.optString("dose"),
                times = o.optString("times"), startDay = o.getLong("startDay"),
                endDay = o.optLongOrNull("endDay"), note = o.optString("note"),
            )
        }
        val medIds = meds.map { it.id }.toSet()
        val logs = root.optJSONArray("doseLogs")?.objects().orEmpty().map { o ->
            DoseLog(medicationId = o.getLong("medicationId"), day = o.getLong("day"),
                slot = o.getString("slot"), takenAt = o.getLong("takenAt"))
        }.filter { it.medicationId in medIds }.distinctBy { Triple(it.medicationId, it.day, it.slot) }
        return Snapshot(ms, meds, logs, root.optLong("createdAt", 0))
    }
}

object BackupRepo {
    suspend fun snapshot(db: AppDatabase) = db.withTransaction {
        Snapshot(db.measurements().all(), db.medications().all(), db.doseLogs().all())
    }

    /** Полная замена данных содержимым копии — всё или ничего. */
    suspend fun restore(db: AppDatabase, s: Snapshot) = db.withTransaction {
        db.doseLogs().clear()
        db.medications().clear()
        db.measurements().clear()
        db.measurements().insertAll(s.measurements)
        db.medications().insertAll(s.medications)
        db.doseLogs().insertAll(s.doseLogs)
    }

    fun write(ctx: Context, uri: Uri, s: Snapshot) {
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(BackupCodec.encode(s).toByteArray()) }
    }

    fun read(ctx: Context, uri: Uri): Snapshot {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw BackupFormatException("Не удалось открыть файл")
        return BackupCodec.decode(text)
    }
}

/**
 * Автоматическая копия в папку, которую пользователь выбрал один раз.
 * Файлы лежат в обычной папке телефона и не удаляются вместе с приложением:
 *   davlenie-latest.json        — всегда самая свежая копия;
 *   davlenie-ГГГГ-ММ-ДД.json    — по одной на день, хранятся последние 30.
 */
object AutoBackup {
    private const val PREFS = "backup"
    private const val KEY_TREE = "tree"
    private const val KEY_LAST = "last"
    private const val KEY_ERROR = "error"
    const val LATEST = "davlenie-latest.json"
    private const val KEEP_DAILY = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folder(ctx: Context): Uri? = prefs(ctx).getString(KEY_TREE, null)?.let(Uri::parse)
    fun lastBackup(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST, 0)
    fun lastError(ctx: Context): String? = prefs(ctx).getString(KEY_ERROR, null)

    fun folderName(ctx: Context): String? =
        folder(ctx)?.let { DocumentFile.fromTreeUri(ctx, it)?.name }

    fun setFolder(ctx: Context, uri: Uri?) {
        val old = folder(ctx)
        if (old != null && old != uri) runCatching {
            ctx.contentResolver.releasePersistableUriPermission(old,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (uri != null) ctx.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs(ctx).edit().putString(KEY_TREE, uri?.toString()).remove(KEY_ERROR).apply()
    }

    /** Копия, которая уже лежит в выбранной папке (для восстановления на новом телефоне). */
    fun findExisting(ctx: Context): Pair<Uri, Snapshot>? {
        val dir = folder(ctx)?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return null
        val f = dir.findFile(LATEST) ?: dir.listFiles()
            .filter { it.name?.matches(Regex("""davlenie-\d{4}-\d{2}-\d{2}\.json""")) == true }
            .maxByOrNull { it.name!! } ?: return null
        return runCatching { f.uri to BackupRepo.read(ctx, f.uri) }.getOrNull()
    }

    /** Страховочная копия текущих данных перед восстановлением из файла. */
    fun saveBeforeRestore(ctx: Context, s: Snapshot) {
        if (s.isEmpty) return
        val dir = folder(ctx)?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return
        runCatching {
            val name = "davlenie-before-restore.json"
            val uri = dir.findFile(name)?.uri ?: dir.createFile("application/json", name)?.uri ?: return
            BackupRepo.write(ctx, uri, s)
        }
    }

    /**
     * Записывает копию. Пустую базу НЕ записывает никогда — чтобы после переустановки
     * пустое приложение не затёрло хорошую копию.
     */
    fun run(ctx: Context, s: Snapshot): Boolean {
        if (s.isEmpty) return false
        val dir = folder(ctx)?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return false
        return try {
            if (!dir.canWrite()) throw IllegalStateException("Нет доступа к папке — выберите её заново")
            fun fileFor(name: String): Uri {
                dir.findFile(name)?.let { return it.uri }
                val created = dir.createFile("application/json", name)
                    ?: throw IllegalStateException("Не удалось создать файл в папке")
                return created.uri
            }
            // сначала дневная копия, потом «последняя» — если запись оборвётся, одна из них цела
            BackupRepo.write(ctx, fileFor("davlenie-${LocalDate.now()}.json"), s)
            BackupRepo.write(ctx, fileFor(LATEST), s)
            dir.listFiles()
                .filter { it.name?.matches(Regex("""davlenie-\d{4}-\d{2}-\d{2}\.json""")) == true }
                .sortedByDescending { it.name }
                .drop(KEEP_DAILY)
                .forEach { it.delete() }
            prefs(ctx).edit().putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR).apply()
            true
        } catch (e: Exception) {
            prefs(ctx).edit().putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).apply()
            false
        }
    }
}
