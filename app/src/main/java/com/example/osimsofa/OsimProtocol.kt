package com.example.osimsofa

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * uDiva SOFA (uDiva4) wire protocol — full write-up in findings/PROTOCOL.md.
 *
 * Mainboard frame, written to AA010001: `AA 55 | seq LE16 | len LE16 | C5 00 | key p1 p2 | xor`
 * with len = 2 + 3 + 1. Every key operation uses header `C5 00`; the sofa answers each one with a
 * 27-byte status packet (see [SofaStatus]). Key codes, program numbers and option values are the
 * OSIM app's own tables (kKeyOperationMap, UDiva4Program, the manual-massage enums); the encoder
 * reproduces every frame of the real OSIM capture byte for byte (ProtocolTest).
 */
object OsimProtocol {

    private fun uuid16(v: String): UUID = UUID.fromString("0000$v-0000-1000-8000-00805f9b34fb")
    private fun U(s: String): UUID = UUID.fromString("$s-7dd4-4cef-ab30-a807ad5c5ae6")

    data class Variant(val service: UUID, val write: UUID, val notify: UUID?)

    /** The uDiva4 module variants (BleProfileUDiva4). The chair exposes one; we subscribe to all notifies. */
    val VARIANTS: List<Variant> = listOf(
        Variant(U("AA010000"), U("AA010001"), U("AA010002")),   // Mb / control  (primary)
        Variant(U("BB020000"), U("BB020001"), U("BB020002")),   // CDC / audio
        Variant(U("BB010000"), U("BB010001"), null),
        Variant(UUID.fromString("49535343-fe7d-4ae5-8fa9-9fafd205e455"),
                UUID.fromString("6e800002-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e800003-b5a3-f393-e0a9-e50e24dcca9e")),
        Variant(uuid16("fff0"), uuid16("fff2"), uuid16("fff1")),
    )

    // Control (mainboard) channel and audio (CDC) channel.
    val MB_WRITE: UUID = U("AA010001"); val MB_NOTIFY: UUID = U("AA010002")
    val CDC_WRITE: UUID = U("BB020001"); val CDC_NOTIFY: UUID = U("BB020002")

    val CANDIDATE_SERVICES: List<UUID> = VARIANTS.map { it.service }
    val CCCD: UUID = uuid16("2902")
    val DEVICE_INFO: UUID = uuid16("180a")
    val FIRMWARE_REV: UUID = uuid16("2a26")

    fun looksLikeControlService(service: UUID): Boolean {
        val s = service.toString().lowercase()
        return CANDIDATE_SERVICES.any { it.toString().lowercase() == s } ||
            s.endsWith("-7dd4-4cef-ab30-a807ad5c5ae6") ||
            s.startsWith("49535343") || s.startsWith("6e80") || s == uuid16("fff0").toString()
    }

    // ====== uDiva4 key codes (kKeyOperationMap) ===================================================
    object Key {
        const val KEY_OFF = 0x00            // release / idle heartbeat
        const val STOP_MASSAGE = 0x01
        const val SOFA = 0x1C               // the three presets are HELD: stream while pressed
        const val UPRIGHT = 0x1D
        const val RECLINE = 0x1E
        const val RESUME = 0x30
        const val PAUSE = 0x31
        const val TOGGLE_DURATION = 0x49    // 15 ↔ 30 min
        const val KNEAD_TAP = 0x48
        const val ROLLING = 0x47
        const val BACK_SPEED = 0x60
        const val ROLLER_WIDTH = 0x07
        const val SHOULDER_INTENSITY = 0x44
        const val BACK_INTENSITY = 0x46
        const val LUMBAR_INTENSITY = 0x45
        const val FOOT_KNEADING = 0x20
        const val FOOT_VIBRATION = 0x22
        const val SEAT_VIBRATION = 0x23
    }

    /** The sofa's three preset positions (OSIM: sofa / upright / reclined — there is no zero-g). */
    enum class Posture(val key: Int, val zh: String) {
        UPRIGHT(Key.UPRIGHT, "坐直"), RECLINE(Key.RECLINE, "躺平"), SOFA(Key.SOFA, "沙發")
    }

    /** uDiva4 auto programs: the status reports [number], the start key is number + 0x23.
     *  [prepare] is the position OSIM moves the sofa to before starting. */
    enum class AutoProgram(val number: Int, val zh: String, val prepare: Posture) {
        SIGNATURE(16, "全身按摩", Posture.RECLINE),
        NECK_SHOULDER(17, "頸肩紓緩", Posture.RECLINE),
        LUMBAR(18, "腰部舒緩", Posture.UPRIGHT),
        SLEEP(19, "夜間助眠", Posture.RECLINE),
        ENTERTAINMENT(20, "娛樂時光", Posture.RECLINE),
        SPORTS_RECOVERY(21, "運動恢復", Posture.RECLINE);

