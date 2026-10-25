package com.example.osimsofa

import android.Manifest
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
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Single-sofa BLE session: scan → auto-pick the uDiva → connect over LE → enable both notifies →
 * OSIM's read-only init queries → mainboard link (one frame in flight, acked; held posture stream;
 * keyOff heartbeat) with the sofa's status decoded into [status]. All state is Compose-observable.
 */
class SofaConnection(private val app: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, DISCOVERING, VERIFYING, READY, FAILED }

    data class Candidate(val name: String, val address: String, val rssi: Int, val likely: Boolean)

    var phase by mutableStateOf(Phase.IDLE); private set
    var deviceName by mutableStateOf<String?>(null); private set
    var serial by mutableStateOf<String?>(null)
    var variant by mutableStateOf<String?>(null); private set
    var linkReady by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var lastRx by mutableStateOf<String?>(null); private set
    /** Latest decoded status packet from the mainboard (null until the first one arrives). */
    var status by mutableStateOf<SofaStatus?>(null); private set
    var mtu by mutableStateOf(23); private set
    /** Called on the main thread for every status packet. */
    var onStatus: ((SofaStatus) -> Unit)? = null
    val candidates = mutableStateListOf<Candidate>()
    val logLines = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    private val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = btManager.adapter
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null     // Mb / control (AA010001)
    private var cdcWrite: BluetoothGattCharacteristic? = null      // CDC / audio (BB020001)
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var scanning = false
    private val seen = HashMap<String, BluetoothDevice>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(s: String) {
        android.util.Log.i("uDivaSofa", s)
        main.post {
            logLines.add("${fmt.format(Date())}  $s")
            if (logLines.size > 400) logLines.removeAt(0)
        }
    }

    private fun setMsg(s: String?) { main.post { message = s } }

    // ---------------- permissions ----------------
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions(): Boolean = requiredPermissions().all {
        app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // ---------------- scan / auto-connect ----------------
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = try { result.scanRecord?.deviceName ?: dev.name } catch (e: SecurityException) { null }
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
            val likely = uuids.any { OsimProtocol.looksLikeControlService(it) } ||
                (name != null && isSofaName(name))
            val addr = dev.address
            val shown = name ?: "(未命名)"
            if (!seen.containsKey(addr)) {
                val mfg = result.scanRecord?.manufacturerSpecificData
                val mfgIds = if (mfg != null) (0 until mfg.size()).joinToString(",") { "0x%04x".format(mfg.keyAt(it)) } else ""
                log("廣播 $shown $addr rssi=${result.rssi} type=${dev.type} svc=${uuids.joinToString { it.toString().substring(4, 8) }} mfg=[$mfgIds] likely=$likely")
            }
            main.post {
                val idx = candidates.indexOfFirst { it.address == addr }
                val c = Candidate(shown, addr, result.rssi, likely)
                if (idx >= 0) candidates[idx] = c else candidates.add(c)
                candidates.sortByDescending { it.rssi }
            }
            seen[addr] = dev
            if (likely && phase == Phase.SCANNING) {
                if (isPreferredSofa(name)) {
                    log("找到偏好沙發：$shown rssi=${result.rssi}")
                    connect(dev)
                } else {
                    // Remember it; if the preferred serial never appears, we connect to this after a grace period.
                    firstSofa = firstSofa ?: dev
                    scheduleFallbackConnect()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) { log("掃描失敗 code=$errorCode"); fail("掃描失敗（$errorCode）") }
    }

    /** Preferred unit = the NFC-tapped serial if we have one, else the module the OSIM app uses. */
    private val preferredSerial: String get() = serial ?: DEFAULT_MAINBOARD_SERIAL
    private var firstSofa: BluetoothDevice? = null
    private var fallbackScheduled = false

    /** If the preferred serial hasn't shown up a few seconds into the scan, connect to whichever
     *  uDivaSOFA IS advertising (the two modules don't always advertise at the same time). */
    private fun scheduleFallbackConnect() {
        if (fallbackScheduled) return
        fallbackScheduled = true
        main.postDelayed({
            val d = firstSofa
            if (phase == Phase.SCANNING && d != null) {
                val n = try { d.name } catch (e: SecurityException) { null } ?: "(未命名)"
                log("偏好序號未出現，改連目前廣播中的沙發：$n")
                connect(d)
            }
        }, 4_000)
    }

    private fun isSofaName(n: String): Boolean {
        val l = n.lowercase()
        return l.contains("udiva") || l.contains("osim") || l.contains("sofa")
    }

    private fun isPreferredSofa(n: String?): Boolean =
        n != null && n.lowercase().endsWith(preferredSerial.lowercase())

    companion object {
        const val DEFAULT_MAINBOARD_SERIAL = "12345678"
        const val ACK_TIMEOUT_MS = 500L          // OSIM _writeWithAckToMb: wait ≤500 ms …
        const val MAX_ATTEMPTS = 10              // … and send up to 10 times
        const val WRITE_RETRY_MS = 60L           // the BLE stack refused the write; try again shortly
        const val HOLD_PERIOD_MS = 100L          // OSIM re-sends a held preset every ~100 ms
        const val HOLD_CAP_MS = 25_000L          // a held move never runs longer than this
        const val HEARTBEAT_IDLE_MS = 3_000L     // OSIM polls ~5 s when idle; a bit faster for the UI
        const val HEARTBEAT_RUNNING_MS = 1_000L
        const val REQUEST_MTU = 512              // flutter_blue_plus default, as OSIM uses
        const val MTU_TIMEOUT_MS = 3_000L
    }

    fun startAutoConnect() {
        if (!hasPermissions()) { fail("需要藍牙權限"); return }
        if (!bluetoothOn) { fail("請先開啟藍牙"); return }
        disconnect(silent = true)
        candidates.clear(); seen.clear(); firstSofa = null; fallbackScheduled = false; gattErrorRetries = 0
        phase = Phase.SCANNING; linkReady = false; setMsg("正在尋找沙發…")
        try {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            adapter?.bluetoothLeScanner?.startScan(null, settings, scanCb)
            scanning = true
            log("開始掃描")
            main.postDelayed({
                if (phase == Phase.SCANNING) {
                    stopScan()
                    val paired = bondedSofas()
                    if (paired.isNotEmpty()) {
                        log("掃描未發現沙發，改用手機已配對的沙發（${paired.size} 筆）")
                        setMsg("掃描未發現，改用已配對的沙發連線…")
                        tryBondedQueue(paired)
                    } else {
                        fail("找不到沙發。請確認沙發已開機、靠近手機，且未被其他 App 連線。")
                    }
                }
            }, 15_000)
        } catch (e: SecurityException) { fail("需要藍牙權限") }
    }

    fun stopScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (e: Exception) {}
        scanning = false
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
        lastDevice = dev
        stopScan()
        phase = Phase.CONNECTING
        deviceName = try { dev.name } catch (e: SecurityException) { null } ?: dev.address
        setMsg("正在連線…")
        openGatt(dev)
    }

    private var bondReceiver: BroadcastReceiver? = null
    private fun unregisterBond() { bondReceiver?.let { try { app.unregisterReceiver(it) } catch (e: Exception) {} }; bondReceiver = null }

    private fun openGatt(dev: BluetoothDevice) {
        if (gatt != null) return
        try {
            gatt = if (Build.VERSION.SDK_INT >= 23)
                dev.connectGatt(app, false, gattCb, BluetoothDevice.TRANSPORT_LE)
            else dev.connectGatt(app, false, gattCb)
        } catch (e: SecurityException) { fail("需要藍牙連線權限") }
    }

    /** Fallback when scanning can't see the sofa (e.g. it isn't advertising): use the phone's existing pairing. */
    private fun bondedSofas(): List<BluetoothDevice> {
        val all = try { adapter?.bondedDevices } catch (e: SecurityException) { null } ?: return emptyList()
        val s = serial?.lowercase()
        return all.filter { d ->
            val n = (try { d.name } catch (e: SecurityException) { null })?.lowercase() ?: return@filter false
            (n.contains("udiva") || n.contains("osim")) && d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC
        }.sortedWith(compareByDescending<BluetoothDevice> { d ->
            // serial match first, then dual-mode (the live control module is usually DUAL), then LE
            var score = 0
            if (s != null && (d.name ?: "").lowercase().endsWith(s)) score += 10
            if (d.type == BluetoothDevice.DEVICE_TYPE_DUAL) score += 2
            if (d.type == BluetoothDevice.DEVICE_TYPE_LE) score += 1
            score
        }).take(4)   // the phone can hold dozens of stale entries; only try the best few
    }

    /** Try each paired sofa entry in turn (the sofa's BLE address can rotate; stale entries time out). */
    private val bondedQueue = ArrayDeque<BluetoothDevice>()
    private var attemptTimeout: Runnable? = null

    private fun tryBondedQueue(list: List<BluetoothDevice>) {
        bondedQueue.clear(); bondedQueue.addAll(list)
        nextBondedAttempt()
    }

    private fun nextBondedAttempt() {
        val dev = bondedQueue.removeFirstOrNull()
        if (dev == null) { fail("無法連上已配對的沙發。請確認沙發已開機並靠近手機後重試。"); return }
        log("嘗試已配對項目 ${dev.address}")
        try { gatt?.close() } catch (e: Exception) {}; gatt = null
        connect(dev)
        attemptTimeout?.let { main.removeCallbacks(it) }
        attemptTimeout = Runnable {
            if (phase == Phase.CONNECTING && bondedQueue.isNotEmpty()) {
                log("此項目逾時，換下一筆"); try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}; gatt = null
                nextBondedAttempt()
            }
        }.also { main.postDelayed(it, 8_000) }
    }

    private fun describeStatus(status: Int): String = when (status) {
        5, 15 -> "沙發要求配對/加密（$status）"
        8, 147 -> "連線逾時：沙發可能已關機或超出範圍（$status）"
        19 -> "沙發端結束連線（19）"
        22 -> "沙發主動結束連線（22）"
        133 -> "連線遭拒或逾時（133）"
        else -> "狀態 $status"
    }

    fun disconnect(silent: Boolean = false) {
        resetLink()
        stopScan()
        unregisterBond(); bondedQueue.clear()
        attemptTimeout?.let { main.removeCallbacks(it) }; attemptTimeout = null
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) {}
        gatt = null; writeChar = null; cdcWrite = null; notifyChar = null
        linkReady = false; variant = null
        if (!silent) { phase = Phase.IDLE; setMsg("已中斷連線"); log("已中斷") }
    }

    private fun fail(why: String) {
        main.post { phase = Phase.FAILED; message = why }
        log("失敗：$why")
    }

    // ---------------- GATT ----------------
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { phase = Phase.DISCOVERING; message = "正在辨識沙發…" }
                // The 27-byte status packet does not fit the default MTU 23 (20-byte notifications,
                // the rest is lost). OSIM (flutter_blue_plus) asks for 512 too.
                log("已連線 (status=$status)，要求 MTU $REQUEST_MTU…")
                val asked = try { g.requestMtu(REQUEST_MTU) } catch (e: SecurityException) { false }
                if (asked) main.postDelayed(mtuTimeout, MTU_TIMEOUT_MS) else main.post { discover(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("連線中斷：${describeStatus(status)}")
                main.post {
                    try { g.close() } catch (e: Exception) {}
                    resetLink()
                    gatt = null; writeChar = null; cdcWrite = null; notifyChar = null
                    linkReady = false
                    if (phase == Phase.CONNECTING && bondedQueue.isNotEmpty()) { nextBondedAttempt(); return@post }
                    // 133 right after connecting is a transient Android stack error (seen after the
                    // sofa's power cycle); a fresh attempt usually succeeds.
                    val d = lastDevice
                    if (status == 133 && d != null && gattErrorRetries < 2 &&
                        (phase == Phase.CONNECTING || phase == Phase.DISCOVERING)) {
                        gattErrorRetries++
                        log("連線錯誤 133，自動重試（第 $gattErrorRetries 次）")
                        phase = Phase.CONNECTING
                        main.postDelayed({ if (gatt == null && phase == Phase.CONNECTING) openGatt(d) }, 600)
                        return@post
                    }
                    if (phase != Phase.IDLE) { phase = Phase.FAILED; message = "沙發已斷線：${describeStatus(status)}" }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU = $mtu (status=$status)")
            main.post {
                this@SofaConnection.mtu = mtu
                main.removeCallbacks(mtuTimeout)
                discover(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            for (svc in g.services) log("服務 ${svc.uuid}" + svc.characteristics.joinToString("") { "\n   特徵 ${it.uuid} props=${it.properties}" })
            // Collect EVERY notify characteristic across the known control services, and a write char.
            // (The OSIM app subscribes to both aa010002 and bb020002 before the sofa will talk.)
            val notifies = ArrayList<BluetoothGattCharacteristic>()
            var picked: String? = null
            for (svc in g.services) {
                if (!OsimProtocol.looksLikeControlService(svc.uuid)) continue
                if (picked == null) picked = svc.uuid.toString().substring(0, 8).uppercase()
                for (ch in svc.characteristics) {
                    val p = ch.properties
                    if ((p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notifies.add(ch)
                    val writable = (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                    if (!writable) continue
                    val u = ch.uuid.toString().lowercase()
                    if (u.startsWith("aa010001")) writeChar = ch          // Mb / control (primary)
                    if (u.startsWith("bb020001")) cdcWrite = ch           // CDC / audio
                    if (writeChar == null) writeChar = ch                 // fallback
                }
            }
            notifyChar = notifies.firstOrNull()
            if (notifies.isEmpty()) { fail("此裝置沒有可監聽的控制服務"); return }
            main.post { variant = picked; message = "已辨識（$picked），啟用通知中…" }
            log("使用服務變體 $picked  write=${writeChar?.uuid}  notify=${notifies.joinToString { it.uuid.toString().substring(0, 8) }}")
            notifyQueue.clear(); notifyQueue.addAll(notifies)
            enableNextNotify(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            log("通知已啟用 (${d.characteristic.uuid.toString().substring(0, 8)} status=$status)")
            if (notifyQueue.isNotEmpty()) enableNextNotify(g) else onLinkReady()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val v = ch.value ?: return
            onRx(ch.uuid, v.copyOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onRx(ch.uuid, value)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != 0) log("寫入狀態 status=$status")
        }
    }

    private var discovering = false
    private val mtuTimeout = Runnable { gatt?.let { log("MTU 協商沒有回應，沿用預設值"); discover(it) } }

    /** Service discovery, once per connection, after the MTU exchange (main thread). */
    private fun discover(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        log("探索服務…")
        try { g.discoverServices() } catch (e: SecurityException) { fail("需要藍牙連線權限") }
    }

    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()

    private fun enableNextNotify(g: BluetoothGatt) {
        val ch = notifyQueue.removeFirstOrNull() ?: run { onLinkReady(); return }
        try {
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(OsimProtocol.CCCD)
                ?: run { if (notifyQueue.isNotEmpty()) enableNextNotify(g) else onLinkReady(); return }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            }
        } catch (e: SecurityException) { fail("需要藍牙連線權限") }
    }

    /** Link is up and we're listening on all notify channels. No guessed handshake (a malformed first
     *  frame makes the mainboard drop us): we send exactly OSIM's read-only init queries, then the
     *  keyOff heartbeat keeps the status coming. */
    private fun onLinkReady() {
        main.post {
            phase = Phase.READY
            linkReady = true
            message = "已連線，正在接收沙發狀態"
            if (initSent) return@post
            initSent = true
            log("連線完成，送出 OSIM 初始化查詢")
            queue.addAll(OsimProtocol.initMb())
            pump()
        }
    }
    private var initSent = false

    private fun onRx(uuid: UUID, bytes: ByteArray) {
        val hx = OsimProtocol.hex(bytes)
        main.post {
            lastRx = hx
            if (!linkReady) { linkReady = true; phase = Phase.READY }
            if (uuid != OsimProtocol.MB_NOTIFY) { log("RX CDC $hx"); return@post }
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
                onStatus?.invoke(s)
            }
            if (OsimProtocol.isReply(bytes)) onReply()
        }
    }

    // ---------------- mainboard link ----------------
    // OSIM's discipline (_writeWithAckToMb): one frame in flight, acked by the sofa's reply within
    // 500 ms, re-sent up to 10 times. A held preset is re-sent every ~100 ms (each acked) and the sofa
    // stops as soon as the stream stops. With nothing else to do, a keyOff heartbeat polls the status.
    // Everything below runs on the main thread.

    private class InFlight(val cmd: OsimProtocol.Cmd, val retry: Boolean, val quiet: Boolean) {
        val frame = cmd.frame()
        var attempts = 0
    }

    private val queue = ArrayDeque<OsimProtocol.Cmd>()
    private var inFlight: InFlight? = null
    private var held: OsimProtocol.Posture? = null
    private var heldFrames = 0
    private var holdCap: Runnable? = null
    private var lastSendAt = 0L
    private val ackTimeout = Runnable { onAckTimeout() }
    private val pumpLater = Runnable { pump() }

    /** Queue mainboard commands; [front] puts them ahead of anything already waiting. */
    fun enqueue(cmds: List<OsimProtocol.Cmd>, front: Boolean = false) {
        main.post {
            if (!linkReady) { log("未連線，略過：${cmds.joinToString { it.label }}"); return@post }
            if (front) cmds.asReversed().forEach { queue.addFirst(it) } else queue.addAll(cmds)
            pump()
        }
    }

    /** Stream a preset while it is held. The cap releases it on its own (safety) and calls [onCap]. */
    fun startHold(p: OsimProtocol.Posture, maxMs: Long = HOLD_CAP_MS, onCap: (() -> Unit)? = null) {
        if (!linkReady || writeChar == null) return
        if (held != null) endHold(sendRelease = false)     // switching presets: the new stream takes over
        held = p
        heldFrames = 0
        log("HOLD ${p.zh} 開始")
        holdCap = Runnable {
            if (held == p) { log("HOLD ${p.zh} 安全時限到，自動放開"); stopHold(); onCap?.invoke() }
        }.also { main.postDelayed(it, maxMs) }
        pump()
    }

    /** Stop streaming and send the release, so the sofa stops at once. */
    fun stopHold() { if (held != null) endHold(sendRelease = true) }

    /** 停止: drop everything pending, stop any movement and stop the massage. */
    fun stopAll() {
        if (held != null) endHold(sendRelease = false)
        queue.clear()
        queue.addAll(listOf(OsimProtocol.release(), OsimProtocol.stopMassage()))
        pump()
    }

    private fun endHold(sendRelease: Boolean) {
        val p = held ?: return
        held = null
        holdCap?.let { main.removeCallbacks(it) }; holdCap = null
        log("HOLD ${p.zh} 結束（串流 $heldFrames 幀）")
        if (sendRelease) { queue.addFirst(OsimProtocol.release()); pump() }
    }

    private fun pump() {
        main.removeCallbacks(pumpLater)
        if (!linkReady || writeChar == null || inFlight != null) return
        queue.removeFirstOrNull()?.let { transmit(InFlight(it, retry = true, quiet = false)); return }
        val since = SystemClock.uptimeMillis() - lastSendAt
        val p = held
        if (p != null) {
            if (since < HOLD_PERIOD_MS) { main.postDelayed(pumpLater, HOLD_PERIOD_MS - since); return }
            heldFrames++
            transmit(InFlight(OsimProtocol.hold(p), retry = false, quiet = heldFrames > 1))
            return
        }
        // Poll faster while a massage runs or a fault is pending, so changes show up at once.
        val every = if (status?.massageRunning == true || status?.fault == true) HEARTBEAT_RUNNING_MS else HEARTBEAT_IDLE_MS
        if (since < every) { main.postDelayed(pumpLater, every - since); return }
        transmit(InFlight(OsimProtocol.heartbeat(), retry = false, quiet = true))
    }

    private fun transmit(f: InFlight) {
        inFlight = f
        f.attempts++
        lastSendAt = SystemClock.uptimeMillis()
        val ok = writeBytes(writeChar, f.frame)
        if (!f.quiet) {
            val again = if (f.attempts > 1) "（第 ${f.attempts} 次）" else ""
            log("TX ${f.cmd.label} ${OsimProtocol.hex(f.frame)}$again" + (if (ok) "" else " 寫入被拒"))
        }
        main.removeCallbacks(ackTimeout)
        main.postDelayed(ackTimeout, if (ok) ACK_TIMEOUT_MS else WRITE_RETRY_MS)
    }

    private fun onReply() {
        val f = inFlight ?: return
        main.removeCallbacks(ackTimeout)
        inFlight = null
        if (!f.quiet) log("ACK ${f.cmd.label}")
        pump()
    }

    private fun onAckTimeout() {
        val f = inFlight ?: return
        if (f.retry && f.attempts < MAX_ATTEMPTS) { transmit(f); return }
        inFlight = null
        if (f.retry) log("FAIL ${f.cmd.label}：沙發 $MAX_ATTEMPTS 次都沒有回應")
        pump()
    }

    private fun resetLink() {
        main.removeCallbacks(ackTimeout); main.removeCallbacks(pumpLater)
        holdCap?.let { main.removeCallbacks(it) }; holdCap = null
        queue.clear(); inFlight = null; held = null
        initSent = false
        status = null
        main.removeCallbacks(mtuTimeout)
        discovering = false; warnedTruncated = false; mtu = 23
    }
    private var warnedTruncated = false

    fun sendVolume(pct: Int): Boolean {
        val f = OsimProtocol.volumeFrame(pct)
        log("→ 音量$pct% ${OsimProtocol.hex(f)}")
        return writeBytes(cdcWrite ?: writeChar, f)
    }

    fun sendRaw(bytes: ByteArray): Boolean { log("→ raw ${OsimProtocol.hex(bytes)}"); return writeBytes(writeChar, bytes) }

    private fun writeBytes(ch: BluetoothGattCharacteristic?, bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = ch ?: return false
        val wt = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, wt) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = wt; c.value = bytes; g.writeCharacteristic(c) }
            }
        } catch (e: SecurityException) { false }
    }
}
