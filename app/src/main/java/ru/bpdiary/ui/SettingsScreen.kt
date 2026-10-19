package ru.bpdiary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.bpdiary.analysis.BpNorms
import ru.bpdiary.data.AppSettings

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    val current by vm.target.collectAsState()
    var sys by remember { mutableStateOf(current.sys.toString()) }
    var dia by remember { mutableStateOf(current.dia.toString()) }
    val s = sys.toIntOrNull(); val d = dia.toIntOrNull()
    val error = when {
        s == null || d == null -> "Введите оба значения"
        s !in 100..160 -> "Верхнее — от 100 до 160"
        d !in 60..100 -> "Нижнее — от 60 до 100"
        d >= s -> "Нижнее должно быть меньше верхнего"
        else -> null
    }

    EditorDialog(
        title = "Настройки",
        saveEnabled = error == null,
        onDismiss = onClose,
        onSave = { vm.setTarget(AppSettings.Target(s!!, d!!)); onClose() },
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Цель давления", style = MaterialTheme.typography.titleMedium)
                Text("Замер считается «в цели», если он ниже этих значений. По цели строится зелёная линия " +
                    "на графике и считаются «в цели», «стабилизировалось» и сравнение месяцев.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetField("Верхнее", sys, Modifier.weight(1f)) { sys = it }
                    Text("/", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))
                    TargetField("Нижнее", dia, Modifier.weight(1f)) { dia = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(BpNorms.DEFAULT_TARGET_SYS to BpNorms.DEFAULT_TARGET_DIA, 140 to 90).forEach { (a, b) ->
                        FilterChip(
                            selected = s == a && d == b,
                            onClick = { sys = a.toString(); dia = b.toString() },
                            label = { Text("$a/$b") },
                        )
                    }
                }
                Text(
                    "130/80 — по умолчанию. Клинические рекомендации Минздрава РФ (2024) и ESC (2024) советуют " +
                        "при хорошей переносимости снижать давление до 120–129 / 70–79.\n" +
                        "140/90 — первый этап для 65–79 лет; потом, если переносится, — ниже 130.\n" +
                        "Если врач назначил свою цель — впишите его цифры.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TargetField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}
