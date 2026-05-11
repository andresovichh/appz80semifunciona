package xyz.mdblab.z80.mdb

/**
 * State machine vocabulary mirrored 1:1 from the myPOS-MDB-SDK
 * (com.mypos.mdbsdk.*). The SDK proves these are the abstractions a real
 * cashless device exposes; we reuse the names so this lab matches the SDK
 * shape exactly.
 */
enum class DeviceStatus {
    UNKNOWN,
    INACTIVE,
    DISABLE,
    ENABLE,
    SESSION_IDLE,
    VEND,
    VEND_CANCEL,
    VEND_SUCCESS,
    END_SESSION,
    SERVICE_ERR,
    PROCESS_ON_DESTROY,
}

/** Outgoing reports — what the app tells the slave to send to the VMC. */
enum class ReportType {
    TRADE_RESULT,
    SELECTION_REQUEST,
    COUPON_REPORT,
    BEGIN_SESSION,
    CANCEL_SESSION_REQUEST,
    COMMAND_DATA,
    RESET_DEVICE,
    USER_DEFINED_DATA,
    SUPPORT_AGE_VERIFICATION,
    AGE_VERIFICATION_STATUS,
}

/** Type of vend the VMC requested in onPay(). */
enum class VendType { SALE, CASH_SALE, REVALUE }

/** Outcome of a vend attempt as the VMC reports back. */
enum class VendResult { VEND_SUCCESS, VEND_FAILURE, VEND_CANCEL, VEND_END_SESSION }

/** SETUP-time parameters the VMC pushes to the cashless. */
enum class VendParam { MAX_PRICE, MIN_PRICE, IDLE_MODE }

/** Result the app reports back for a TRADE_RESULT. */
enum class TransResult { TRADE_UNKNOWN, TRADE_SUCCESS, TRADE_FAILURE, TRADE_CANCEL }

/** Cashless device operating mode. */
enum class DeviceMode { UNKNOWN, CASHLESS, TRANSMIT }

/** Verbosity for the sniffer. */
enum class LogLevel { OFF, INFO, ALL }
