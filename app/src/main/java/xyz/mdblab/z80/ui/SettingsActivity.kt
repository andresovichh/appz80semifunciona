package xyz.mdblab.z80.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import xyz.mdblab.z80.R
import xyz.mdblab.z80.data.Settings
import xyz.mdblab.z80.service.MdbService

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val s = Settings(this)
        val cfg = s.load()

        val mfg = findViewById<EditText>(R.id.mfg)
        val country = findViewById<EditText>(R.id.country)
        val scale = findViewById<EditText>(R.id.scale)
        val decimals = findViewById<EditText>(R.id.decimals)
        val responseTime = findViewById<EditText>(R.id.response_time)
        val featureLevel = findViewById<EditText>(R.id.feature_level)
        val waitEnd = findViewById<EditText>(R.id.wait_end)
        val alwaysIdle = findViewById<CheckBox>(R.id.always_idle)
        val multiVend = findViewById<CheckBox>(R.id.multi_vend)
        val remoteVend = findViewById<CheckBox>(R.id.remote_vend)
        val refunds = findViewById<CheckBox>(R.id.refunds)
        val revalue = findViewById<CheckBox>(R.id.revalue)
        val ackFirst = findViewById<CheckBox>(R.id.ack_first)
        val cashSale = findViewById<CheckBox>(R.id.cash_sale)

        mfg.setText(cfg.manufactureCode)
        country.setText("0x%04X".format(cfg.countryCode))
        scale.setText(cfg.scaleFactor.toString())
        decimals.setText((cfg.decimalPlaces.toInt() and 0xFF).toString())
        responseTime.setText("0x%02X".format(cfg.responseTime.toInt() and 0xFF))
        featureLevel.setText((cfg.readerFeatureLevel.toInt() and 0xFF).toString())
        waitEnd.setText(cfg.waitEndSessionTm.toString())
        alwaysIdle.isChecked = cfg.enableAlwaysIdle
        multiVend.isChecked = cfg.supportMultiVend
        remoteVend.isChecked = cfg.supportRemoteVend
        refunds.isChecked = cfg.requestRefunds
        revalue.isChecked = cfg.revalueApproved
        ackFirst.isChecked = cfg.respondAckFirst
        cashSale.isChecked = cfg.supportCashSale

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            try {
                val updated = cfg.copy(
                    manufactureCode = mfg.text.toString().take(3).padEnd(3),
                    countryCode = parseHexOrInt(country.text.toString()),
                    scaleFactor = scale.text.toString().toInt(),
                    decimalPlaces = decimals.text.toString().toInt().toByte(),
                    responseTime = parseHexOrInt(responseTime.text.toString()).toByte(),
                    readerFeatureLevel = featureLevel.text.toString().toInt().toByte(),
                    waitEndSessionTm = waitEnd.text.toString().toInt(),
                    enableAlwaysIdle = alwaysIdle.isChecked,
                    supportMultiVend = multiVend.isChecked,
                    supportRemoteVend = remoteVend.isChecked,
                    requestRefunds = refunds.isChecked,
                    revalueApproved = revalue.isChecked,
                    respondAckFirst = ackFirst.isChecked,
                    supportCashSale = cashSale.isChecked,
                )
                s.save(updated)
                sendBroadcast(Intent(MdbService.ACTION_VM_COMMAND).apply {
                    setPackage(packageName)
                    putExtra("command", "RELOAD_CONFIG")
                })
                Toast.makeText(this, "Saved + reloaded.", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid input: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun parseHexOrInt(s: String): Int {
        val t = s.trim()
        return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toInt(16) else t.toInt()
    }
}
