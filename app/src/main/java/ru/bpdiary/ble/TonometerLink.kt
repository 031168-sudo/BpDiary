package ru.bpdiary.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.bpdiary.data.AppDatabase
import ru.bpdiary.data.Measurement
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Связь с тонометром по Bluetooth Low Energy.
 *
 * Тонометр включает Bluetooth на несколько секунд после замера. Приложение держит «ожидающее»
 * подключение (autoConnect) к выбранному тонометру, поэтому как только он появится — Android
 * подключится сам, приложение включит приём и сохранит присланные замеры в дневник.
 *
 * Если тонометр работает по стандартному профилю Bluetooth для тонометров (Blood Pressure Service),
 * замеры разбираются автоматически. Если нет — всё, что он присылает, пишется в журнал,
 * чтобы по нему можно было дописать поддержку.
 */
@SuppressLint("MissingPermission")
object TonometerLink {
    private fun uuid16(x: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(x))
    private val BPS = uuid16(0x1810)
    private val BP_MEASUREMENT = uuid16(0x2A35)
    private val CTS = uuid16(0x1805)
    private val CURRENT_TIME = uuid16(0x2A2B)
    private val CCCD = uuid16(0x2902)
    private val CUFF = uuid16(0x2A36)
    private val BP_FEATURE = uuid16(0x2A49)
    private val BATTERY = uuid16(0x2A19)
    private val MANUFACTURER = uuid16(0x2A29)
    private val MODEL = uuid16(0x2A24)
    private val FIRMWARE = uuid16(0x2A26)
    /** Фирменные службы Rossmax — слушаем их для журнала (там могут быть, например, данные о мерцательной аритмии). */
    private val VENDOR_SERVICES = setOf(uuid16(0xFDF2), uuid16(0x1210))

    enum class State(val title: String) {
        NO_DEVICE("Тонометр не выбран"),
        NO_PERMISSION("Нет разрешения на Bluetooth"),
        BT_OFF("Bluetooth выключен"),
        WAITING("Жду тонометр — сделайте замер"),
        CONNECTING("Подключаюсь…"),
        CONNECTED("Тонометр подключён"),
    }

    data class Found(val name: String, val address: String, val rssi: Int, val likelyBp: Boolean)

    private const val PREFS = "tonometer"
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: Context

    private val _state = MutableStateFlow(State.NO_DEVICE)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _lastReading = MutableStateFlow<String?>(null)
    val lastReading: StateFlow<String?> = _lastReading

    /** Окно «идёт замер»: давление в манжете в реальном времени, затем результат. */
    data class Live(
        val cuff: Int? = null,
        val result: BleReading? = null,
        val aborted: Boolean = false,
        val history: List<Int> = emptyList(),
    )
    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live
    fun dismissLive() { main.removeCallbacks(abortLive); _live.value = null }
    private val abortLive = Runnable {
        val l = _live.value
        if (l != null && l.result == null) _live.value = l.copy(aborted = true)
    }

    data class Info(
        val manufacturer: String? = null, val model: String? = null, val firmware: String? = null,
        val battery: Int? = null, val features: List<String>? = null,
    )
    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info

    private var gatt: BluetoothGatt? = null
    private val ops = ArrayDeque<() -> Unit>()
    private var standardProfile = false

    // ── настройки ──
    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun deviceAddress(): String? = prefs().getString("address", null)
    fun deviceName(): String? = prefs().getString("name", null)
    /** 0 — принимать замеры всех пользователей тонометра, 1 или 2 — только этого. */
    fun userFilter(): Int = prefs().getInt("user", 0)
    fun setUserFilter(u: Int) = prefs().edit().putInt("user", u).apply()

    // ── журнал ──
    private val tf = DateTimeFormatter.ofPattern("HH:mm:ss")
    fun log(msg: String) {
        val line = "${LocalTime.now().format(tf)}  $msg"
        _log.value = (_log.value + line).takeLast(400)
    }
    fun clearLog() { _log.value = emptyList() }

