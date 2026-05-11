package xyz.mdblab.z80.data

import android.content.Context
import xyz.mdblab.z80.mdb.DeviceMode
import xyz.mdblab.z80.mdb.LogLevel
import xyz.mdblab.z80.mdb.PaymentAppInfo

/**
 * Persistence wrapper around PaymentAppInfo. Lets the user tweak each
 * MDB-spec flag from the Settings screen and survives app restarts.
 * Default values match the myPOS-MDB-SDK demo defaults.
 */
class Settings(ctx: Context) {

    private val prefs = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): PaymentAppInfo = PaymentAppInfo(
        clientId = prefs.getString(K_CLIENT_ID, "            ")!!,
        model = prefs.getString(K_MODEL, "            ")!!,
        manufactureCode = prefs.getString(K_MFG, "VMF")!!,
        countryCode = prefs.getInt(K_COUNTRY, 0xFFFF),
        decimalPlaces = prefs.getInt(K_DECIMALS, 0x02).toByte(),
        scaleFactor = prefs.getInt(K_SCALE, 1),
        responseTime = prefs.getInt(K_RESPONSE_TIME, 0x03).toByte(),
        readerFeatureLevel = prefs.getInt(K_FEATURE_LEVEL, 0x01).toByte(),
        enableAlwaysIdle = prefs.getBoolean(K_ALWAYS_IDLE, true),
        monetaryFormat = prefs.getInt(K_MONETARY, 0),
        revalueApproved = prefs.getBoolean(K_REVALUE, false),
        requestRefunds = prefs.getBoolean(K_REFUNDS, true),
        deviceMode = DeviceMode.values()[prefs.getInt(K_DEVICE_MODE, DeviceMode.CASHLESS.ordinal)],
        notifyStatus = prefs.getBoolean(K_NOTIFY, true),
        respondAckFirst = prefs.getBoolean(K_ACK_FIRST, false),
        supportCashSale = prefs.getBoolean(K_CASH_SALE, false),
        delayTm = prefs.getInt(K_DELAY, 0),
        waitEndSessionTm = prefs.getInt(K_WAIT_END, 30),
        supportMultiVend = prefs.getBoolean(K_MULTI_VEND, false),
        supportRemoteVend = prefs.getBoolean(K_REMOTE_VEND, true),
        defaultIdleFunds = prefs.getInt(K_DEFAULT_FUNDS, 0xFFFF),
        logLevel = LogLevel.values()[prefs.getInt(K_LOG, LogLevel.INFO.ordinal)],
        optionsByte = prefs.getInt(K_OPTIONS_OVERRIDE, -1).let { if (it < 0) null else it.toByte() },
    )

    fun save(c: PaymentAppInfo) {
        prefs.edit().apply {
            putString(K_CLIENT_ID, c.clientId)
            putString(K_MODEL, c.model)
            putString(K_MFG, c.manufactureCode)
            putInt(K_COUNTRY, c.countryCode)
            putInt(K_DECIMALS, c.decimalPlaces.toInt() and 0xFF)
            putInt(K_SCALE, c.scaleFactor)
            putInt(K_RESPONSE_TIME, c.responseTime.toInt() and 0xFF)
            putInt(K_FEATURE_LEVEL, c.readerFeatureLevel.toInt() and 0xFF)
            putBoolean(K_ALWAYS_IDLE, c.enableAlwaysIdle)
            putInt(K_MONETARY, c.monetaryFormat)
            putBoolean(K_REVALUE, c.revalueApproved)
            putBoolean(K_REFUNDS, c.requestRefunds)
            putInt(K_DEVICE_MODE, c.deviceMode.ordinal)
            putBoolean(K_NOTIFY, c.notifyStatus)
            putBoolean(K_ACK_FIRST, c.respondAckFirst)
            putBoolean(K_CASH_SALE, c.supportCashSale)
            putInt(K_DELAY, c.delayTm)
            putInt(K_WAIT_END, c.waitEndSessionTm)
            putBoolean(K_MULTI_VEND, c.supportMultiVend)
            putBoolean(K_REMOTE_VEND, c.supportRemoteVend)
            putInt(K_DEFAULT_FUNDS, c.defaultIdleFunds)
            putInt(K_LOG, c.logLevel.ordinal)
            putInt(K_OPTIONS_OVERRIDE, c.optionsByte?.let { it.toInt() and 0xFF } ?: -1)
        }.apply()
    }

    private companion object {
        const val NAME = "mdblab"
        const val K_CLIENT_ID = "clientId"
        const val K_MODEL = "model"
        const val K_MFG = "manufactureCode"
        const val K_COUNTRY = "countryCode"
        const val K_DECIMALS = "decimalPlaces"
        const val K_SCALE = "scaleFactor"
        const val K_RESPONSE_TIME = "responseTime"
        const val K_FEATURE_LEVEL = "readerFeatureLevel"
        const val K_ALWAYS_IDLE = "enableAlwaysIdle"
        const val K_MONETARY = "monetaryFormat"
        const val K_REVALUE = "revalueApproved"
        const val K_REFUNDS = "requestRefunds"
        const val K_DEVICE_MODE = "deviceMode"
        const val K_NOTIFY = "notifyStatus"
        const val K_ACK_FIRST = "respondAckFirst"
        const val K_CASH_SALE = "supportCashSale"
        const val K_DELAY = "delayTm"
        const val K_WAIT_END = "waitEndSessionTm"
        const val K_MULTI_VEND = "supportMultiVend"
        const val K_REMOTE_VEND = "supportRemoteVend"
        const val K_LOG = "logLevel"
        const val K_OPTIONS_OVERRIDE = "optionsOverride"
        const val K_DEFAULT_FUNDS = "defaultIdleFunds"
    }
}
