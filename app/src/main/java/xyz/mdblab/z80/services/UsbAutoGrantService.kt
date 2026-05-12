package xyz.mdblab.z80.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility-driven monitor that keeps two system toggles ON without root:
 *   1. Wireless Debugging (Settings.Global.adb_wifi_enabled) — also captures the random
 *      port Android 13 assigns and persists it so the heartbeat can report it.
 *   2. Ethernet Tethering — toggles the switch in TetherSettings if eth0 is up but lacks
 *      an IPv4 address (used to give downstream Pi/POS internet via wlan0).
 *
 * Ported from Z80_Tablet_Replacement/services/UsbAutoGrantService.kt — same logic, only the
 * package name changed. Locale-bound to es-AR strings ("Depuración inalámbrica",
 * "Conexión Ethernet", "Permitir") — matches the Z80's default Spanish UI.
 *
 * REQUIRES manual one-time activation: Settings → Accessibility → installed services →
 * "URU Auto Grant" → toggle ON.
 */
class UsbAutoGrantService : AccessibilityService() {

    companion object {
        private const val TAG = "UsbAutoGrant"
        @Volatile var instance: UsbAutoGrantService? = null
    }

    @Volatile private var waitingForTetherSettings = false
    @Volatile private var waitingForWirelessDebugging = false
    @Volatile private var handlerBusy = false
    private var monitorThread: Thread? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.packageNames = null
        serviceInfo = info
        Log.i(TAG, "Accessibility Service connected")
        startSettingsMonitor()
    }

    private var wirelessRetryCount = 0
    private var ethernetRetryCount = 0
    private val MAX_RETRIES = 3

    private fun startSettingsMonitor() {
        monitorThread = Thread {
            try {
                Thread.sleep(15000) // Initial wait for boot

                while (!Thread.currentThread().isInterrupted) {
                    try {
                        // Wireless debugging
                        if (!waitingForWirelessDebugging && !waitingForTetherSettings) {
                            if (needsWirelessDebuggingFix() && wirelessRetryCount < MAX_RETRIES) {
                                Log.i(TAG, "Wireless debugging OFF — opening Developer Options (attempt ${wirelessRetryCount + 1}/$MAX_RETRIES)")
                                waitingForWirelessDebugging = true
                                wirelessRetryCount++

                                val intent = Intent()
                                intent.setClassName("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity")
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(intent)

                                Thread.sleep(25000)
                                waitingForWirelessDebugging = false
                                returnToApp()
                                Thread.sleep(3000)

                                if (!needsWirelessDebuggingFix()) {
                                    wirelessRetryCount = 0
                                    Log.i(TAG, "Wireless debugging is now ON")
                                }
                            } else if (!needsWirelessDebuggingFix()) {
                                wirelessRetryCount = 0
                            }
                        }

                        // Ethernet tethering
                        if (!waitingForTetherSettings && !waitingForWirelessDebugging) {
                            if (needsEthernetFix() && ethernetRetryCount < MAX_RETRIES) {
                                Log.i(TAG, "eth0 needs tethering — opening TetherSettings (attempt ${ethernetRetryCount + 1}/$MAX_RETRIES)")
                                waitingForTetherSettings = true
                                ethernetRetryCount++

                                val intent = Intent()
                                intent.setClassName("com.android.settings", "com.android.settings.TetherSettings")
                                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                startActivity(intent)

                                Thread.sleep(20000)
                                waitingForTetherSettings = false
                                returnToApp()
                                Thread.sleep(3000)

                                if (!needsEthernetFix()) {
                                    ethernetRetryCount = 0
                                    Log.i(TAG, "Ethernet tethering is now ON")
                                }
                            } else if (!needsEthernetFix()) {
                                ethernetRetryCount = 0
                            }
                        }
                    } catch (e: InterruptedException) {
                        return@Thread
                    } catch (e: Exception) {
                        Log.w(TAG, "Monitor error: ${e.message}")
                        waitingForTetherSettings = false
                        waitingForWirelessDebugging = false
                        returnToApp()
                    }

                    // Idle re-check cadence (when both toggles are already healthy).
                    Thread.sleep(30000)
                }
            } catch (_: InterruptedException) { }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun needsWirelessDebuggingFix(): Boolean {
        return try {
            val value = android.provider.Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0)
            value != 1
        } catch (_: Exception) { true }
    }

    private fun needsEthernetFix(): Boolean {
        return try {
            val eth0 = java.net.NetworkInterface.getByName("eth0") ?: return false
            if (!eth0.isUp) return false
            !eth0.inetAddresses.asSequence().any {
                it is java.net.Inet4Address && !it.isLoopbackAddress
            }
        } catch (_: Exception) { false }
    }

    private fun tap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "Tap dispatched at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
    }

    /**
     * Real swipe-up gesture, works on any visible view regardless of whether
     * the underlying RecyclerView exposes ACTION_SCROLL_FORWARD properly.
     * Used to walk down a long Settings list when the accessibility scroll
     * action is a no-op (observed on the Z80's stock ROM).
     */
    private fun swipeUp() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val path = Path()
        path.moveTo(w / 2f, h * 0.80f)
        path.lineTo(w / 2f, h * 0.20f)
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun dumpVisibleTexts(root: AccessibilityNodeInfo, max: Int = 25): String {
        val out = mutableListOf<String>()
        collectAllText(root, out)
        return out.take(max).joinToString(" | ")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val root = rootInActiveWindow ?: return

        when (pkg) {
            "com.android.settings" -> {
                if (handlerBusy) return
                if (waitingForWirelessDebugging) {
                    handlerBusy = true
                    Thread {
                        try { handleWirelessDebugging(root) } finally { handlerBusy = false }
                    }.start()
                } else if (waitingForTetherSettings) {
                    handlerBusy = true
                    Thread {
                        try { handleSettingsTethering(root) } finally { handlerBusy = false }
                    }.start()
                }
            }
            "com.android.systemui", "android" -> {
                if (!waitingForWirelessDebugging && !waitingForTetherSettings) {
                    handleUsbPermissionDialog(root)
                }
                root.recycle()
            }
        }
    }

    // === WIRELESS DEBUGGING ===

    // Locale-tolerant labels: the Z80 ships in en-US but operators may flip it to es.
    private val WIRELESS_DEBUGGING_LABELS = listOf("Wireless debugging", "Depuración inalámbrica")
    private val WIRELESS_DEBUGGING_LABELS_LOWER = listOf("wireless debugging", "depuración inalámbrica")

    private fun handleWirelessDebugging(root: AccessibilityNodeInfo) {
        Thread.sleep(3000)

        for (scrollAttempt in 1..15) {
            val currentRoot = rootInActiveWindow ?: continue
            val nodes = WIRELESS_DEBUGGING_LABELS.flatMap { findNodesByText(currentRoot, it) }
            val menuItem = nodes.firstOrNull { node ->
                val resId = node.viewIdResourceName ?: ""
                resId.contains("title") || node.parent?.className?.toString()?.contains("RelativeLayout") == true
            } ?: nodes.firstOrNull()

            if (menuItem != null) {
                var clickTarget: AccessibilityNodeInfo = menuItem
                var parent = menuItem.parent
                while (parent != null) {
                    if (parent.isClickable) { clickTarget = parent; break }
                    parent = parent.parent
                }
                clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Clicked Wireless Debugging row (text='${menuItem.text}')")
                Thread.sleep(3000)
                handleWirelessStep1Subscreen()
                return
            }

            Log.d(TAG, "Scroll $scrollAttempt/15 — visible: ${dumpVisibleTexts(currentRoot)}")
            val accessibilityScrollWorked = findScrollableNode(currentRoot)
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            if (!accessibilityScrollWorked) {
                swipeUp()
            }
            Thread.sleep(1500)
        }

        Log.w(TAG, "Could not find Wireless Debugging entry after 15 scrolls")
        waitingForWirelessDebugging = false
        returnToApp()
    }

    private fun handleWirelessStep1Subscreen() {
        Thread.sleep(3000)

        if (!needsWirelessDebuggingFix()) {
            Log.i(TAG, "WD already ON after row click — skipping switch tap, reading port only")
            Thread.sleep(2000)
            readAndSaveAdbPort()
            waitingForWirelessDebugging = false
            returnToApp()
            return
        }

        for (attempt in 1..5) {
            val currentRoot = rootInActiveWindow ?: continue
            val nodes = WIRELESS_DEBUGGING_LABELS_LOWER.flatMap { findNodesByText(currentRoot, it) }
            for (node in nodes) {
                var ancestor = node.parent
                for (i in 0..3) {
                    if (ancestor == null) break
                    val sw = findSwitch(ancestor)
                    if (sw != null) {
                        if (needsWirelessDebuggingFix() && !sw.isChecked) {
                            val bounds = android.graphics.Rect()
                            sw.getBoundsInScreen(bounds)
                            Log.i(TAG, "Tapping wireless debugging switch at (${bounds.centerX()}, ${bounds.centerY()})")
                            tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                            Thread.sleep(3000)
                            handlePermitirDialog()
                            Thread.sleep(2000)
                        } else {
                            Log.i(TAG, "Wireless debugging already ON (system setting) — not tapping switch")
                        }
                        readAndSaveAdbPort()
                        waitingForWirelessDebugging = false
                        returnToApp()
                        return
                    }
                    ancestor = ancestor.parent
                }
            }
            Log.d(TAG, "Switch not found, attempt $attempt/5")
            Thread.sleep(2000)
        }

        Log.w(TAG, "Could not find wireless debugging switch")
        waitingForWirelessDebugging = false
        returnToApp()
    }

    private fun handlePermitirDialog() {
        val dialogRoot = rootInActiveWindow ?: return
        val allowTexts = listOf("Permitir", "Allow", "Aceptar", "OK")
        for (text in allowTexts) {
            for (btn in findNodesByText(dialogRoot, text)) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked '$text' on dialog")
                    return
                }
            }
        }
    }

    private fun readAndSaveAdbPort() {
        try {
            for (attempt in 1..5) {
                Thread.sleep(2000)
                val currentRoot = rootInActiveWindow ?: continue
                val allText = mutableListOf<String>()
                collectAllText(currentRoot, allText)
                for (text in allText) {
                    val match = Regex("""(\d+\.\d+\.\d+\.\d+):(\d+)""").find(text)
                    if (match != null) {
                        val port = match.groupValues[2].toIntOrNull()
                        if (port != null && port > 1024) {
                            savePort(port)
                            return
                        }
                    }
                }
                Log.i(TAG, "Port not visible yet, attempt $attempt/5")
            }
            Log.w(TAG, "Could not find ADB port after 5 attempts")
        } catch (e: Exception) {
            Log.w(TAG, "readAndSaveAdbPort error: ${e.message}")
        }
    }

    private fun savePort(port: Int) {
        val prefs = getSharedPreferences("adb_config", 0)
        prefs.edit().putInt("adb_port", port).apply()
        Log.i(TAG, "Saved ADB wireless port: $port")
    }

    private fun collectAllText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) texts.add(text)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllText(it, texts) }
        }
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // === ETHERNET TETHERING ===

    private fun handleSettingsTethering(root: AccessibilityNodeInfo) {
        for (attempt in 1..5) {
            Thread.sleep(2000)

            val currentRoot = rootInActiveWindow ?: continue
            val switchNode = findEthernetSwitch(currentRoot)

            if (switchNode != null && switchNode.isEnabled && !switchNode.isChecked) {
                val bounds = android.graphics.Rect()
                switchNode.getBoundsInScreen(bounds)
                val x = bounds.centerX().toFloat()
                val y = bounds.centerY().toFloat()
                Log.i(TAG, "Tapping Ethernet switch at ($x, $y) (attempt $attempt)")
                tap(x, y)
                Thread.sleep(5000)
                waitingForTetherSettings = false
                returnToApp()
                return
            } else if (switchNode != null && switchNode.isChecked) {
                Log.i(TAG, "Ethernet tethering already ON")
                waitingForTetherSettings = false
                returnToApp()
                return
            }

            Log.d(TAG, "Ethernet switch not ready, attempt $attempt/5")
        }

        Log.w(TAG, "Ethernet switch not found after 5 attempts")
        waitingForTetherSettings = false
        returnToApp()
    }

    private fun findEthernetSwitch(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val tetherTexts = listOf("Conexión Ethernet", "Ethernet tethering", "Ethernet")
        for (text in tetherTexts) {
            val textNodes = findNodesByText(root, text)
            for (node in textNodes) {
                var ancestor = node.parent
                for (i in 0..2) {
                    if (ancestor == null) break
                    val sw = findSwitch(ancestor)
                    if (sw != null) return sw
                    ancestor = ancestor.parent
                }
            }
        }
        return null
    }

    private fun findSwitch(parent: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(parent)
        while (queue.isNotEmpty()) {
            val curr = queue.poll() ?: continue
            if (curr.className?.toString() == "android.widget.Switch") return curr
            for (i in 0 until curr.childCount) {
                curr.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // === USB PERMISSION DIALOG ===

    private fun handleUsbPermissionDialog(root: AccessibilityNodeInfo) {
        val isUsbDialog = findNodesByText(root, "USB").isNotEmpty()
                || findNodesByText(root, "USB2.0").isNotEmpty()
                || findNodesByText(root, "access").isNotEmpty()
        if (!isUsbDialog) return

        Log.i(TAG, "USB permission dialog detected")

        val checkboxTexts = listOf("Always open", "Siempre abrir", "Use by default", "Usar de forma predeterminada")
        for (text in checkboxTexts) {
            for (cb in findNodesByText(root, text)) {
                if (cb.isCheckable && !cb.isChecked) {
                    cb.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Checked: $text")
                    Thread.sleep(300)
                }
            }
        }

        val okTexts = listOf("OK", "Aceptar", "Allow", "Permitir")
        for (text in okTexts) {
            for (btn in findNodesByText(root, text)) {
                if (btn.isClickable) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked: $text")
                    return
                }
            }
        }
    }

    // === HELPERS ===

    private fun findNodesByText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        return root.findAccessibilityNodeInfosByText(text) ?: emptyList()
    }

    private fun returnToApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(launchIntent)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        monitorThread?.interrupt()
    }
}
