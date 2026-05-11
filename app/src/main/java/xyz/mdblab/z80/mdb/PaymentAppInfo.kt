package xyz.mdblab.z80.mdb

/**
 * Cashless device config — same shape as the myPOS-MDB-SDK
 * `com.mypos.mdbsdk.PaymentAppInfo`. Defaults match what that SDK applies
 * by default in its demo (see MDBService.java#initData) because those
 * values are known to make a real cashless device behave on a real VMC.
 */
data class PaymentAppInfo(
    /**
     * Terminal id (= MDB peripheral serial in REQUEST_ID response). The
     * already-vending Z80_Credit_App leaves this as 12 spaces, mirroring
     * the ESP32 vmflow nightly. We default to the same.
     */
    var clientId: String = "            ",
    /**
     * Model field in REQUEST_ID. The already-vending Z80_Credit_App uses
     * 12 spaces (same as ESP32 nightly), not "Z80-UPT".
     */
    var model: String = "            ",
    /**
     * 3-char manufacturer code in REQUEST_ID. The already-vending
     * Z80_Credit_App uses "VMF" (vmflow), not "XGD".
     */
    var manufactureCode: String = "VMF",

    /**
     * Country / currency code reported in CONFIG_DATA Z3-Z4. The original
     * Z80_Tablet_Replacement (which already vends on this VCS MCS 1080
     * VMC) uses 0xFFFF — comment in that code: "Any currency, clave
     * para que tome el saldo". Waferstar manual ships 0x0972 but that's
     * for a different generic VMC.
     */
    var countryCode: Int = 0xFFFF,

    /** Z6 — 0x02 typically (cents). 0x00 if currency has no fractional unit. */
    var decimalPlaces: Byte = 0x02,
    /** Z5 — typically 1. */
    var scaleFactor: Int = 1,

    /**
     * Z7 — Maximum response time in seconds. The original
     * Z80_Tablet_Replacement (already vending on this VMC) uses 0x03
     * (3 s, same as ESP32 nightly). Waferstar manual ships 0x07.
     */
    var responseTime: Byte = 0x03,

    /** Sniffer verbosity. */
    var logLevel: LogLevel = LogLevel.INFO,

    /**
     * Z2 — Reader Feature Level. Waferstar MDB-RS232 manual ships 0x01
     * (Level 1 forced).
     */
    var readerFeatureLevel: Byte = 0x01,

    /**
     * Level 3 / auto: keep the cashless permanently in idle (=session
     * always armed) so the VMC doesn't enter the RESET → SETUP →
     * READER_ENABLE handshake repeatedly when no card is present. Maps
     * to myPOS `enableAlwaysIdle`.
     */
    var enableAlwaysIdle: Boolean = true,

    /** 0 = 16-bit monetary format, 1 = 32-bit. Only L3 supports 32-bit. */
    var monetaryFormat: Int = 0,

    /**
     * Z8 bit 2. Original Z80_Tablet_Replacement uses 0x09 (no revalue
     * bit), Waferstar manual uses 0x0D. We mirror the original since it
     * vends on this exact VMC.
     */
    var revalueApproved: Boolean = false,
    /** Default true: most VMCs require this bit (0) of Z8 to accept the cashless. */
    var requestRefunds: Boolean = true,

    var deviceMode: DeviceMode = DeviceMode.CASHLESS,

    /** Push DeviceStatus changes through PaymentCallback.notifyMdbStatus. */
    var notifyStatus: Boolean = true,

    /** Whether to ACK before the data response (some VMCs require it). */
    var respondAckFirst: Boolean = false,

    var supportCashSale: Boolean = false,

    /** Slave delay before booting the state machine (ms). */
    var delayTm: Int = 0,

    /** Seconds to wait after VEND_SUCCESS/FAILURE before reporting END_SESSION. */
    var waitEndSessionTm: Int = 30,

    var supportMultiVend: Boolean = false,
    /** Default true: bit 3 of Z8 — upstream nightly uses 0b00001001 = 0x09. */
    var supportRemoteVend: Boolean = true,

    /**
     * Funds (scaled) to silently arm the session with on READER_ENABLE
     * when [enableAlwaysIdle] is on. 0xFFFF is the MDB-Cashless magic
     * value for "unlimited credit / always-approve" — the VMC keeps
     * the session open indefinitely instead of applying its short
     * "select-a-product" timeout. The Waferstar fork uses this exact
     * value (`03 FF FF 01` on the wire). Use a real number only if
     * you want the VMC to enforce a balance display.
     */
    var defaultIdleFunds: Int = 0xFFFF,

    /** Z8 Misc Options. Spec defines these bits — `optionsByte` overrides
     *  the value computed from the booleans above when non-null. */
    var optionsByte: Byte? = null,
)
