package xyz.mdblab.z80.mdb

/**
 * Callbacks from the slave to the host app — same shape as
 * `com.mypos.mdbsdk.IPaymentCallback`. The interface is what allows
 * future code to look identical between this lab and myPOS demos.
 */
interface PaymentCallback {

    /**
     * VMC asked to vend `itemNumber` for `itemPriceScaled`. Return value
     * is informational; the host responds asynchronously by calling
     * `MdbCashlessSlave.reportTradeResult(...)`.
     */
    fun onPay(tradeType: VendType, itemPriceScaled: Int, itemNumber: Int): Int = 0

    /** Cashless state changed (the SDK myPOS state machine). */
    fun notifyMdbStatus(status: DeviceStatus) {}

    /** Result of the most recent vend. */
    fun notifyVendResult(result: VendResult) {}

    /** SETUP-time params the VMC pushed (MAX_PRICE/MIN_PRICE/IDLE_MODE). */
    fun notifyVendParam(param: VendParam, data: ByteArray) {}

    /** VMC asked us to run a diagnostic (EXPANSION/0xFF). */
    fun onCallDiagnostics(data: ByteArray) {}

    /** VMC issued a READER CANCEL while in session. */
    fun onReaderCancel() {}

    /** Cash-only sale (coins/bills). Not part of the cashless flow. */
    fun onCashSale(priceScaled: Int, itemNumber: Int) {}

    /** Sniffer hook — every byte rx/tx on the bus, hex-encoded. */
    fun onSniff(direction: SniffDirection, bytes: ByteArray, label: String) {}
}

enum class SniffDirection { RX, TX }
