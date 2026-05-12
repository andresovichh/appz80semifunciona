package xyz.mdblab.z80.utils

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enables native Android Ethernet Tethering via reflection.
 * Android 12 API: TetheringManager.startTethering(TetheringRequest, Executor, Callback)
 * Requires TETHER_PRIVILEGED (granted via Magisk privapp-permissions module).
 */
object EthernetTetherHelper {

    private const val TAG = "EthernetTetherHelper"
    private const val TETHERING_ETHERNET = 5

    fun enableEthernetTethering(context: Context): Boolean {
        // Try Android 12+ TetheringManager API first (correct API)
        val result = tryTetheringManagerWithRequest(context)
        if (result) return true

        // Fallback: try legacy ConnectivityManager API (Android 8-10)
        return tryLegacyConnectivityManager(context)
    }

    /**
     * Android 12+ API: TetheringManager.startTethering(TetheringRequest, Executor, Callback)
     * TetheringRequest is built via TetheringRequest.Builder(type).build()
     */
    private fun tryTetheringManagerWithRequest(context: Context): Boolean {
        return try {
            val tmClass = Class.forName("android.net.TetheringManager")
            val tm = context.getSystemService(tmClass)
                ?: throw Exception("TetheringManager service not available")

            // Build TetheringRequest
            val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .newInstance(TETHERING_ETHERNET)
            val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val request = builderClass.getDeclaredMethod("build").invoke(builder)

            // Create callback that captures the result
            val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            val success = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val callback = try {
                // StartTetheringCallback is an abstract class with:
                //   onTetheringStarted() and onTetheringFailed(int error)
                val constructor = callbackClass.getDeclaredConstructor()
                constructor.isAccessible = true

                // We need a subclass, use dynamic bytecode generation or just create a no-op
                // For now, create instance and rely on timeout to check state
                constructor.newInstance()
            } catch (e: Exception) {
                Log.w(TAG, "Could not instantiate callback directly, trying Proxy", e)
                Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    if (callbackClass.isInterface) arrayOf(callbackClass) else callbackClass.interfaces
                ) { _, method, args ->
                    when (method.name) {
                        "onTetheringStarted" -> {
                            Log.i(TAG, "onTetheringStarted callback!")
                            success.set(true)
                            latch.countDown()
                        }
                        "onTetheringFailed" -> {
                            val error = args?.firstOrNull() ?: "unknown"
                            Log.e(TAG, "onTetheringFailed callback! error=$error")
                            success.set(false)
                            latch.countDown()
                        }
                    }
                    null
                }
            }

            val executor = java.util.concurrent.Executor { it.run() }

            // Call startTethering(TetheringRequest, Executor, StartTetheringCallback)
            val startMethod = tmClass.getDeclaredMethod(
                "startTethering",
                requestClass,
                java.util.concurrent.Executor::class.java,
                callbackClass
            )
            startMethod.invoke(tm, request, executor, callback)
            Log.i(TAG, "TetheringManager.startTethering(TetheringRequest) invoked")

            // Wait up to 10s for callback
            val gotCallback = latch.await(10, TimeUnit.SECONDS)
            if (gotCallback) {
                Log.i(TAG, "Tethering callback received: success=${success.get()}")
                success.get()
            } else {
                // Didn't get callback - check state directly
                Log.w(TAG, "No callback after 10s, checking eth0 state...")
                checkEthernetTetherActive()
            }
        } catch (e: Exception) {
            Log.e(TAG, "TetheringManager with TetheringRequest failed", e)
            false
        }
    }

    /**
     * Legacy API: ConnectivityManager.startTethering(int, boolean, callback, handler)
     * Exists on Android 8-10, may still work as deprecated shim on some ROMs.
     */
    private fun tryLegacyConnectivityManager(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

            val callbackClass = Class.forName(
                "android.net.ConnectivityManager\$OnStartTetheringCallback"
            )
            val startTethering = android.net.ConnectivityManager::class.java.getDeclaredMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                callbackClass,
                android.os.Handler::class.java
            )

            val callback = try {
                val constructor = callbackClass.getDeclaredConstructor()
                constructor.isAccessible = true
                constructor.newInstance()
            } catch (e: Exception) {
                Proxy.newProxyInstance(
                    callbackClass.classLoader,
                    arrayOf(callbackClass)
                ) { _, _, _ -> null }
            }

            startTethering.invoke(
                cm, TETHERING_ETHERNET, false, callback,
                android.os.Handler(android.os.Looper.getMainLooper())
            )
            Log.i(TAG, "Legacy ConnectivityManager.startTethering invoked")

            // Wait and check if it actually worked
            Thread.sleep(5000)
            checkEthernetTetherActive()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy ConnectivityManager.startTethering failed", e)
            false
        }
    }

    /**
     * Check if ethernet tethering is actually active by reading dumpsys.
     */
    private fun checkEthernetTetherActive(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys ethernet | grep 'Default interface mode'"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            val active = output.contains("mode: 2")
            Log.i(TAG, "Ethernet tether check: mode=${if (active) "SERVER(2)" else "CLIENT(1)"}")
            active
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check ethernet state", e)
            false
        }
    }
}
