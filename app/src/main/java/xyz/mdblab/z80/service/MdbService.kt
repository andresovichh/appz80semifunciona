package xyz.mdblab.z80.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.morefun.yapi.device.mdb.IMdbService
import com.morefun.yapi.device.mdb.IRecvCallback
import kotlinx.coroutines.*
import xyz.mdblab.z80.App
import xyz.mdblab.z80.data.Settings
import xyz.mdblab.z80.mdb.*

/**
 * Foreground service that owns the YSDK MDB AIDL pipe and feeds packets
 * into MdbCashlessSlave. Re-broadcasts every event (state, vend, sniff)
 * to the UI.
 *
 * Commands accepted on ACTION_VM_COMMAND:
 *   "BEGIN_SESSION"  + "funds" (int)
 *   "CANCEL_SESSION"
 *   "TRADE_RESULT"   + "result" (TransResult.ordinal) + "amount" (int)
 *   "RELOAD_CONFIG"
 */
class MdbService : Service() {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    @Volatile private var mdb: IMdbService? = null
    private lateinit var slave: MdbCashlessSlave
    private lateinit var settings: Settings
    private var commandReceiver: BroadcastReceiver? = null

    /**
     * Append-only log file. Survives logcat rotation. Pull with:
     *   adb pull /sdcard/Android/data/xyz.mdblab.z80/files/mdblab.log
     * Or, for the older-style world-readable path:
     *   adb pull /sdcard/Download/mdblab.log
     */
    private var logFile: File? = null
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private fun fileLog(line: String) {
        try {
            val f = logFile ?: return
            FileWriter(f, true).use { it.appendLine("${tsFmt.format(Date())}  $line") }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("MDB Lab starting…"))

        // Initialise the file log first so we don't lose the very first
        // events. Truncate on each service start so the file holds one
        // session worth of MDB traffic.
        try {
            logFile = File(getExternalFilesDir(null), "mdblab.log").also { it.writeText("") }
            // Try also a copy on /sdcard/Download for easier pulling.
            val downloads = File("/sdcard/Download")
            if (downloads.exists() || downloads.mkdirs()) {
                File(downloads, "mdblab.log").writeText("")
            }
        } catch (_: Exception) {}
        fileLog("== service start ==")

        settings = Settings(this)
        slave = MdbCashlessSlave(
            transport = object : MdbCashlessSlave.Transport {
                override fun send(frame: ByteArray) {
                    try {
                        mdb?.send(frame, frame.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "transport.send failed", e)
                    }
                }
            },
            config = settings.load(),
        )
        slave.setCallback(object : PaymentCallback {
            override fun onPay(tradeType: VendType, itemPriceScaled: Int, itemNumber: Int): Int {
                broadcast("PAY", "type=$tradeType,price=$itemPriceScaled,item=$itemNumber")
                return 0
            }
            override fun notifyMdbStatus(status: DeviceStatus) {
                broadcast("STATUS", status.name)
            }
            override fun notifyVendResult(result: VendResult) {
                broadcast("VEND_RESULT", result.name)
            }
            override fun notifyVendParam(param: VendParam, data: ByteArray) {
                broadcast("VEND_PARAM", "$param=${MdbWire.hex(data)}")
            }
            override fun onCallDiagnostics(data: ByteArray) {
                broadcast("DIAGNOSTICS", MdbWire.hex(data))
            }
            override fun onReaderCancel() {
                broadcast("READER_CANCEL", "")
            }
            override fun onCashSale(priceScaled: Int, itemNumber: Int) {
                broadcast("CASH_SALE", "price=$priceScaled,item=$itemNumber")
            }
            override fun onSniff(direction: SniffDirection, bytes: ByteArray, label: String) {
                val arrow = if (direction == SniffDirection.RX) "↓" else "↑"
                val line = "$arrow ${MdbWire.hex(bytes)}  $label"
                Log.i("MdbSniff", line)
                fileLog(line)
                broadcast("SNIFF", line)
            }
        })

        registerCommandReceiver()
        scope.launch { connectAndAttach() }
        scope.launch { startHeartbeat() }
    }

    private suspend fun startHeartbeat() {
        while (scope.isActive) {
            delay(2000)
            if (this::slave.isInitialized) {
                slave.heartbeatTick()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun connectAndAttach() {
        var retries = 0
        while (App.instance.engine == null && retries < 20) {
            delay(1000); retries++
        }
        val engine = App.instance.engine ?: run {
            broadcast("DIAG", "YSDK not available")
            return
        }
        mdb = engine.mdbService
        val ret = try { mdb?.connect(MDB_DEV_PATH) ?: -1 } catch (e: Exception) { -1 }
        if (ret != 0) {
            broadcast("DIAG", "mdb.connect failed ret=$ret")
            return
        }
        broadcast("DIAG", "MDB attached @ $MDB_DEV_PATH")
        try {
            mdb?.registerRecv(object : IRecvCallback.Stub() {
                override fun onRecv(data: ByteArray, len: Int) {
                    val effective = data.copyOfRange(0, len.coerceAtMost(data.size))
                    slave.handlePacket(effective)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "registerRecv failed", e)
        }
    }

    private fun registerCommandReceiver() {
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getStringExtra("command")) {
                    "BEGIN_SESSION" -> {
                        val funds = intent.getIntExtra("funds", 0).coerceIn(0, 0xFFFF)
                        slave.beginSession(funds)
                        broadcast("DIAG", "BEGIN_SESSION funds=$funds")
                    }
                    "CANCEL_SESSION" -> slave.cancelSession()
                    "TRADE_RESULT" -> {
                        val result = TransResult.values()[intent.getIntExtra("result", 0)]
                        val amount = intent.getIntExtra("amount", 0)
                        when (result) {
                            TransResult.TRADE_SUCCESS -> slave.approveVend(amount)
                            else -> slave.denyVend()
                        }
                    }
                    "RELOAD_CONFIG" -> {
                        slave.config = settings.load()
                        broadcast("DIAG", "config reloaded")
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_VM_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mdb?.disconnect() } catch (_: Exception) {}
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        job.cancel()
    }

    private fun broadcast(event: String, extra: String = "") {
        // Mirror every non-SNIFF event into the file log — sniff events
        // already go to file from the onSniff callback to keep the format.
        if (event != "SNIFF") fileLog("[$event] $extra")
        sendBroadcast(Intent(ACTION_VM_EVENT).apply {
            setPackage(packageName)
            putExtra("event", event)
            putExtra("extra", extra)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, "MDB", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("MDB Lab")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

    companion object {
        private const val TAG = "MdbService"
        private const val MDB_DEV_PATH = "/dev/ttyACM0"
        private const val CH_ID = "mdblab_mdb"
        private const val NOTIFICATION_ID = 1
        const val ACTION_VM_COMMAND = "xyz.mdblab.z80.VM_COMMAND"
        const val ACTION_VM_EVENT = "xyz.mdblab.z80.VM_EVENT"
    }
}
