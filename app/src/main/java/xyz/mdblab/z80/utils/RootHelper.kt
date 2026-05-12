package xyz.mdblab.z80.utils

import android.util.Log
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility to execute Root commands for Network Management.
 * Uses manual ethernet tethering (Android native doesn't support eth0 on this ROM).
 * All commands are IDEMPOTENT - never bring eth0 down to avoid POS losing connectivity.
 */
object RootHelper {

    private const val TAG = "RootHelper"

    /**
     * Sets up ethernet tethering manually (IP, NAT, DHCP) + enforces ADB.
     * IDEMPOTENT: only adds what's missing, never disrupts existing config.
     */
    suspend fun setupFullNetwork(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting idempotent network setup (Root)...")

        val commands = listOf(
            // 1. IP Forwarding
            "echo 1 > /proc/sys/net/ipv4/ip_forward",
            // 2. Configure eth0 - add IP without link flap (idempotent)
            "ip addr show eth0 | grep -q 192.168.44.1 || ip addr add 192.168.44.1/24 dev eth0",
            "ip link set eth0 up",
            // 3. IPTables NAT/Forwarding - only add if missing (idempotent)
            "iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o wlan0 -j MASQUERADE",
            "iptables -C FORWARD -i wlan0 -o eth0 -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wlan0 -o eth0 -m state --state RELATED,ESTABLISHED -j ACCEPT",
            "iptables -C FORWARD -i eth0 -o wlan0 -j ACCEPT 2>/dev/null || iptables -A FORWARD -i eth0 -o wlan0 -j ACCEPT",
            // 4. DNSMasq - only start if not running (idempotent)
            "pgrep dnsmasq > /dev/null || dnsmasq --no-daemon --no-resolv --no-poll --dhcp-range=192.168.44.2,192.168.44.10,1h --dhcp-option=3,192.168.44.1 --dhcp-option=6,8.8.8.8,8.8.4.4 &",
            // 5. Enforce ADB 5555
            "setprop service.adb.tcp.port 5555",
            "setprop persist.adb.tcp.port 5555"
        )

        return@withContext executeCommands(commands)
    }

    /**
     * Enforce ADB on port 5555 (separate from network setup).
     */
    suspend fun enforceAdb(): Boolean = withContext(Dispatchers.IO) {
        return@withContext executeCommands(listOf(
            "setprop service.adb.tcp.port 5555",
            "setprop persist.adb.tcp.port 5555"
        ))
    }

    /**
     * Restarts ADBD. Warning: Will kill current connection!
     */
    suspend fun restartAdbd(): Boolean = withContext(Dispatchers.IO) {
        return@withContext executeCommands(listOf(
            "setprop service.adb.tcp.port 5555",
            "setprop persist.adb.tcp.port 5555",
            "stop adbd",
            "start adbd"
        ))
    }

    /**
     * Starts a background watchdog that checks network state every 15 seconds
     * and re-applies configuration if the OS wipes it.
     * IDEMPOTENT: only adds what's missing, never disrupts existing config.
     */
    suspend fun startNetworkWatchdog() = withContext(Dispatchers.IO) {
        val logFile = java.io.File(android.os.Environment.getExternalStorageDirectory(), "root_log.txt")
        logFile.appendText("Starting Network Watchdog...\n")

        while (true) {
            try {
                // Check if eth0 has the correct IP
                val checkProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ip addr show eth0"))
                val reader = java.io.BufferedReader(java.io.InputStreamReader(checkProcess.inputStream))
                val output = reader.readText()
                checkProcess.waitFor()

                if (!output.contains("192.168.44.1")) {
                    Log.w(TAG, "Watchdog: IP missing on eth0! Adding without link flap...")
                    logFile.appendText("${java.util.Date()}: IP missing! Adding idempotently...\n")
                    executeCommands(listOf(
                        "ip addr add 192.168.44.1/24 dev eth0 2>/dev/null",
                        "ip link set eth0 up",
                        "iptables -t nat -C POSTROUTING -o wlan0 -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -o wlan0 -j MASQUERADE",
                        "pgrep dnsmasq > /dev/null || dnsmasq --no-daemon --no-resolv --no-poll --dhcp-range=192.168.44.2,192.168.44.10,1h --dhcp-option=3,192.168.44.1 --dhcp-option=6,8.8.8.8,8.8.4.4 &"
                    ))
                }

                // Check IP Forwarding
                val checkForward = Runtime.getRuntime().exec("cat /proc/sys/net/ipv4/ip_forward")
                val readerForward = java.io.BufferedReader(java.io.InputStreamReader(checkForward.inputStream))
                val outputForward = readerForward.readText().trim()
                checkForward.waitFor()

                if (outputForward != "1") {
                    Log.w(TAG, "Watchdog: IP Forwarding disabled! Re-enabling...")
                    logFile.appendText("${java.util.Date()}: IP Forwarding disabled! Fixing...\n")
                    executeCommands(listOf("echo 1 > /proc/sys/net/ipv4/ip_forward"))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Watchdog error", e)
            }

            kotlinx.coroutines.delay(15000)
        }
    }

    /**
     * Toggles native Ethernet Tethering on Android 11+ via adb shell cmd.
     */
    suspend fun enableNativeEthernetTethering(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Requesting native Ethernet Tethering...")
        return@withContext executeCommands(listOf("cmd connectivity start-tethering ethernet"))
    }

    private fun executeCommands(cmds: List<String>): Boolean {
        var os: DataOutputStream? = null
        val logFile = java.io.File(android.os.Environment.getExternalStorageDirectory(), "root_log.txt")
        try {
            val process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)

            for (cmd in cmds) {
                Log.d(TAG, "CMD: $cmd")
                os.writeBytes(cmd + "\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            val exitValue = process.waitFor()
            if (exitValue == 0) {
                return true
            } else {
                logFile.appendText("Cmd Failed: $exitValue\n")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root execution error", e)
            logFile.appendText("Error: ${e.message}\n")
            return false
        } finally {
            try {
                os?.close()
            } catch (e: Exception) {}
        }
    }
}
