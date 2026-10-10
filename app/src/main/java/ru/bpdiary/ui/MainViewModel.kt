package ru.bpdiary.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bpdiary.analysis.AnalysisResult
import ru.bpdiary.analysis.Analyzer
import ru.bpdiary.data.AppDatabase
import ru.bpdiary.data.DoseLog
import ru.bpdiary.data.Measurement
import ru.bpdiary.data.Medication
import ru.bpdiary.report.ReportPdf
import java.time.LocalDate

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val measurements: StateFlow<List<Measurement>> =
        db.measurements().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val medications: StateFlow<List<Medication>> =
        db.medications().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val doseLogs: StateFlow<List<DoseLog>> =
        db.doseLogs().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val analysis: StateFlow<AnalysisResult?> =
        combine(measurements, medications, doseLogs) { m, md, l -> Analyzer.analyze(m, md, l, LocalDate.now()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveMeasurement(m: Measurement) = viewModelScope.launch { db.measurements().upsert(m) }
    fun deleteMeasurement(m: Measurement) = viewModelScope.launch { db.measurements().delete(m) }

    fun saveMedication(m: Medication) = viewModelScope.launch { db.medications().upsert(m) }
    fun deleteMedication(m: Medication) = viewModelScope.launch { db.medications().delete(m) }

    /** Отметить приём с конкретным временем (или изменить время уже отмеченного). */
    fun setDoseTime(med: Medication, day: Long, slot: String, takenAt: Long) = viewModelScope.launch {
        db.doseLogs().replace(DoseLog(medicationId = med.id, day = day, slot = slot, takenAt = takenAt))
    }

    fun clearDose(med: Medication, day: Long, slot: String) = viewModelScope.launch {
        db.doseLogs().remove(med.id, day, slot)
    }

    suspend fun buildReport(from: LocalDate, to: LocalDate): Uri = withContext(Dispatchers.Default) {
        val a = Analyzer.analyze(measurements.value, medications.value, doseLogs.value, LocalDate.now())
        ReportPdf.create(getApplication<Application>(), from, to, measurements.value, medications.value, doseLogs.value, a)
    }
}
