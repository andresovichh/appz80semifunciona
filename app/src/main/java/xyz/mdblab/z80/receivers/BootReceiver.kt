package xyz.mdblab.z80.receivers

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import xyz.mdblab.z80.AdminReceiver
import xyz.mdblab.z80.utils.RootHelper
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Boot Completed. Ethernet sharing handled by Magisk boot script.")
        val logFile = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "root_log.txt")
        try { logFile.appendText("${java.util.Date()}: BootReceiver triggered\n") } catch (_: Exception) { }

        // Whitelist Settings + Automate in lock task
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(context, AdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setLockTaskPackages(componentName, arrayOf(context.packageName, "com.android.settings", "com.llamalab.automate", "com.android.systemui"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to whitelist packages: ${e.message}")
        }

        // Enforce ADB and start Native Ethernet Tethering
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                RootHelper.enforceAdb()
                try { logFile.appendText("${java.util.Date()}: ADB enforced.\n") } catch (_: Exception) { }

                // Wait for WiFi to connect before enabling tethering
                var waited = 0
                while (waited < 120) {
                    val hasWifi = java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                        ?.any { it.name == "wlan0" && it.inetAddresses.hasMoreElements() } == true
                    if (hasWifi) break
                    Thread.sleep(5000)
                    waited += 5
                }
                try { logFile.appendText("${java.util.Date()}: WiFi ready after ${waited}s, enabling tethering...\n") } catch (_: Exception) { }

                // Use EthernetTetherHelper (reflection API) instead of cmd
                val success = xyz.mdblab.z80.utils.EthernetTetherHelper.enableEthernetTethering(context)
                try { logFile.appendText("${java.util.Date()}: EthernetTetherHelper result: $success\n") } catch (_: Exception) { }
            } catch (e: Exception) {
                try { logFile.appendText("${java.util.Date()}: EXCEPTION ${e.message}\n") } catch (_: Exception) { }
            }
        }
    }
}
