package ru.bpdiary.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.bpdiary.ble.TonometerLink

@Composable
fun TonometerScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val state by TonometerLink.state.collectAsState()
    val found by TonometerLink.found.collectAsState()
    val scanning by TonometerLink.scanning.collectAsState()
    val log by TonometerLink.log.collectAsState()
    val last by TonometerLink.lastReading.collectAsState()
    val info by TonometerLink.info.collectAsState()
    var userFilter by remember { mutableIntStateOf(TonometerLink.userFilter()) }
    var showLog by remember { mutableStateOf(false) }
    // имя тонометра перечитываем при смене состояния (после выбора/отвязки)
    val deviceName = remember(state) { TonometerLink.deviceName() }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        TonometerLink.restart()
    }
    val askBtOn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        TonometerLink.restart()
    }

    Dialog(onDismissRequest = { TonometerLink.stopScan(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { TonometerLink.stopScan(); onClose() }) { Icon(Icons.Filled.Close, "Закрыть") }
                    Text("Тонометр по Bluetooth", style = MaterialTheme.typography.titleLarge)
                }
                HorizontalDivider()
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── состояние ──
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val (icon, tint) = when (state) {
                                    TonometerLink.State.CONNECTED -> Icons.Filled.BluetoothConnected to Color(0xFF2E7D32)
                                    TonometerLink.State.WAITING, TonometerLink.State.CONNECTING -> Icons.Filled.Bluetooth to MaterialTheme.colorScheme.primary
                                    else -> Icons.Filled.BluetoothDisabled to MaterialTheme.colorScheme.error
                                }
                                Icon(icon, null, tint = tint)
                                Text("  ${state.title}", fontWeight = FontWeight.SemiBold, color = tint)
                            }
                            deviceName?.let { Text("Тонометр: $it") }
                            listOfNotNull(info.manufacturer, info.model).takeIf { it.isNotEmpty() }?.let {
                                Text(it.joinToString(" "), style = MaterialTheme.typography.bodySmall)
                            }
                            info.battery?.let { Text("Батарея тонометра: $it%") }
                            info.features?.let { f ->
                                Text("Тонометр сообщает о: " + (listOf("давлении и пульсе") + f.filterNot { it.startsWith("несколько") }).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            last?.let { Text("Последний полученный замер: $it") }
                            when (state) {
                                TonometerLink.State.NO_PERMISSION -> Button(onClick = { askPerms.launch(TonometerLink.requiredPermissions()) }) {
                                    Text("Разрешить Bluetooth")
                                }
                                TonometerLink.State.BT_OFF -> Button(onClick = {
                                    runCatching { askBtOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                }) { Text("Включить Bluetooth") }
                                else -> {}
                            }
                            if (state == TonometerLink.State.WAITING || state == TonometerLink.State.CONNECTED) Text(
                                "Замеры принимаются только когда приложение открыто на экране и только те, что делаются " +
                                    "прямо сейчас: на экране бежит давление в манжете, затем результат сам записывается в дневник. " +
                                    "Старые замеры из памяти тонометра не переносятся. Пока идёт замер, экран не гаснет.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    // ── поиск ──
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (deviceName == null) "Подключить тонометр" else "Выбрать другой тонометр",
                                style = MaterialTheme.typography.titleMedium)
                            Text(
                                "1. Включите на тонометре Bluetooth и режим сопряжения (как описано в его инструкции — " +
                                    "обычно долгое нажатие кнопки, пока не замигает значок Bluetooth).\n" +
                                    "2. Нажмите «Найти». Если тонометр не появляется — сделайте замер: после него он тоже включает Bluetooth.\n" +
                                    "3. Выберите его в списке. Если телефон попросит подтвердить сопряжение — подтвердите.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(enabled = !scanning, onClick = {
                                    if (!TonometerLink.hasPermissions(ctx)) askPerms.launch(TonometerLink.requiredPermissions())
                                    else if (!TonometerLink.bluetoothOn()) runCatching { askBtOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                                    else TonometerLink.startScan()
                                }) { Text("Найти") }
                                if (scanning) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    TextButton(onClick = { TonometerLink.stopScan() }) { Text("Стоп") }
                                }
                            }
                            found.forEach { f ->
                                Card(
                                    Modifier.fillMaxWidth().clickable { TonometerLink.select(f) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (f.likelyBp) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(f.name, fontWeight = FontWeight.SemiBold)
                                        Text("${f.address} · сигнал ${f.rssi} дБм" + if (f.likelyBp) " · похоже на тонометр" else "",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (!scanning && found.isEmpty()) Text(
                                "Список пуст. На Android 11 и старше для поиска должно быть включено и «Местоположение».",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // ── пользователь тонометра ──
                    if (deviceName != null) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Чьи замеры принимать", style = MaterialTheme.typography.titleMedium)
                            Text("В тонометре две памяти (пользователь 1 и 2). Если вторым пользуется кто-то другой — " +
                                "выберите свой номер, и чужие замеры не попадут в ваш дневник.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0 to "Все", 1 to "№ 1", 2 to "№ 2").forEach { (v, label) ->
                                    FilterChip(selected = userFilter == v,
                                        onClick = { userFilter = v; TonometerLink.setUserFilter(v) },
                                        label = { Text(label) })
                                }
                            }
                            TextButton(onClick = { TonometerLink.forget() }) {
                                Text("Отвязать тонометр", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    // ── журнал ──
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Журнал связи", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Скрыть" else "Показать (${log.size})") }
                            }
                            Text("Если замеры не приходят — отправьте журнал разработчику: по нему видно, что передаёт тонометр.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = log.isNotEmpty(), onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Журнал тонометра")
                                        putExtra(Intent.EXTRA_TEXT, log.joinToString("\n"))
                                    }
                                    ctx.startActivity(Intent.createChooser(send, "Отправить журнал"))
                                }) { Text("Отправить журнал") }
                                TextButton(onClick = { TonometerLink.clearLog() }) { Text("Очистить") }
                            }
                            if (showLog) Text(
                                log.takeLast(150).joinToString("\n"),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
