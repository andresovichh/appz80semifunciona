package xyz.mdblab.z80

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.morefun.yapi.engine.DeviceServiceEngine

class App : Application() {

    @Volatile
    var engine: DeviceServiceEngine? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engine = DeviceServiceEngine.Stub.asInterface(service)
            Log.i(TAG, "YSDK connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            Log.w(TAG, "YSDK disconnected")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val intent = Intent().apply {
            action = "com.morefun.ysdk.service"
            setPackage("com.morefun.ysdk")
        }
        val ok = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.i(TAG, "bindService(YSDK) -> $ok")
    }

    companion object {
        private const val TAG = "MdbLabApp"
        lateinit var instance: App
            private set
    }
}
