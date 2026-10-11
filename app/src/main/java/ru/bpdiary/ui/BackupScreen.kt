package ru.bpdiary.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import ru.bpdiary.data.BackupCodec
import ru.bpdiary.data.Snapshot
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val GOOD = Color(0xFF2E7D32)
private val WHEN_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", RU)

private fun fmt(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(WHEN_FMT)

@Composable
fun BackupScreen(vm: MainViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by vm.backupStatus.collectAsState()
    var pendingRestore by remember { mutableStateOf<Snapshot?>(null) }
    var currentSummary by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun askRestore(s: Snapshot) = scope.launch {
        currentSummary = vm.currentSnapshot().let { if (it.isEmpty) "" else it.summary() }
        pendingRestore = s
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val found = vm.chooseBackupFolder(uri)
                if (found != null) askRestore(found) else toast("Папка выбрана, копия сохранена")
            } catch (e: Exception) { toast("Не удалось выбрать папку: ${e.message}") }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try { val s = vm.exportTo(uri); toast("Сохранено: ${s.summary()}") }
            catch (e: Exception) { toast("Не удалось сохранить: ${e.message}") }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try { askRestore(vm.readBackup(uri)) }
            catch (e: Exception) { toast(e.message ?: "Не удалось прочитать файл") }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Закрыть") }
                    Text("Резервная копия", style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── автокопия ──
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Автоматическая копия на телефоне", style = MaterialTheme.typography.titleMedium)
                            if (status.folder == null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    Text("  Не настроена", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                                }
                                Text("Выберите папку, и приложение будет само сохранять туда копию после каждого изменения. " +
                                    "Файлы в этой папке не удаляются вместе с приложением.")
                                Text("Совет: в окне выбора откройте «Документы» (Documents), создайте папку «Давление» и выберите её.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.CheckCircle, null, tint = GOOD, modifier = Modifier.size(18.dp))
                                    Text("  Включена · папка «${status.folder}»", color = GOOD, fontWeight = FontWeight.SemiBold)
                                }
                                Text(if (status.last > 0) "Последняя копия: ${fmt(status.last)}" else "Копий ещё не было")
                                status.error?.let { Text("Ошибка: $it", color = MaterialTheme.colorScheme.error) }
                                Text("В папке: davlenie-latest.json (самая свежая) и по копии на каждый день за последние 30 дней.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pickFolder.launch(null) }) {
                                    Text(if (status.folder == null) "Выбрать папку" else "Сменить папку")
                                }
                                if (status.folder != null) OutlinedButton(enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        val ok = vm.backupNow(); busy = false
                                        toast(if (ok) "Копия сохранена" else "Нечего сохранять или нет доступа к папке")
                                    }
                                }) { Text("Копия сейчас") }
                            }
                        }
                    }

                    // ── перенос ──
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Перенос на другой телефон", style = MaterialTheme.typography.titleMedium)
                            Text("Отправьте файл копии себе (в Телеграм, на почту) или сохраните на диск. " +
                                "На новом телефоне установите приложение и нажмите «Восстановить из файла».")
                            Button(onClick = {
                                scope.launch {
                                    try {
                                        val s = vm.currentSnapshot()
                                        if (s.isEmpty) { toast("Пока нечего отправлять"); return@launch }
                                        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
                                        val f = File(dir, "davlenie-${LocalDate.now()}.json")
                                        f.writeText(BackupCodec.encode(s))
                                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(Intent.createChooser(send, "Отправить копию"))
                                    } catch (e: Exception) { toast("Не удалось: ${e.message}") }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Отправить копию…") }
                            OutlinedButton(onClick = { exportFile.launch("davlenie-${LocalDate.now()}.json") },
                                modifier = Modifier.fillMaxWidth()) { Text("Сохранить копию в файл…") }
                            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth()) { Text("Восстановить из файла…") }
                        }
                    }

                    // ── защита при удалении ──
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Если приложение удалили случайно", style = MaterialTheme.typography.titleMedium)
                            Text("При удалении Android (10 и новее) спросит, сохранить ли данные приложения — отметьте «Сохранить». " +
                                "Тогда после повторной установки всё вернётся само.")
                            Text("Если данные всё же стёрлись — установите приложение, откройте этот экран, нажмите «Выбрать папку» " +
                                "и укажите ту же папку: приложение найдёт копию и предложит восстановить.")
                        }
                    }
                }
            }
        }
    }

    pendingRestore?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Восстановить данные?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.createdAt > 0) Text("Копия от ${fmt(s.createdAt)}")
                    Text("В копии: ${s.summary()}.")
                    if (currentSummary.isNotEmpty()) Text(
                        "Сейчас в приложении: $currentSummary — эти данные будут заменены копией.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        try { vm.restore(s); toast("Данные восстановлены"); onClose() }
                        catch (e: Exception) { toast("Не удалось восстановить: ${e.message}") }
                    }
                }) { Text("Восстановить") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Отмена") } },
        )
    }
}
