package com.example.osimsofa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.osimsofa.OsimProtocol.Cmd
import com.example.osimsofa.OsimProtocol.Posture
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Single-sofa BLE session: scan → pick the uDiva → connect over LE → MTU → enable both notifies →
 * OSIM's read-only init queries → [MbLink] (acked frames, held preset stream, heartbeat), with the
 * sofa's status decoded into [status].
 *
 * The sofa counts as operable ([Phase.READY]) only once a valid status has arrived on this
 * connection (improve-plan S01). Every GATT callback and delayed task belongs to one connection
 * generation; anything from an older one is ignored. All state lives on the main thread and is
 * Compose-observable.
 *
 * Bluetooth permissions: every way in ([startAutoConnect], [connectCandidate]) checks
 * [hasPermissions] first, and each platform call still handles a SecurityException (a permission
 * can be revoked while connected), hence the class-wide MissingPermission suppression.
 */
@SuppressLint("MissingPermission")
class SofaConnection(private val app: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, DISCOVERING, VERIFYING, READY, FAILED }

    data class Candidate(val name: String, val address: String, val rssi: Int, val likely: Boolean)

    var phase by mutableStateOf(Phase.IDLE); private set
    var deviceName by mutableStateOf<String?>(null); private set
    /** The serial read from the sofa's NFC tag: only that sofa is auto-connected. */
    var serial by mutableStateOf<String?>(null)
    var variant by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var lastRx by mutableStateOf<String?>(null); private set
    /** Latest decoded status packet from the mainboard (null until the first one arrives). */
    var status by mutableStateOf<SofaStatus?>(null); private set
    /** Uptime of [status]. */
    var statusAt = 0L; private set
    var mtu by mutableIntStateOf(23); private set
    val candidates = mutableStateListOf<Candidate>()
    val logLines = mutableStateListOf<String>()

    /** Called on the main thread for every status packet. */
    var onStatus: ((SofaStatus) -> Unit)? = null
    /** The first valid status arrived: the sofa can be controlled. */
    var onOperable: (() -> Unit)? = null
    /** An operable link went down; the sofa's real state is unknown now. The reason is null when
     *  the user disconnected (or started a new search) on purpose. */
    var onLost: ((String?) -> Unit)? = null
    /** No link any more, for whatever reason. */
    var onClosed: (() -> Unit)? = null
    /** A command the sofa never acknowledged. */
    var onCommandFailed: ((Cmd) -> Unit)?
        get() = link.onFail
        set(v) { link.onFail = v }

    val operable: Boolean get() = phase == Phase.READY
    fun statusAgeMs(): Long? = if (status == null) null else SystemClock.uptimeMillis() - statusAt

    private val main = Handler(Looper.getMainLooper())
    private val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter
    private val prefs = app.getSharedPreferences("sofa", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null     // Mb / control (AA010001)
    private var scanning = false
    private val seen = HashMap<String, BluetoothDevice>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    /** Connection generation: bumped by every new attempt and every teardown. */
    private var gen = 0

    private val wake: Runnable = Runnable { link.onWake() }
    private val link: MbLink = MbLink(object : MbLink.Io {
        override fun now() = SystemClock.uptimeMillis()
        override fun write(frame: ByteArray) = writeBytes(writeChar, frame)
        override fun wakeAt(at: Long) { main.removeCallbacks(wake); main.postAtTime(wake, at) }
        override fun log(line: String) = this@SofaConnection.log(line)
    })

    /** Callable from any thread (GATT callbacks log on a binder thread); the time is formatted on
     *  the main thread, as SimpleDateFormat is not thread-safe. */
    fun log(s: String) {
        android.util.Log.i("uDivaSofa", s)
        val at = System.currentTimeMillis()
        main.post {
            logLines.add("${fmt.format(Date(at))}  $s")
            if (logLines.size > 400) logLines.removeAt(0)
        }
    }

    // ---------------- mainboard link (main thread) ----------------
    fun enqueue(cmds: List<Cmd>, front: Boolean = false) = link.enqueue(cmds, front)
    /** Stream a preset; the sofa moves only while the stream lasts. */
    fun hold(p: Posture) = link.hold(p)
    fun release() = link.release()
    /** Pre-empt everything: release + stopMassage go out now. */
    fun stopNow() = link.stopNow()
    var pollFast: Boolean
        get() = link.pollFast
        set(v) { link.pollFast = v }
    val holding: Posture? get() = link.holding

    // ---------------- permissions ----------------
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all {
        app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------------- scan / pick ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = try { result.scanRecord?.deviceName ?: dev.name } catch (e: SecurityException) { null }
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val likely = uuids.any { OsimProtocol.looksLikeControlService(it) } || (name != null && isSofaName(name))
            val addr = dev.address
            val rssi = result.rssi
            main.post { onScanned(dev, name, addr, rssi, likely, uuids) }
        }
        override fun onScanFailed(errorCode: Int) { main.post { fail("掃描失敗（$errorCode）") } }
    }

    private var scanGen = 0
    private var scanStartedAt = 0L
    /** Sofa-like modules seen in this scan: address → advertised name. */
    private val likelySofas = LinkedHashMap<String, String?>()

    private fun onScanned(dev: BluetoothDevice, name: String?, addr: String, rssi: Int, likely: Boolean, uuids: List<UUID>) {
        if (phase != Phase.SCANNING) return
        val shown = name ?: "(未命名)"
        if (!seen.containsKey(addr)) {
            log("廣播 $shown ${mask(addr)} rssi=$rssi likely=$likely svc=${uuids.joinToString { it.toString().substring(4, 8) }}")
        }
        seen[addr] = dev
        val idx = candidates.indexOfFirst { it.address == addr }
        val c = Candidate(shown, addr, rssi, likely)
        if (idx >= 0) candidates[idx] = c else candidates.add(c)
        candidates.sortByDescending { it.rssi }
        if (!likely) return
        likelySofas[addr] = name
        pick(final = false)
    }

    /**
     * Which sofa module to connect, or keep waiting:
     * 1. the NFC-tapped sofa, when the tag's serial is in a Bluetooth name;
     * 2. the module connected last time (OSIM's 12345678 before the first connection);
     * 3. the only sofa module around.
     * The tag may carry the product serial (0000X-…) rather than the module's (uDivaSOFA_12345678),
     * and one sofa has two modules that do not always advertise together — so a tap whose serial
     * matches no name falls back to 2 and 3 after a few seconds. Several unknown modules: the list,
     * never a guess.
     */
    private fun pick(final: Boolean) {
        if (phase != Phase.SCANNING) return
        val nfc = serial
        likelySofas.entries.firstOrNull { nfc != null && it.value.endsWithSerial(nfc) }
            ?.let { connectSeen(it.key, "找到感應的沙發：${it.value}"); return }
        val waited = final || SystemClock.uptimeMillis() - scanStartedAt >= SINGLE_PICK_MS
        if (nfc != null && !waited) { recheckLater(); return }      // give the tapped serial a moment first
        likelySofas.entries.firstOrNull { it.value.endsWithSerial(rememberedSerial()) }?.let {
            connectSeen(it.key, (if (nfc != null) "感應序號沒有對應的藍牙名稱；" else "") + "找到上次連線的沙發：${it.value}")
            return
        }
        if (!waited) { recheckLater(); return }                     // a second module may still show up
        likelySofas.entries.singleOrNull()?.let {
            connectSeen(it.key, (if (nfc != null) "感應序號沒有對應的藍牙名稱；" else "") + "附近只有一個沙發裝置：${it.value}")
        }
    }

    private var recheckFor = -1
    private fun recheckLater() {
        val g = scanGen
        if (recheckFor == g) return
        recheckFor = g
        main.postAtTime({ if (g == scanGen) pick(final = false) }, scanStartedAt + SINGLE_PICK_MS)
    }

    private fun connectSeen(address: String, why: String) {
        val dev = seen[address] ?: return
        log(why)
        connect(dev)
    }

    private fun String?.endsWithSerial(s: String?): Boolean =
        this != null && s != null && s.isNotEmpty() && lowercase().endsWith(s.lowercase())

    /** The serial of the last sofa that reached READY (the mainboard module OSIM uses before that). */
    private fun rememberedSerial(): String = prefs.getString(KEY_LAST_SERIAL, null) ?: DEFAULT_MAINBOARD_SERIAL

    private fun isSofaName(n: String): Boolean {
        val l = n.lowercase()
        return l.contains("udiva") || l.contains("osim") || l.contains("sofa")
    }

    fun startAutoConnect() {
        if (!hasPermissions()) { fail("需要藍牙權限"); return }
        if (!bluetoothOn) { fail("請先開啟藍牙"); return }
        val was = phase
        teardown()
        if (was == Phase.READY) { onLost?.invoke(null); onClosed?.invoke() }   // e.g. an NFC tap while connected
        candidates.clear(); seen.clear(); likelySofas.clear(); gattErrorRetries = 0
        val g = ++scanGen
        scanStartedAt = SystemClock.uptimeMillis()
        phase = Phase.SCANNING
        message = serial?.let { "正在尋找感應的沙發（序號 ${maskSerial(it)}）…" } ?: "正在尋找沙發…"
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter?.bluetoothLeScanner?.startScan(null, settings, scanCb)
            scanning = true
            log("開始掃描")
            main.postDelayed({ if (g == scanGen && phase == Phase.SCANNING) onScanTimeout() }, SCAN_MS)
        } catch (e: SecurityException) { fail("需要藍牙權限") }
    }

    private fun onScanTimeout() {
        pick(final = true)
        if (phase != Phase.SCANNING) return                          // pick connected something
        stopScan()
        if (likelySofas.size > 1) { fail("附近有 ${likelySofas.size} 個沙發裝置，無法確定是哪一張，請從下方清單選擇。"); return }
        val paired = bondedSofas()
        if (paired.isNotEmpty()) {
            log("掃描未發現沙發，改用手機已配對的沙發（${paired.size} 筆）")
            message = "掃描未發現，改用已配對的沙發連線…"
            tryBondedQueue(paired)
        } else {
            fail("找不到沙發。請確認沙發已開機、靠近手機，且原廠 App 沒有連著沙發。")
        }
    }

    fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) {}
        scanning = false
    }

    /** 取消 while searching or connecting. */
    fun cancel() {
        log("使用者取消連線")
        teardown()
        phase = Phase.IDLE
        message = null
    }

    fun connectCandidate(address: String) {
        gattErrorRetries = 0
        val dev = seen[address] ?: try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null }
        if (dev == null) { fail("無法取得裝置"); return }
        connect(dev)
    }

    private var lastDevice: BluetoothDevice? = null
    private var gattErrorRetries = 0

    /** Mirror the OSIM app: connect directly over LE, no bonding (observed: it never bonds). */
    private fun connect(dev: BluetoothDevice) {
        stopScan()
        scanGen++
        closeGatt()
        lastDevice = dev
        deviceName = try { dev.name } catch (e: SecurityException) { null } ?: dev.address
        phase = Phase.CONNECTING
        message = "正在連線…"
        openGatt(dev)
    }

    private fun openGatt(dev: BluetoothDevice) {
        if (gatt != null) return
        val g = ++gen
        try {
            gatt = dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) { fail("需要藍牙連線權限"); return }
        after(CONNECT_MS, g, Phase.CONNECTING) { if (bondedQueue.isEmpty()) fail("連線逾時：沙發沒有回應。請確認沙發已開機並靠近手機。") }
    }

    /** Run [block] after [ms] if this is still connection [g] in [expect]. */
    private fun after(ms: Long, g: Int, expect: Phase, block: () -> Unit) {
        main.postDelayed({ if (g == gen && phase == expect) block() }, ms)
    }

    /** Fallback when scanning can't see the sofa (e.g. it isn't advertising): use the phone's existing pairing. */
    private fun bondedSofas(): List<BluetoothDevice> {
        val all = try { adapter?.bondedDevices } catch (e: SecurityException) { null } ?: return emptyList()
        val s = rememberedSerial().lowercase()
        return all.filter { d ->
            val n = (try { d.name } catch (e: SecurityException) { null })?.lowercase() ?: return@filter false
            (n.contains("udiva") || n.contains("osim")) && d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC
        }.sortedWith(compareByDescending<BluetoothDevice> { d ->
            // serial match first, then dual-mode (the live control module is usually DUAL), then LE
            var score = 0
            if ((try { d.name } catch (e: SecurityException) { null } ?: "").lowercase().endsWith(s)) score += 10
            if (d.type == BluetoothDevice.DEVICE_TYPE_DUAL) score += 2
            if (d.type == BluetoothDevice.DEVICE_TYPE_LE) score += 1
            score
        }).take(4)   // the phone can hold dozens of stale entries; only try the best few
    }

    /** Try each paired sofa entry in turn (the sofa's BLE address can rotate; stale entries time out). */
    private val bondedQueue = ArrayDeque<BluetoothDevice>()

    private fun tryBondedQueue(list: List<BluetoothDevice>) {
        bondedQueue.clear(); bondedQueue.addAll(list)
        nextBondedAttempt()
    }

    private fun nextBondedAttempt() {
        val dev = bondedQueue.removeFirstOrNull()
        if (dev == null) { fail("無法連上已配對的沙發。請確認沙發已開機並靠近手機後重試。"); return }
        log("嘗試已配對項目 ${mask(dev.address)}")
        connect(dev)
        val g = gen
        after(BONDED_ATTEMPT_MS, g, Phase.CONNECTING) {
            if (bondedQueue.isNotEmpty()) { log("此項目逾時，換下一筆"); closeGatt(); nextBondedAttempt() }
        }
    }

    private fun describeStatus(status: Int): String = when (status) {
        5, 15 -> "沙發要求配對/加密（$status）"
        8, 147 -> "連線逾時：沙發可能已關機或超出範圍（$status）"
        19 -> "沙發端結束連線（19）"
        22 -> "沙發主動結束連線（22）"
        133 -> "連線遭拒或逾時（133）"
        else -> "狀態 $status"
    }

    /** User disconnect (or the app leaving for good). */
    fun disconnect(silent: Boolean = false) {
        val was = phase
        teardown()
        if (!silent) { phase = Phase.IDLE; message = "已中斷連線"; log("已中斷") }
        if (was == Phase.READY) onLost?.invoke(null)
        if (was != Phase.IDLE) onClosed?.invoke()
    }

    /** Drop the link, the gatt and every pending task of the current generation. */
    private fun teardown() {
        gen++; scanGen++
        demoStatus = null; main.removeCallbacks(demoTick)
        stopScan()
        bondedQueue.clear()
        link.close()
        main.removeCallbacks(wake)
        closeGatt()
        status = null; mtu = 23; variant = null
        discovering = false; warnedTruncated = false; initSent = false
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null; writeChar = null
        try { g.disconnect(); g.close() } catch (e: Exception) {}
    }

    private fun fail(why: String) {
        val was = phase
        teardown()
        phase = Phase.FAILED
        message = why
        log("失敗：$why")
        if (was == Phase.READY) onLost?.invoke(why)
        if (was != Phase.IDLE && was != Phase.FAILED) onClosed?.invoke()
    }

    // ---------------- GATT ----------------
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post { onConnectionState(g, status, newState) }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                log("MTU = $mtu (status=$status)")
                this@SofaConnection.mtu = mtu
                discover(g)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { main.post { onServices(g, status) } }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { onDescriptor(g, d, status) }
        }
        @Suppress("OVERRIDE_DEPRECATION")     // the only callback below Android 13
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = ch.value ?: return
            val copy = v.copyOf()
            main.post { if (g === gatt) onRx(ch.uuid, copy) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (g === gatt) onRx(ch.uuid, value) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != 0) log("寫入狀態 status=$status")
        }
    }

    private fun onConnectionState(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) { try { g.close() } catch (e: Exception) {}; return }      // an older connection
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            phase = Phase.DISCOVERING
            message = "正在辨識沙發…"
            // The 27-byte status packet does not fit the default MTU 23 (20-byte notifications, the
            // rest is lost). OSIM (flutter_blue_plus) asks for 512 too.
            log("已連線 (status=$status)，要求 MTU $REQUEST_MTU…")
            val asked = try { g.requestMtu(REQUEST_MTU) } catch (e: SecurityException) { false }
            val cur = gen
            if (asked) after(MTU_TIMEOUT_MS, cur, Phase.DISCOVERING) { log("MTU 協商沒有回應，沿用預設值"); discover(g) }
            else discover(g)
            after(DISCOVER_MS, cur, Phase.DISCOVERING) { fail("無法辨識沙發的服務，請重試") }
            return
        }
        if (newState != BluetoothProfile.STATE_DISCONNECTED) return
        log("連線中斷：${describeStatus(status)}")
        val was = phase
        closeGatt()
        if (was == Phase.CONNECTING && bondedQueue.isNotEmpty()) { nextBondedAttempt(); return }
        // 133 right after connecting is a transient Android stack error (seen after the sofa's
        // power cycle); a fresh attempt usually succeeds.
        val d = lastDevice
        if (status == 133 && d != null && gattErrorRetries < 2 && (was == Phase.CONNECTING || was == Phase.DISCOVERING)) {
            gattErrorRetries++
            log("連線錯誤 133，自動重試（第 $gattErrorRetries 次）")
            teardown()
            phase = Phase.CONNECTING
            val g2 = gen
            main.postDelayed({ if (g2 == gen && phase == Phase.CONNECTING) openGatt(d) }, 600)
            return
        }
        if (was != Phase.IDLE) {
            val why = if (was == Phase.READY) "沙發已斷線：${describeStatus(status)}。無法確認沙發是否已停止，請查看沙發。"
            else "沙發已斷線：${describeStatus(status)}"
            fail(why)
        }
    }

    private var discovering = false

    /** Service discovery, once per connection, after the MTU exchange. */
    private fun discover(g: BluetoothGatt) {
        if (discovering || g !== gatt) return
        discovering = true
        log("探索服務…")
        try { g.discoverServices() } catch (e: SecurityException) { fail("需要藍牙連線權限") }
    }

    private fun onServices(g: BluetoothGatt, status: Int) {
        if (g !== gatt || phase != Phase.DISCOVERING) return
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("無法讀取沙發的服務（$status）"); return }
        // Every notify characteristic across the known control services, and the write char.
        // (The OSIM app subscribes to both aa010002 and bb020002 before the sofa will talk.)
        val notifies = ArrayList<BluetoothGattCharacteristic>()
        var picked: String? = null
        for (svc in g.services) {
            if (!OsimProtocol.looksLikeControlService(svc.uuid)) continue
            if (picked == null) picked = svc.uuid.toString().substring(0, 8).uppercase()
            for (ch in svc.characteristics) {
                val p = ch.properties
                if ((p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notifies.add(ch)
                val writable = (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                if (!writable) continue
                val u = ch.uuid.toString().lowercase()
                if (u.startsWith("aa010001")) writeChar = ch          // Mb / control (primary)
                if (writeChar == null) writeChar = ch                 // fallback
            }
        }
        if (notifies.isEmpty() || writeChar == null) { fail("此裝置沒有沙發的控制服務"); return }
        variant = picked
        message = "已辨識沙發，正在啟用通知…"
        log("使用服務變體 $picked  write=${writeChar?.uuid}  notify=${notifies.joinToString { it.uuid.toString().substring(0, 8) }}")
        notifyQueue.clear(); notifyQueue.addAll(notifies)
        after(SUBSCRIBE_MS, gen, Phase.DISCOVERING) { fail("無法啟用沙發的通知，請重試") }
        enableNextNotify(g)
    }

    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private fun enableNextNotify(g: BluetoothGatt) {
        val ch = notifyQueue.removeFirstOrNull() ?: run { onSubscribed(); return }
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(OsimProtocol.CCCD) ?: run { enableNextNotify(g); return }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
            if (!ok) fail("無法啟用沙發的通知（寫入被拒）")
        } catch (e: SecurityException) { fail("需要藍牙連線權限") }
    }

    private fun onDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        if (g !== gatt || phase != Phase.DISCOVERING) return
        log("通知已啟用 (${d.characteristic.uuid.toString().substring(0, 8)} status=$status)")
        if (status != BluetoothGatt.GATT_SUCCESS) { fail("無法啟用沙發的通知（$status）"); return }
        enableNextNotify(g)
    }

    /** Listening on every notify channel. No guessed handshake (a malformed first frame makes the
     *  mainboard drop us): exactly OSIM's read-only init queries, then the heartbeat polls the status. */
    private fun onSubscribed() {
        phase = Phase.VERIFYING
        message = "已連線，正在接收沙發狀態…"
        link.open()
        if (!initSent) {
            initSent = true
            log("連線完成，送出 OSIM 初始化查詢")
            link.enqueue(OsimProtocol.initMb())
        }
        after(VERIFY_MS, gen, Phase.VERIFYING) {
            fail("已連線，但沒有收到沙發狀態。請確認原廠 App 已關閉後重試。")
        }
    }
    private var initSent = false

    private fun onRx(uuid: UUID, bytes: ByteArray) {
        val hx = OsimProtocol.hex(bytes)
        lastRx = hx
        if (uuid != OsimProtocol.MB_NOTIFY) { log("RX CDC $hx"); return }
        val s = SofaStatus.parse(bytes)
        if (s == null) {
            log("RX $hx")
            if (!warnedTruncated && bytes.size < SofaStatus.LENGTH && bytes.size >= 9 && (bytes[7].toInt() and 0xFF) == 0xD0) {
                warnedTruncated = true
                log("STATUS 封包被截斷（${bytes.size}/${SofaStatus.LENGTH} bytes，MTU=$mtu），無法解析")
            }
        } else {
            if (status?.summary() != s.summary()) log("STATUS ${s.summary()}")
            status = s
            statusAt = SystemClock.uptimeMillis()
            link.awaitingStatus = false
            if (phase == Phase.VERIFYING) becomeOperable()
            onStatus?.invoke(s)
        }
        OsimProtocol.replySeq(bytes)?.let { link.onReply(it, OsimProtocol.isKeyReply(bytes)) }
    }
    private var warnedTruncated = false

    private fun becomeOperable() {
        phase = Phase.READY
        message = null
        log("沙發已可操作（收到第一筆狀態）")
        deviceName?.let { n -> Regex("_(\\w+)$").find(n)?.groupValues?.get(1)?.let { prefs.edit().putString(KEY_LAST_SERIAL, it).apply() } }
        onOperable?.invoke()
    }

    fun sendRaw(bytes: ByteArray): Boolean { log("→ raw ${OsimProtocol.hex(bytes)}"); return writeBytes(writeChar, bytes) }

    // ---------------- debug builds only: a pretend sofa for layout checks ----------------
    private var demoStatus by mutableStateOf<SofaStatus?>(null)
    val demo: Boolean get() = demoStatus != null
    private val demoTick = object : Runnable {
        override fun run() {
            val s = demoStatus ?: return
            status = s; statusAt = SystemClock.uptimeMillis()
            onStatus?.invoke(s)
            main.postDelayed(this, 1_000)
        }
    }

    /** Show the control screen for a sofa in state [s] without any sofa. The link stays closed,
     *  so whatever is pressed, nothing is sent (MbLink logs 「未連線，略過」). */
    fun debugDemo(s: SofaStatus) {
        teardown()
        log("示範模式：沒有連線，不會送出任何指令")
        demoStatus = s
        deviceName = "示範模式（未連線）"
        phase = Phase.READY
        message = null
        main.post(demoTick)
    }

    private fun writeBytes(ch: BluetoothGattCharacteristic?, bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = ch ?: return false
        val wt = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, wt) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = wt; c.value = bytes; g.writeCharacteristic(c) }
            }
        } catch (e: SecurityException) { false }
    }

    companion object {
        /** The mainboard module the OSIM app connects to; used until a sofa has been connected once. */
        const val DEFAULT_MAINBOARD_SERIAL = "12345678"
        const val REQUEST_MTU = 512              // flutter_blue_plus default, as OSIM uses
        const val SCAN_MS = 15_000L
        const val SINGLE_PICK_MS = 4_000L
        const val CONNECT_MS = 15_000L
        const val BONDED_ATTEMPT_MS = 8_000L
        const val MTU_TIMEOUT_MS = 3_000L
        const val DISCOVER_MS = 12_000L
        const val SUBSCRIBE_MS = 6_000L
        const val VERIFY_MS = 8_000L
        private const val KEY_LAST_SERIAL = "last_serial"

        /** MAC addresses keep only their last two bytes in logs: 11:22:33:44:55:66 → **:**:**:**:55:66. */
        fun mask(address: String): String =
            address.split(':').let { p -> if (p.size == 6) "**:**:**:**:${p[4]}:${p[5]}" else address }

        /** 12345678 → 1234****. */
        fun maskSerial(s: String): String = if (s.length <= 4) s else s.take(4) + "*".repeat(s.length - 4)
    }
}
