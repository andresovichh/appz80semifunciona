package xyz.mdblab.z80.services

import android.util.Log
import com.google.gson.Gson
import xyz.mdblab.z80.data.repository.VendingRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class SyncServer(
    private val repository: VendingRepository,
    private val scope: CoroutineScope
) : NanoHTTPD(3001) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.POST && session.uri == "/api/v1/sync") {
            // Security Check: Validate API Key to prevent unauthorized pushes
            // NanoHTTPD headers are lowercase
            val receivedKey = session.headers["x-api-key"]
            // MVP Hardcoded Key (must match Backend DB for this machine)
            val expectedKey = "d87ac804-f3d1-4a1d-a36a-3fd9b34f9692"
            
            if (receivedKey != expectedKey) {
                Log.w("SyncServer", "Unauthorized sync attempt. Key: $receivedKey")
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized")
            }

            try {
                // NanoHTTPD requires parseBody to read POST data properly
                val files = HashMap<String, String>()
                session.parseBody(files)
                val payloadJson = files["postData"] ?: ""

                Log.d("SyncServer", "Received Sync Payload (${payloadJson.length} chars)")

                // We process this asynchronously to reply fast
                scope.launch(Dispatchers.IO) {
                    repository.processSyncPayload(payloadJson)
                }

                return newFixedLengthResponse(Response.Status.OK, "text/plain", "{\"status\":\"ok\"}")
            } catch (e: Exception) {
                Log.e("SyncServer", "Error processing sync", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "{\"error\":\"${e.message}\"}")
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }
}
