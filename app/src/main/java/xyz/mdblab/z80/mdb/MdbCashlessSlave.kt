package xyz.mdblab.z80.mdb

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * MDB cashless slave state machine — same vocabulary the myPOS-MDB-SDK
 * exposes (DeviceStatus / ReportType / VendType / VendResult), but the
 * wire protocol below is implemented in-app (the YSDK transport just
 * gives/takes raw bytes, no protocol layer like myPOS' com.mypos.ipp).
 *
 * Key behaviour driven by [PaymentAppInfo] flags:
 *   • responseTime          → Z7 of CONFIG_DATA, controls how long the VMC waits for us
 *   • readerFeatureLevel    → Z2 of CONFIG_DATA (0=auto)
 *   • enableAlwaysIdle      → after ENABLE we permanently arm a session (mirrors myPOS L3)
 *   • supportMultiVend / supportRemoteVend / supportCashSale / requestRefunds / revalueApproved
 *                           → bits of Z8 Misc Options
 *   • respondAckFirst       → ACK first, then payload (some VMCs need it; not yet wired)
 *   • waitEndSessionTm      → seconds to wait before reporting END_SESSION
 *
 * Callbacks via [PaymentCallback] (assignable through [setCallback]):
 *   onPay(...)              when VMC requests a vend
 *   notifyMdbStatus(...)    on each state transition
 *   notifyVendResult(...)   after VMC reports VEND_SUCCESS/FAILURE/CANCEL/SESSION_COMPLETE
 *   notifyVendParam(...)    on SETUP/MAX_MIN_PRICES with each param
 *   onCallDiagnostics(...)  on EXPANSION/DIAGNOSTICS
 *   onReaderCancel()        on READER_CANCEL
 *   onCashSale(...)         on VEND/CASH_SALE
 *   onSniff(dir, bytes, lbl) every byte that crossed the bus
 */
class MdbCashlessSlave(
    private val transport: Transport,
    @Volatile var config: PaymentAppInfo = PaymentAppInfo(),
) {

    interface Transport {
        /** Push the (already-checksummed if needed) frame to the AIDL. */
        fun send(frame: ByteArray)
    }

    @Volatile
    private var callback: PaymentCallback = object : PaymentCallback {}
    fun setCallback(cb: PaymentCallback) { callback = cb }

    // ─── State machine ────────────────────────────────────────────────────

    @Volatile
    var status: DeviceStatus = DeviceStatus.UNKNOWN
        private set

    private fun setStatus(s: DeviceStatus) {
        if (status != s) {
            status = s
            if (config.notifyStatus) {
                try { callback.notifyMdbStatus(s) } catch (e: Exception) { Log.e(TAG, "notifyMdbStatus", e) }
            }
        }
    }

    // ─── Pending poll responses (priority ladder, same as upstream) ──────

    private val cashlessResetTodo = AtomicBoolean(false)
    private val sessionCancelTodo = AtomicBoolean(false)
    private val sessionEndTodo = AtomicBoolean(false)
    private val vendApprovedTodo = AtomicBoolean(false)
    private val vendDeniedTodo = AtomicBoolean(false)
    private val outOfSequenceTodo = AtomicBoolean(false)

    /**
     * Single-slot session arm. -1 == nothing pending, otherwise contains
     * the funds (scaled) the next POLL should emit as BEGIN_SESSION.
     */
    private val pendingFunds = AtomicReference<Int>(NONE)

    @Volatile private var fundsAvailable: Int = 0
    @Volatile private var itemPrice: Int = 0
    @Volatile private var itemNumber: Int = 0
    @Volatile private var sessionBeginTimeSec: Long = 0

    /** Last MAX/MIN price the VMC declared in SETUP/MAX_MIN_PRICES. */
    @Volatile var lastMaxPrice: Int = -1
        private set
    @Volatile var lastMinPrice: Int = -1
        private set

    // ─── Public API (= myPOS reportToVMC equivalent) ─────────────────────

    /**
     * Mirror of myPOS `MDBManager.reportToVMC(json)`. Accepts the same
     * JSON shape so calling code looks identical.
     */
    fun reportToVMC(json: String) {
        val obj = JSONObject(json)
        val type = ReportType.values()[obj.getInt(ReportKeys.TYPE)]
        when (type) {
            ReportType.BEGIN_SESSION -> {
                val funds = parseHexAmount(obj.getString(ReportKeys.FUNDS))
                pendingFunds.set(funds)
            }
            ReportType.CANCEL_SESSION_REQUEST -> sessionCancelTodo.set(true)
            ReportType.TRADE_RESULT -> {
                val tr = TransResult.values()[obj.getInt(ReportKeys.TRADE_RESULT)]
                when (tr) {
                    TransResult.TRADE_SUCCESS -> {
                        if (obj.has(ReportKeys.TRANS_RESULT_AMOUNT)) {
                            itemPrice = parseHexAmount(obj.getString(ReportKeys.TRANS_RESULT_AMOUNT))
                        }
                        vendApprovedTodo.set(true)
                    }
                    TransResult.TRADE_FAILURE,
                    TransResult.TRADE_CANCEL,
                    TransResult.TRADE_UNKNOWN -> vendDeniedTodo.set(true)
                }
            }
            ReportType.RESET_DEVICE -> cashlessResetTodo.set(true)
            else -> Log.w(TAG, "report type $type not handled yet")
        }
    }

    /**
     * Convenience: arm a session with funds expressed as scaled int.
     * Frame is sent IMMEDIATELY because the Morefun MDB chip manages
     * POLL replies internally and only notifies us of higher-level
     * commands (RESET/SETUP/READER/VEND/EXPANSION). Anything we send
     * after READER_ENABLE gets queued by the AIDL and emitted on the
     * next bus POLL automatically.
     */
    fun beginSession(fundsScaled: Int) {
        require(fundsScaled in 0..0xFFFF) { "funds out of range" }
        pendingFunds.set(fundsScaled)
        emitBeginSessionIfArmed()
    }

    fun approveVend(amountScaled: Int = itemPrice) {
        itemPrice = amountScaled
        vendApprovedTodo.set(false)
        transmit(byteArrayOf(
            MdbWire.POLL_VEND_APPROVED.toByte(),
            (itemPrice ushr 8 and 0xFF).toByte(),
            (itemPrice and 0xFF).toByte(),
        ))
    }

    fun denyVend() {
        vendDeniedTodo.set(false)
        setStatus(DeviceStatus.SESSION_IDLE)
        transmit(byteArrayOf(MdbWire.POLL_VEND_DENIED.toByte()))
    }

    fun cancelSession() {
        sessionCancelTodo.set(false)
        transmit(byteArrayOf(MdbWire.POLL_SESSION_CANCEL_REQ.toByte()))
    }

    private fun emitBeginSessionIfArmed() {
        if (status > DeviceStatus.ENABLE) return
        val funds = pendingFunds.getAndSet(NONE)
        if (funds == NONE) return
        fundsAvailable = funds
        setStatus(DeviceStatus.SESSION_IDLE)
        sessionBeginTimeSec = nowSec()
        emitBeginSession(funds)
    }

    /** Raw emit — used by both initial arm and heartbeat. */
    private fun emitBeginSession(funds: Int) {
        transmit(byteArrayOf(
            MdbWire.POLL_BEGIN_SESSION.toByte(),
            (funds ushr 8 and 0xFF).toByte(),
            (funds and 0xFF).toByte(),
        ))
    }

    /**
     * Re-emit BEGIN_SESSION as a keep-alive. Some VMCs (this Z80's
     * included) drop the cashless if no "interesting" frame arrives in
     * a few seconds. Driven by an external timer in MdbService.
     */
    fun heartbeatTick() {
        if (status == DeviceStatus.SESSION_IDLE && fundsAvailable > 0) {
            emitBeginSession(fundsAvailable)
        }
    }

    // ─── Inbound packet entry point ──────────────────────────────────────

    /**
     * Called from the AIDL onRecv callback with each MDB packet. The
     * byte at rx[0] is the (address|command). YSDK has already stripped
     * the 9-bit mode bit from the wire.
     */
    fun handlePacket(rx: ByteArray) {
        if (rx.isEmpty()) return
        callback.onSniff(SniffDirection.RX, rx, decodeRx(rx))

        when (rx[0].toInt() and 0xFF) {
            MdbWire.ACK, MdbWire.NAK, MdbWire.RET -> return
            MdbWire.CMD_RESET -> handleReset()
            MdbWire.CMD_SETUP -> handleSetup(rx)
            MdbWire.CMD_POLL -> handlePoll()
            MdbWire.CMD_VEND -> handleVend(rx)
            MdbWire.CMD_READER -> handleReader(rx)
            MdbWire.CMD_EXPANSION -> handleExpansion(rx)
            else -> Unit                                         // not for us
        }
        // No "re-arm on every handshake packet" hook here. MDB spec §7.3:
        // emitting an unrequested response (e.g. BEGIN_SESSION mid-SETUP)
        // is "out of sequence" → VMC issues RESET. Sessions are armed
        // only in handleReader(READER_ENABLE).
    }

    // ─── Wire-level handlers ─────────────────────────────────────────────

    private fun handleReset() {
        cashlessResetTodo.set(true)
        pendingFunds.set(NONE)
        clearSessionFlags()
        setStatus(DeviceStatus.INACTIVE)
        // MUST respond to the next POLL with JUST_RESET (0x00)
        transmit(byteArrayOf(MdbWire.POLL_JUST_RESET.toByte()))
    }

    private fun handleSetup(rx: ByteArray) {
        if (rx.size < 2) return
        when (rx[1].toInt() and 0xFF) {
            MdbWire.SETUP_CONFIG_DATA -> {
                // VMC declares: vmcFeatureLevel, columns, rows, displayInfo (rx[2..5]).
                // We don't currently need them — but they're in rx if you do.
                setStatus(DeviceStatus.DISABLE)
                transmit(buildReaderConfigData())
            }
            MdbWire.SETUP_MAX_MIN_PRICES -> {
                if (rx.size >= 6) {
                    lastMaxPrice = ((rx[2].toInt() and 0xFF) shl 8) or (rx[3].toInt() and 0xFF)
                    lastMinPrice = ((rx[4].toInt() and 0xFF) shl 8) or (rx[5].toInt() and 0xFF)
                    callback.notifyVendParam(VendParam.MAX_PRICE, byteArrayOf(rx[2], rx[3]))
                    callback.notifyVendParam(VendParam.MIN_PRICE, byteArrayOf(rx[4], rx[5]))
                }
            }
        }
    }

    private fun handlePoll() {
        // Priority ladder (1:1 with upstream nightly + always-idle hook).
        when {
            cashlessResetTodo.compareAndSet(true, false) ->
                transmit(byteArrayOf(MdbWire.POLL_JUST_RESET.toByte()))

            status <= DeviceStatus.ENABLE && pendingFunds.get() != NONE -> {
                val funds = pendingFunds.getAndSet(NONE)
                fundsAvailable = funds
                setStatus(DeviceStatus.SESSION_IDLE)
                sessionBeginTimeSec = nowSec()
                transmit(byteArrayOf(
                    MdbWire.POLL_BEGIN_SESSION.toByte(),
                    (funds ushr 8 and 0xFF).toByte(),
                    (funds and 0xFF).toByte(),
                ))
            }

            sessionCancelTodo.compareAndSet(true, false) ->
                transmit(byteArrayOf(MdbWire.POLL_SESSION_CANCEL_REQ.toByte()))

            vendApprovedTodo.compareAndSet(true, false) ->
                transmit(byteArrayOf(
                    MdbWire.POLL_VEND_APPROVED.toByte(),
                    (itemPrice ushr 8 and 0xFF).toByte(),
                    (itemPrice and 0xFF).toByte(),
                ))

            vendDeniedTodo.compareAndSet(true, false) -> {
                setStatus(DeviceStatus.SESSION_IDLE)
                transmit(byteArrayOf(MdbWire.POLL_VEND_DENIED.toByte()))
            }

            sessionEndTodo.compareAndSet(true, false) -> {
                setStatus(DeviceStatus.END_SESSION)
                if (config.enableAlwaysIdle) {
                    pendingFunds.set(0xFFFF)                     // stay armed
                    setStatus(DeviceStatus.ENABLE)
                } else {
                    setStatus(DeviceStatus.ENABLE)
                }
                transmit(byteArrayOf(MdbWire.POLL_END_SESSION.toByte()))
            }

            outOfSequenceTodo.compareAndSet(true, false) ->
                transmit(byteArrayOf(MdbWire.POLL_OUT_OF_SEQUENCE.toByte()))

            else -> {
                if (status >= DeviceStatus.SESSION_IDLE) {
                    val elapsed = nowSec() - sessionBeginTimeSec
                    if (elapsed > config.waitEndSessionTm.coerceAtLeast(30)) {
                        sessionCancelTodo.set(true)
                    }
                }
            }
        }
    }

    private fun handleVend(rx: ByteArray) {
        if (rx.size < 2) return
        when (rx[1].toInt() and 0xFF) {
            MdbWire.VEND_REQUEST -> {
                if (rx.size < 6) return
                itemPrice = ((rx[2].toInt() and 0xFF) shl 8) or (rx[3].toInt() and 0xFF)
                itemNumber = ((rx[4].toInt() and 0xFF) shl 8) or (rx[5].toInt() and 0xFF)
                setStatus(DeviceStatus.VEND)
                callback.onPay(VendType.SALE, itemPrice, itemNumber)

                // Opción B (Modificada): Si el saldo es infinito (0xFFFF) o alcanza para pagar,
                // auto-aprobamos al instante. Si no, NO HACEMOS NADA y dejamos que la VMC espere.
                // La VMC se quedará en "Procesando..." durante su timeout de hardware
                // (que suele ser entre 5 y 30 segundos según la máquina) dándole
                // tiempo al usuario para escanear y a la UI de Android para mandar approveVend().
                // BLOQUE COMENTADO:
                // Ya no auto-aprobamos nunca (ni siquiera con 0xFFFF).
                // La VMC siempre se quedará en "Procesando..." y dependerá
                // de que llames a approveVend() desde tu lógica de negocio.
                /*
                when {
                    fundsAvailable == 0xFFFF || itemPrice <= fundsAvailable -> {
                        transmit(byteArrayOf(
                            MdbWire.POLL_VEND_APPROVED.toByte(),
                            (itemPrice ushr 8 and 0xFF).toByte(),
                            (itemPrice and 0xFF).toByte(),
                        ))
                    }
                }
                */
            }
            MdbWire.VEND_CANCEL -> {
                vendDeniedTodo.set(true)
                callback.notifyVendResult(VendResult.VEND_CANCEL)
            }
            MdbWire.VEND_SUCCESS -> {
                if (rx.size >= 4) {
                    itemNumber = ((rx[2].toInt() and 0xFF) shl 8) or (rx[3].toInt() and 0xFF)
                }
                setStatus(DeviceStatus.VEND_SUCCESS)
                callback.notifyVendResult(VendResult.VEND_SUCCESS)
                setStatus(DeviceStatus.SESSION_IDLE)
                if (config.enableAlwaysIdle) {
                    emitBeginSession(fundsAvailable)
                }
            }
            MdbWire.VEND_FAILURE -> {
                callback.notifyVendResult(VendResult.VEND_FAILURE)
                setStatus(DeviceStatus.SESSION_IDLE)
                if (config.enableAlwaysIdle) {
                    emitBeginSession(fundsAvailable)
                }
            }
            MdbWire.SESSION_COMPLETE -> {
                sessionEndTodo.set(true)
                callback.notifyVendResult(VendResult.VEND_END_SESSION)
                transmit(byteArrayOf(MdbWire.POLL_END_SESSION.toByte()))
                if (config.enableAlwaysIdle) {
                    emitBeginSession(fundsAvailable)
                }
            }
            MdbWire.CASH_SALE -> {
                if (rx.size >= 6) {
                    val price = ((rx[2].toInt() and 0xFF) shl 8) or (rx[3].toInt() and 0xFF)
                    val item = ((rx[4].toInt() and 0xFF) shl 8) or (rx[5].toInt() and 0xFF)
                    callback.onCashSale(price, item)
                }
                transmitAck()
            }
        }
    }

    private fun handleReader(rx: ByteArray) {
        if (rx.size < 2) return
        when (rx[1].toInt() and 0xFF) {
            MdbWire.READER_DISABLE -> {
                setStatus(DeviceStatus.DISABLE)
                transmitAck()
            }
            MdbWire.READER_ENABLE -> {
                setStatus(DeviceStatus.ENABLE)
                if (config.enableAlwaysIdle) {
                    val funds = config.defaultIdleFunds
                    fundsAvailable = funds
                    sessionBeginTimeSec = nowSec()
                    setStatus(DeviceStatus.SESSION_IDLE)
                    emitBeginSession(funds) // INLINE response, no ACK!
                }
            }
            MdbWire.READER_CANCEL -> {
                callback.onReaderCancel()
                transmit(byteArrayOf(MdbWire.POLL_CANCELLED.toByte()))
            }
        }
    }

    private fun handleExpansion(rx: ByteArray) {
        if (rx.size < 2) return
        when (rx[1].toInt() and 0xFF) {
            MdbWire.EXPANSION_REQUEST_ID -> transmit(buildPeripheralIdResponse())
            MdbWire.EXPANSION_DIAGNOSTICS -> {
                val diagBytes = if (rx.size > 2) rx.copyOfRange(2, rx.size) else ByteArray(0)
                callback.onCallDiagnostics(diagBytes)
            }
        }
    }

    // ─── Frame builders ─────────────────────────────────────────────────

    /** Reader Config Data — Z1..Z8 with checksum. */
    private fun buildReaderConfigData(): ByteArray {
        val cc = config.countryCode
        val payload = byteArrayOf(
            MdbWire.POLL_READER_CONFIG.toByte(),                  // Z1
            config.readerFeatureLevel,                            // Z2
            (cc ushr 8 and 0xFF).toByte(),                        // Z3 country hi
            (cc and 0xFF).toByte(),                               // Z4 country lo
            config.scaleFactor.toByte(),                          // Z5
            config.decimalPlaces,                                 // Z6
            0xFF.toByte(),                                        // Z7 max response time (255 segundos forzados)
            config.optionsByte ?: computeMiscOptions(),           // Z8 misc options
        )
        return payload                                            // transmit() adds checksum
    }

    private fun computeMiscOptions(): Byte {
        var b = 0
        if (config.requestRefunds)   b = b or 0b00000001          // bit 0: refunds
        if (config.supportMultiVend) b = b or 0b00000010          // bit 1: multi-vend
        if (config.revalueApproved)  b = b or 0b00000100          // bit 2: revalue
        if (config.supportRemoteVend)b = b or 0b00001000          // bit 3: remote vend
        // myPOS default ends up as 0b00001001 in some configs; we let the
        // host override entirely via PaymentAppInfo.optionsByte if needed.
        return b.toByte()
    }

    /** Peripheral ID response (REQUEST_ID). */
    private fun buildPeripheralIdResponse(): ByteArray {
        val payload = ByteArray(30)
        payload[0] = MdbWire.POLL_PERIPHERAL_ID.toByte()
        // Manufacturer code (3 chars)
        val mfg = config.manufactureCode.padEnd(3).take(3)
        for (i in 0..2) payload[1 + i] = mfg[i].code.toByte()
        // Serial number (12 chars from clientId, space-padded)
        val serial = config.clientId.padEnd(12).take(12)
        for (i in 0..11) payload[4 + i] = serial[i].code.toByte()
        // Model (12 chars)
        val model = config.model.padEnd(12).take(12)
        for (i in 0..11) payload[16 + i] = model[i].code.toByte()
        // Software version: 0x00 0x03 (v0.3) — matches the already-vending
        // Z80_Credit_App and the ESP32 vmflow nightly.
        payload[28] = 0x00
        payload[29] = 0x03
        return payload
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun transmit(payload: ByteArray) {
        val frame = MdbWire.framed(payload)
        callback.onSniff(SniffDirection.TX, frame, decodeTx(frame))
        transport.send(frame)
    }

    private fun transmitAck() {
        val frame = byteArrayOf(MdbWire.ACK.toByte())
        callback.onSniff(SniffDirection.TX, frame, "ACK")
        transport.send(frame)
    }

    private fun clearSessionFlags() {
        sessionCancelTodo.set(false)
        sessionEndTodo.set(false)
        vendApprovedTodo.set(false)
        vendDeniedTodo.set(false)
        outOfSequenceTodo.set(false)
    }

    private fun decodeRx(rx: ByteArray): String {
        if (rx.isEmpty()) return "(empty)"
        val b0 = rx[0].toInt() and 0xFF
        val sub = if (rx.size >= 2) rx[1].toInt() and 0xFF else -1
        return when (b0) {
            MdbWire.ACK -> "ACK"
            MdbWire.NAK -> "NAK"
            MdbWire.RET -> "RET"
            MdbWire.CMD_RESET -> "RESET"
            MdbWire.CMD_SETUP -> when (sub) {
                MdbWire.SETUP_CONFIG_DATA -> "SETUP/CONFIG_DATA"
                MdbWire.SETUP_MAX_MIN_PRICES -> "SETUP/MAX_MIN_PRICES"
                else -> "SETUP/0x%02X".format(sub)
            }
            MdbWire.CMD_POLL -> "POLL"
            MdbWire.CMD_VEND -> when (sub) {
                MdbWire.VEND_REQUEST -> "VEND/REQUEST"
                MdbWire.VEND_CANCEL -> "VEND/CANCEL"
                MdbWire.VEND_SUCCESS -> "VEND/SUCCESS"
                MdbWire.VEND_FAILURE -> "VEND/FAILURE"
                MdbWire.SESSION_COMPLETE -> "VEND/SESSION_COMPLETE"
                MdbWire.CASH_SALE -> "VEND/CASH_SALE"
                else -> "VEND/0x%02X".format(sub)
            }
            MdbWire.CMD_READER -> when (sub) {
                MdbWire.READER_DISABLE -> "READER/DISABLE"
                MdbWire.READER_ENABLE -> "READER/ENABLE"
                MdbWire.READER_CANCEL -> "READER/CANCEL"
                else -> "READER/0x%02X".format(sub)
            }
            MdbWire.CMD_EXPANSION -> when (sub) {
                MdbWire.EXPANSION_REQUEST_ID -> "EXPANSION/REQUEST_ID"
                MdbWire.EXPANSION_DIAGNOSTICS -> "EXPANSION/DIAGNOSTICS"
                else -> "EXPANSION/0x%02X".format(sub)
            }
            else -> "RX 0x%02X".format(b0)
        }
    }

    private fun decodeTx(tx: ByteArray): String {
        if (tx.isEmpty()) return "(empty)"
        return when (tx[0].toInt() and 0xFF) {
            MdbWire.POLL_JUST_RESET -> if (tx.size == 1) "ACK" else "JUST_RESET"
            MdbWire.POLL_READER_CONFIG -> "READER_CONFIG"
            MdbWire.POLL_BEGIN_SESSION -> "BEGIN_SESSION"
            MdbWire.POLL_SESSION_CANCEL_REQ -> "SESSION_CANCEL_REQ"
            MdbWire.POLL_VEND_APPROVED -> "VEND_APPROVED"
            MdbWire.POLL_VEND_DENIED -> "VEND_DENIED"
            MdbWire.POLL_END_SESSION -> "END_SESSION"
            MdbWire.POLL_CANCELLED -> "READER_CANCELLED"
            MdbWire.POLL_PERIPHERAL_ID -> "PERIPHERAL_ID"
            MdbWire.POLL_OUT_OF_SEQUENCE -> "OUT_OF_SEQUENCE"
            else -> "TX"
        }
    }

    private fun parseHexAmount(hex: String): Int = hex.toLong(16).toInt() and 0xFFFF

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    companion object {
        private const val TAG = "MdbCashlessSlave"
        private const val NONE: Int = -1
    }
}

/** JSON keys matching myPOS `ReportKey.*` so reportToVMC payloads stay 1:1. */
object ReportKeys {
    const val TYPE = "TYPE"
    const val TRADE_RESULT = "TRADE_RESULT"
    const val FUNDS = "FUNDS"
    const val ITEMNUMBER = "ITEMNUMBER"
    const val ITEMOPTION = "ITEMOPTION"
    const val COMMAND_DATA = "COMMAND_DATA"
    const val TRANS_RESULT_AMOUNT = "TRANS_RESULT_AMOUNT"
    const val USER_DEFINED_DATA = "USER_DEFINED_DATA"
}