    // ── разрешения и адаптер ──
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(ctx: Context = app) = requiredPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun adapter(): BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter

    fun bluetoothOn() = adapter()?.isEnabled == true

    // ── запуск ──
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        restart()
    }

    /** Пересчитать состояние и, если всё готово, начать ждать тонометр. */
    fun restart() {
        if (!::app.isInitialized) return
        val addr = deviceAddress()
        when {
            addr == null -> { closeGatt(); _state.value = State.NO_DEVICE }
            !hasPermissions() -> { closeGatt(); _state.value = State.NO_PERMISSION }
            !bluetoothOn() -> { closeGatt(); _state.value = State.BT_OFF }
            gatt == null -> connect(addr)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    log("Bluetooth: " + if (bluetoothOn()) "включён" else "выключен")
                    closeGatt(); restart()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (dev?.address != deviceAddress()) return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDING -> log("Сопряжение…")
                        BluetoothDevice.BOND_BONDED -> {
                            log("Сопряжение выполнено")
                            main.postDelayed({ gatt?.discoverServices() }, 500)
                        }
                        BluetoothDevice.BOND_NONE -> log("Сопряжения нет")
                    }
                }
            }
        }
    }

    // ── поиск ──
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.scanRecord?.deviceName ?: r.device.name ?: return
            val uuids = r.scanRecord?.serviceUuids.orEmpty()
            val likely = uuids.contains(ParcelUuid(BPS)) ||
                Regex("rossmax|z5|bpm|bp|blood", RegexOption.IGNORE_CASE).containsMatchIn(name)
            val f = Found(name, r.device.address, r.rssi, likely)
            val list = _found.value.filter { it.address != f.address } + f
            _found.value = list.sortedWith(compareByDescending<Found> { it.likelyBp }.thenByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) { log("Ошибка поиска: $errorCode"); _scanning.value = false }
    }

    fun startScan() {
        if (!hasPermissions() || !bluetoothOn()) { restart(); return }
        val scanner = adapter()?.bluetoothLeScanner ?: return
        _found.value = emptyList()
        _scanning.value = true
        log("Поиск устройств…")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        main.postDelayed({ stopScan() }, 25_000)
    }

    fun stopScan() {
        if (!_scanning.value) return
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCb) }
        _scanning.value = false
        log("Поиск завершён, найдено: ${_found.value.size}")
    }

    fun select(f: Found) {
        stopScan()
        prefs().edit().putString("address", f.address).putString("name", f.name).apply()
        log("Выбран тонометр: ${f.name} (${f.address})")
        closeGatt()
        restart()
    }

    fun forget() {
        closeGatt()
        prefs().edit().remove("address").remove("name").apply()
        _state.value = State.NO_DEVICE
        log("Тонометр отвязан")
    }

    // ── подключение ──
    private fun connect(addr: String) {
        val dev = adapter()?.getRemoteDevice(addr) ?: return
        _state.value = State.WAITING
        log("Жду тонометр ${deviceName() ?: addr}")
        gatt = dev.connectGatt(app, true, cb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        ops.clear()
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
    }

    private fun nextOp() { main.post { ops.removeFirstOrNull()?.invoke() } }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = State.CONNECTED
                log("Подключено (статус $status), ищу службы…")
                main.postDelayed({ g.discoverServices() }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Отключено (статус $status)")
                ops.clear()
                main.post {
                    if (gatt !== g) return@post
                    if (status == 133 || status == 8 || status == 62) {
                        // «зависшее» соединение — пересоздаём
                        closeGatt(); main.postDelayed({ restart() }, 1500)
                    } else {
                        _state.value = State.WAITING
                        g.connect() // снова ждём следующего замера
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { servicesReady(g, status) }
        }

        private fun servicesReady(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("Не удалось получить службы: $status"); return }
            ops.clear()
            g.services.forEach { s ->
                log("Служба ${short(s.uuid)}")
                s.characteristics.forEach { c -> log("   хар-ка ${short(c.uuid)} [${props(c.properties)}]") }
            }
            // синхронизация часов тонометра, если он это умеет
            g.getService(CTS)?.getCharacteristic(CURRENT_TIME)?.let { c ->
                if (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    ops += { writeChar(g, c, currentTimeBytes()); log("Отправляю время в тонометр") }
            }
            val bp = g.getService(BPS)?.getCharacteristic(BP_MEASUREMENT)
            standardProfile = bp != null
            if (bp != null) {
                log("Стандартный профиль тонометра найден")
                // сведения о тонометре
                listOf(MANUFACTURER, MODEL, FIRMWARE, BP_FEATURE, BATTERY).forEach { u ->
                    g.services.firstNotNullOfOrNull { it.getCharacteristic(u) }
                        ?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
                        ?.let { c -> ops += { if (!g.readCharacteristic(c)) nextOp() } }
                }
                ops += { subscribe(g, bp) }
                g.getService(BPS)?.getCharacteristic(CUFF)?.let { c -> ops += { subscribe(g, c) } }
                g.services.firstNotNullOfOrNull { it.getCharacteristic(BATTERY) }
                    ?.takeIf { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
                    ?.let { c -> ops += { subscribe(g, c) } }
                // фирменные каналы — только для журнала
                g.services.filter { it.uuid in VENDOR_SERVICES }.flatMap { it.characteristics }
                    .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                    .forEach { c -> ops += { subscribe(g, c) } }
            } else {
                log("Стандартного профиля нет — подписываюсь на все уведомления для диагностики")
                g.services.flatMap { it.characteristics }
                    .filter { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
                    .forEach { c -> ops += { subscribe(g, c) } }
            }
            nextOp()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Подписка на ${short(d.characteristic.uuid)} включена")
            } else {
                log("Подписка на ${short(d.characteristic.uuid)}: ошибка $status")
                if (status == 5 || status == 15 || status == 137) {
                    log("Нужно сопряжение — подтвердите запрос на телефоне")
                    main.post { ops.clear(); g.device.createBond() }
                    return
                }
            }
            nextOp()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") handleRead(c.uuid, c.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(c.uuid, value, status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            log("Запись в ${short(c.uuid)}: " + if (status == 0) "ок" else "ошибка $status")
            nextOp()
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handleData(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleData(c.uuid, value)
        }
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: run { log("У ${short(c.uuid)} нет дескриптора подписки"); nextOp(); return }
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothGatt.GATT_SUCCESS
        else { d.value = v; g.writeDescriptor(d) }
        if (!ok) { log("Не удалось подписаться на ${short(c.uuid)}"); nextOp() }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, type) == BluetoothGatt.GATT_SUCCESS
        else { c.writeType = type; c.value = v; g.writeCharacteristic(c) }
        if (!ok || type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) nextOp()
    }

    private fun currentTimeBytes(): ByteArray {
        val n = LocalDateTime.now()
        return byteArrayOf(
            (n.year and 0xFF).toByte(), (n.year shr 8).toByte(), n.monthValue.toByte(), n.dayOfMonth.toByte(),
            n.hour.toByte(), n.minute.toByte(), n.second.toByte(), n.dayOfWeek.value.toByte(), 0, 1,
        )
    }

    // ── данные ──
    private fun handleRead(u: UUID, v: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            val text = v.toString(Charsets.UTF_8).trim { it <= ' ' }
            when (u) {
                MANUFACTURER -> { _info.value = _info.value.copy(manufacturer = text); log("Производитель: $text") }
                MODEL -> { _info.value = _info.value.copy(model = text); log("Модель: $text") }
                FIRMWARE -> { _info.value = _info.value.copy(firmware = text); log("Прошивка: $text") }
                BATTERY -> v.firstOrNull()?.let { val pct = it.toInt() and 0xFF
                    _info.value = _info.value.copy(battery = pct); log("Батарея: $pct%") }
                BP_FEATURE -> { val f = BpParser.parseFeature(v)
                    _info.value = _info.value.copy(features = f)
                    log("Умеет определять: " + f.ifEmpty { listOf("ничего дополнительно") }.joinToString(", ") + " (${BpParser.hex(v)})") }
                else -> log("Чтение ${short(u)}: ${BpParser.hex(v)}")
            }
        } else log("Чтение ${short(u)}: ошибка $status")
        nextOp()
    }

    private fun handleData(u: UUID, v: ByteArray) {
        when (u) {
            CUFF -> {
                val p = BpParser.parseCuff(v) ?: return
                val prev = _live.value?.takeIf { it.result == null && !it.aborted }
                if (prev == null) log("Начался замер")
                _live.value = Live(cuff = p, history = ((prev?.history ?: emptyList()) + p).takeLast(300))
                main.removeCallbacks(abortLive)
                main.postDelayed(abortLive, 15_000)
                return
            }
            BATTERY -> { v.firstOrNull()?.let { _info.value = _info.value.copy(battery = it.toInt() and 0xFF) }; return }
        }
        log("Данные ${short(u)}: ${BpParser.hex(v)}")
        if (u != BP_MEASUREMENT) return
        val r = BpParser.parse(v) ?: run { log("Не удалось разобрать замер"); return }
        val filter = userFilter()
        if (filter != 0 && r.userId != null && r.userId != filter) {
            log("Замер пользователя ${r.userId} пропущен (принимаются только замеры пользователя $filter)")
            return
        }
        if (r.warnings.isNotEmpty() || r.irregular)
            log("Предупреждения: " + (listOfNotNull("аритмия".takeIf { r.irregular }) + r.warnings).joinToString(", "))
        // результат показываем в окне, если замер шёл сейчас или сделан только что (не старые из памяти тонометра)
        val fresh = _live.value != null || r.time == null ||
            Duration.between(r.time, LocalDateTime.now()).abs().toMinutes() < 10
        if (fresh) {
            main.removeCallbacks(abortLive)
            _live.value = (_live.value ?: Live()).copy(result = r, aborted = false)
        }
        save(r)
    }

    private fun save(r: BleReading) = io.launch {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        // время тонометра берём, если оно правдоподобное; иначе (часы не выставлены) — текущее
        val t = r.time?.takeIf { Duration.between(it, now).let { d -> d.toDays() < 730 && d.toMinutes() > -10 } } ?: now
        val ts = t.atZone(zone).toInstant().toEpochMilli()
        val dao = AppDatabase.get(app).measurements()
        if (dao.countSimilar(r.systolic, r.diastolic, ts - 120_000, ts + 120_000) > 0) {
            log("Замер ${r.systolic}/${r.diastolic} уже есть в дневнике")
            return@launch
        }
        val note = if (r.warnings.isEmpty()) "" else "Тонометр: " + r.warnings.joinToString(", ")
        dao.upsert(Measurement(timestamp = ts, systolic = r.systolic, diastolic = r.diastolic, pulse = r.pulse,
            irregular = r.irregular, note = note))
        val txt = "${r.systolic}/${r.diastolic}" + (r.pulse?.let { ", пульс $it" } ?: "") +
            (r.userId?.let { " (пользователь $it)" } ?: "")
        log("Сохранён замер $txt")
        _lastReading.value = "$txt · ${t.format(DateTimeFormatter.ofPattern("d.MM HH:mm"))}"
        main.post { Toast.makeText(app, "Замер с тонометра: $txt", Toast.LENGTH_LONG).show() }
    }

    // ── вспомогательное ──
    private fun short(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) "0x" + s.substring(4, 8).uppercase() else s
    }

    private fun props(p: Int) = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("чтение")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("запись")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("запись б/о")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString(", ")
}
