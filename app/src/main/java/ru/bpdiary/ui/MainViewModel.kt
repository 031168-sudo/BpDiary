package ru.bpdiary.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.bpdiary.analysis.AnalysisResult
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.data.AppDatabase
import ru.bpdiary.data.AutoBackup
import ru.bpdiary.data.BackupRepo
import ru.bpdiary.data.Snapshot
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Measurement
import ru.bpdiary.data.Medication
import ru.bpdiary.report.ReportPdf
import java.time.LocalDate

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val measurements: StateFlow<List<Measurement>> =
        db.measurements().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val medications: StateFlow<List<Medication>> =
        db.medications().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val doseLogs: StateFlow<List<DoseLog>> =
        db.doseLogs().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── резервное копирование ──
    data class BackupStatus(val folder: String?, val last: Long, val error: String?)

    private val _backup = MutableStateFlow(readBackupStatus())
    val backupStatus: StateFlow<BackupStatus> = _backup

    private fun readBackupStatus() = getApplication<Application>().let {
        BackupStatus(AutoBackup.folderName(it), AutoBackup.lastBackup(it), AutoBackup.lastError(it))
    }

    init {
        // После любого изменения данных (с паузой 2 с) — копия в выбранную папку.
        viewModelScope.launch {
            combine(db.measurements().observeAll(), db.medications().observeAll(), db.doseLogs().observeAll()) { _, _, _ -> }
                .debounce(2000)
                .collect { backupNow() }
        }
    }

    suspend fun backupNow(): Boolean = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        if (AutoBackup.folder(ctx) == null) return@withContext false
        val ok = AutoBackup.run(ctx, BackupRepo.snapshot(db))
        _backup.value = readBackupStatus()
        ok
    }

    /** Выбрана папка. Если база пустая, а в папке уже есть копия — вернуть её, чтобы предложить восстановить. */
    suspend fun chooseBackupFolder(uri: Uri?): Snapshot? = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        AutoBackup.setFolder(ctx, uri)
        _backup.value = readBackupStatus()
        if (uri == null) return@withContext null
        val current = BackupRepo.snapshot(db)
        if (current.isEmpty) AutoBackup.findExisting(ctx)?.second
        else { backupNow(); null }
    }

    suspend fun exportTo(uri: Uri): Snapshot = withContext(Dispatchers.IO) {
        val s = BackupRepo.snapshot(db)
        BackupRepo.write(getApplication(), uri, s)
        s
    }

    suspend fun readBackup(uri: Uri): Snapshot = withContext(Dispatchers.IO) { BackupRepo.read(getApplication(), uri) }

    suspend fun currentSnapshot(): Snapshot = withContext(Dispatchers.IO) { BackupRepo.snapshot(db) }

    suspend fun restore(s: Snapshot) = withContext(Dispatchers.IO) {
        AutoBackup.saveBeforeRestore(getApplication(), BackupRepo.snapshot(db))
        BackupRepo.restore(db, s)
    }

    val analysis: StateFlow<AnalysisResult?> =
        combine(measurements, medications, doseLogs) { m, md, l -> Analyzer.analyze(m, md, l, LocalDate.now()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveMeasurement(m: Measurement) = viewModelScope.launch { db.measurements().upsert(m) }
    fun deleteMeasurement(m: Measurement) = viewModelScope.launch { db.measurements().delete(m) }

    fun saveMedication(m: Medication) = viewModelScope.launch { db.medications().upsert(m) }
    fun deleteMedication(m: Medication) = viewModelScope.launch { db.medications().delete(m) }

    /** Новая отметка приёма за день с фактическим временем. */
    private val doseLock = Mutex()

    fun addDose(med: Medication, day: Long, takenAt: Long) = viewModelScope.launch {
        doseLock.withLock {
            val slot = med.nextSlot(db.doseLogs().all(), day)
            db.doseLogs().insert(DoseLog(medicationId = med.id, day = day, slot = slot, takenAt = takenAt))
        }
    }

    /** Поменять время уже отмеченного приёма. */
    fun editDose(log: DoseLog, takenAt: Long) = viewModelScope.launch {
        db.doseLogs().replace(log.copy(id = 0, takenAt = takenAt))
    }

    fun deleteDose(log: DoseLog) = viewModelScope.launch {
        db.doseLogs().remove(log.medicationId, log.day, log.slot)
    }

    suspend fun buildReport(from: LocalDate, to: LocalDate): Uri = withContext(Dispatchers.Default) {
        val a = Analyzer.analyze(measurements.value, medications.value, doseLogs.value, LocalDate.now())
        ReportPdf.create(getApplication<Application>(), from, to, measurements.value, medications.value, doseLogs.value, a)
    }
}