        val startKey: Int get() = number + 0x23

        companion object { fun byNumber(n: Int): AutoProgram? = AutoProgram.entries.firstOrNull { it.number == n } }
    }
    const val MANUAL_PROGRAM_NO = 1
    const val VGRIP_PROGRAM_NO = 2

    // ====== manual massage options (wire values of OSIM's enums) ==================================
    enum class KneadTap(val wire: Int, val zh: String) { OFF(0, "關"), TAPPING(1, "敲打"), KNEADING(2, "揉捏"), BOTH(3, "兩者") }
    enum class Rolling(val wire: Int, val zh: String) { OFF(0, "關"), FULL(1, "全程"), PARTIAL(2, "局部") }
    enum class BackSpeed(val wire: Int, val zh: String) { AUTO(1, "自動"), LOW(2, "慢"), HIGH(3, "快") }
    enum class RollerWidth(val wire: Int, val zh: String) { NARROW(1, "窄"), MIDDLE(2, "中"), WIDE(3, "寬"), AUTO(4, "自動") }
    /** Foot kneading, foot vibration and seat (butt) vibration share these values. */
    enum class Level(val wire: Int, val zh: String) { OFF(0, "關"), AUTO(2, "自動"), LOW(3, "弱"), HIGH(4, "強") }
    val INTENSITY = 1..5

    data class ManualSettings(
        val kneadTap: KneadTap = KneadTap.KNEADING,
        val rolling: Rolling = Rolling.FULL,
        val speed: BackSpeed = BackSpeed.AUTO,
        val width: RollerWidth = RollerWidth.AUTO,
        val shoulder: Int = 3,
        val back: Int = 3,
        val lumbar: Int = 3,
        val footKneading: Level = Level.OFF,
        val footVibration: Level = Level.OFF,
        val seatVibration: Level = Level.OFF,
    )

    /** One mainboard command = OSIM's CommandModel: header (`C5 00` for key operations) + values.
     *  Its frame (and sequence number) is built when it is sent. */
    data class Cmd(val label: String, val values: List<Int>, val header: List<Int> = KEY_OP) {
        /** The key code for a key operation, null for other headers. */
        val key: Int? get() = if (header == KEY_OP) values.firstOrNull() else null
        fun frame(seq: Int = nextSeq()): ByteArray = mbFrame(header.toBytes(), values.toBytes(), seq)
    }

    private val KEY_OP = listOf(0xC5, 0x00)
    private fun List<Int>.toBytes() = ByteArray(size) { this[it].toByte() }
    private fun key(k: Int, p1: Int = 0, label: String) = Cmd(label, listOf(k, p1, 0))

    // ====== command sequences (mirror UDiva4ChairController / UDiva4ManualMassageHelper) ===========
    /** What OSIM sends right after connecting (initMb), before any key operation: two read-only queries. */
    fun initMb(): List<Cmd> = listOf(
        Cmd("讀取主機板版本", emptyList(), header = listOf(0xC2, 0x01)),
        Cmd("讀取程式狀態", emptyList(), header = listOf(0xC6, 0x01, 0x00, 0x00, 0x00)),
    )
    fun release() = key(Key.KEY_OFF, label = "放開")
    fun heartbeat() = key(Key.KEY_OFF, label = "心跳")
    fun hold(p: Posture) = key(p.key, label = p.zh)
    fun pause() = key(Key.PAUSE, label = "暫停")
    fun resume() = key(Key.RESUME, label = "繼續")
    fun stopMassage() = key(Key.STOP_MASSAGE, label = "停止按摩")

    /** OSIM's startAutoMassage: toggle 15/30 min only if the sofa isn't already on it, then start. */
    fun startProgram(p: AutoProgram, is30min: Boolean?, want30: Boolean): List<Cmd> {
        val toggle = if (is30min != null && is30min != want30) listOf(key(Key.TOGGLE_DURATION, label = "切換按摩時間")) else emptyList()
        return toggle + key(p.startKey, label = "啟動${p.zh}")
    }

    /** A manual setting is pressed then released, exactly like OSIM's presetCommands. */
    private fun setting(k: Int, value: Int, label: String) = listOf(key(k, value, label), release())

