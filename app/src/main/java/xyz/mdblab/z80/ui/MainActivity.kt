package xyz.mdblab.z80.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import xyz.mdblab.z80.R
import xyz.mdblab.z80.mdb.TransResult
import xyz.mdblab.z80.service.MdbService

class MainActivity : AppCompatActivity() {

    private lateinit var stateLabel: TextView
    private lateinit var snifferView: TextView
    private val log = StringBuilder()

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = intent.getStringExtra("event") ?: return
            val extra = intent.getStringExtra("extra").orEmpty()
            when (event) {
                "STATUS" -> stateLabel.text = "State: $extra"
                "SNIFF"  -> append(extra)                              // ↓/↑ + hex + label
                else     -> append("[$event] $extra")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stateLabel = findViewById(R.id.state_label)
        snifferView = findViewById(R.id.sniffer_view)
        snifferView.movementMethod = ScrollingMovementMethod()

        val fundsInput = findViewById<EditText>(R.id.funds_input)

        findViewById<Button>(R.id.btn_begin).setOnClickListener {
            val funds = fundsInput.text.toString().toIntOrNull() ?: 0
            send("BEGIN_SESSION") { putExtra("funds", funds) }
        }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            send("CANCEL_SESSION")
        }
        findViewById<Button>(R.id.btn_approve).setOnClickListener {
            val amount = fundsInput.text.toString().toIntOrNull() ?: 0
            send("TRADE_RESULT") {
                putExtra("result", TransResult.TRADE_SUCCESS.ordinal)
                putExtra("amount", amount)
            }
        }
        findViewById<Button>(R.id.btn_deny).setOnClickListener {
            send("TRADE_RESULT") { putExtra("result", TransResult.TRADE_FAILURE.ordinal) }
        }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            log.clear(); snifferView.text = ""
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val svc = Intent(this, MdbService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MdbService.ACTION_VM_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(eventReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(eventReceiver) } catch (_: Exception) {}
    }

    private inline fun send(command: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(MdbService.ACTION_VM_COMMAND).apply {
            setPackage(packageName)
            putExtra("command", command)
            configure()
        }
        sendBroadcast(intent)
    }

    private fun append(line: String) {
        log.appendLine(line)
        if (log.length > 8000) log.delete(0, log.length - 8000)
        snifferView.text = log
        // Auto-scroll
        val layout = snifferView.layout
        if (layout != null) {
            val scroll = layout.getLineTop(snifferView.lineCount) - snifferView.height
            if (scroll > 0) snifferView.scrollTo(0, scroll)
        }
    }
}
