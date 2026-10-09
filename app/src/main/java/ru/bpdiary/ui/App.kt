package ru.bpdiary.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

val RU: Locale = Locale.forLanguageTag("ru")
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun BpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFEF9A9A))
        else -> lightColorScheme(primary = Color(0xFFC62828))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private enum class Tab(val title: String, val icon: ImageVector) {
    DIARY("Дневник", Icons.AutoMirrored.Filled.ViewList),
    CHARTS("Графики", Icons.AutoMirrored.Filled.ShowChart),
    ANALYSIS("Анализ", Icons.Filled.Analytics),
    MEDS("Лекарства", Icons.Filled.Medication),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    BpTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val current = Tab.entries[tab]
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (current == Tab.DIARY) "Давление" else current.title) }) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = i == tab, onClick = { tab = i },
                            icon = { Icon(t.icon, null) }, label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when (current) {
                Tab.DIARY -> DiaryScreen(vm, m)
                Tab.CHARTS -> ChartsScreen(vm, m)
                Tab.ANALYSIS -> AnalysisScreen(vm, m)
                Tab.MEDS -> MedicationsScreen(vm, m)
            }
        }
    }
}

// ── системные диалоги выбора даты/времени ──
fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(ctx, { _, y, mo, d -> onPick(LocalDate.of(y, mo + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
}

fun pickTime(ctx: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, mi -> onPick(LocalTime.of(h, mi)) }, initial.hour, initial.minute, true).show()
}