    private fun settingCmds(s: ManualSettings): List<Cmd> = buildList {
        // Same order as UDiva4ManualMassageHelper.presetCommands.
        addAll(setting(Key.KNEAD_TAP, s.kneadTap.wire, "揉捏敲打 ${s.kneadTap.zh}"))
        addAll(setting(Key.ROLLING, s.rolling.wire, "滾動 ${s.rolling.zh}"))
        addAll(setting(Key.SHOULDER_INTENSITY, s.shoulder, "肩部力度 ${s.shoulder}"))
        addAll(setting(Key.BACK_INTENSITY, s.back, "背部力度 ${s.back}"))
        addAll(setting(Key.LUMBAR_INTENSITY, s.lumbar, "腰部力度 ${s.lumbar}"))
        addAll(setting(Key.BACK_SPEED, s.speed.wire, "速度 ${s.speed.zh}"))
        addAll(setting(Key.SEAT_VIBRATION, s.seatVibration.wire, "臀部振動 ${s.seatVibration.zh}"))
        addAll(setting(Key.FOOT_VIBRATION, s.footVibration.wire, "足部振動 ${s.footVibration.zh}"))
        addAll(setting(Key.FOOT_KNEADING, s.footKneading.wire, "足部揉捏 ${s.footKneading.zh}"))
        addAll(setting(Key.ROLLER_WIDTH, s.width.wire, "寬度 ${s.width.zh}"))
    }

    /** OSIM's startManualMassage: release first, then every setting (manual runs once a function is on). */
    fun startManual(s: ManualSettings): List<Cmd> = listOf(release()) + settingCmds(s)

    /** While manual massage runs, a change is sent on its own (press + release) and takes effect at once. */
    fun manualChanges(prev: ManualSettings, next: ManualSettings): List<Cmd> {
        val before = settingCmds(prev).chunked(2)
        return settingCmds(next).chunked(2).filterIndexed { i, pair -> pair[0] != before[i][0] }.flatten()
    }

    // ====== framing ================================================================================
    private const val SEQ_WRAP = 510                     // OSIM's SequenceNumber resets after 510
    private val seq = AtomicInteger(0)

    fun seqAfter(s: Int): Int = if (s >= SEQ_WRAP) 0 else s + 1
    fun nextSeq(): Int = seq.updateAndGet { seqAfter(it) }

    fun xor(data: ByteArray, len: Int = data.size): Int {
        var c = 0; for (i in 0 until len) c = c xor (data[i].toInt() and 0xFF); return c and 0xFF
    }

    /** `AA 55 | seq LE16 | len LE16 | header | values | xor`, len = header + values + 1 (the xor). */
    fun mbFrame(header: ByteArray, values: ByteArray, seq: Int): ByteArray {
        val len = header.size + values.size + 1
        val out = ByteArray(6 + len)
        out[0] = 0xAA.toByte(); out[1] = 0x55
        out[2] = (seq and 0xFF).toByte(); out[3] = ((seq ushr 8) and 0xFF).toByte()
        out[4] = (len and 0xFF).toByte(); out[5] = ((len ushr 8) and 0xFF).toByte()
        header.copyInto(out, 6)
        values.copyInto(out, 6 + header.size)
        out[out.size - 1] = xor(out, out.size - 1).toByte()
        return out
    }

    fun keyFrame(key: Int, p1: Int = 0, seq: Int = nextSeq()): ByteArray =
        mbFrame(KEY_OP.toBytes(), byteArrayOf(key.toByte(), p1.toByte(), 0), seq)

    /** A mainboard notification that answers our write (status reply or other ack), not a CA report.
     *  Judged on the header only, so a packet truncated by a small MTU is still classified right. */
    fun isReply(b: ByteArray): Boolean =
        b.size >= 9 && (b[0].toInt() and 0xFF) == 0x55 && (b[1].toInt() and 0xFF) == 0xAA &&
            !((b[7].toInt() and 0xFF) == 0xD0 && (b[8].toInt() and 0xFF) == 0xCA)

    /** Verified CDC volume frame: E0 A1 <vol 0..100> 01 <xor>. */
    fun volumeFrame(volPercent: Int): ByteArray {
        val d = byteArrayOf(0xE0.toByte(), 0xA1.toByte(), (volPercent.coerceIn(0, 100)).toByte(), 0x01)
        return d + byteArrayOf(xor(d).toByte())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    fun parseHex(s: String): ByteArray? = try {
        val clean = s.replace("0x", "", true).replace("[^0-9a-fA-F]".toRegex(), "")
        if (clean.length % 2 != 0) null
        else ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (e: Exception) { null }

    fun parseSerialFromNfcUri(uri: String): String? {
        Regex("[?&]productsn=([^&]+)", RegexOption.IGNORE_CASE).find(uri)?.let { return it.groupValues[1] }
        val idx = uri.indexOf("/otonfc", ignoreCase = true)
        if (idx >= 0) {
            val tail = uri.substring(idx + "/otonfc".length).trim('/')
            if (tail.isNotEmpty()) return tail.substringBefore('?').substringBefore('/')
        }
        return null
    }
}
