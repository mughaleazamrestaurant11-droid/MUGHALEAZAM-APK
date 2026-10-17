package com.mughalezam.pos.mobile.api

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Round 25 - the ONLY way this phone talks to the main server PC.
 *
 * Two connections, on purpose:
 *
 *  * actions go over plain HTTP (POST /api/action).  Each one is a single
 *    request with a single answer, so a save can never be half-acknowledged.
 *  * a WebSocket stays open for the live state, exactly like the PC tills use.
 *    When a bill is added or a table is taken on the main PC, this phone is
 *    told at once and redraws - no refresh button, no stale table list.
 *
 * The phone holds no orders of its own.  Every screen is a picture of the
 * server's data and every save is a call to that server, so the main PC
 * stays the one and only place an order exists.
 */
class PosApi(initial: String) {

    /** Anything the cashier must read.  Always the server's own words. */
    class PosException(message: String) : Exception(message)

    interface Listener {
        /** The main server's data arrived or changed. Main thread. */
        fun onState(state: JSONObject, rev: Long)

        /** The link to the main server went up or down. Main thread. */
        fun onConnection(connected: Boolean)
    }

    @Volatile
    var user: String = ""

    @Volatile
    var listener: Listener? = null

    private var baseUrl: String = normalize(initial)
    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var socketUp = false

    @Volatile
    private var stopped = false

    private var attempts = 0

    /* ------------------------------------------------------------- address */

    fun server(): String = baseUrl

    fun setServer(url: String) {
        val next = normalize(url)
        if (next == baseUrl) return
        baseUrl = next
        // a different main server - the old link is meaningless now
        val old = socket
        socket = null
        socketUp = false
        if (old != null) {
            try {
                old.close(1000, "server changed")
            } catch (e: Exception) {
                // already gone
            }
        }
    }

    fun isConnected(): Boolean = socketUp

    /* ---------------------------------------------------------- live state */

    fun connectSocket() {
        stopped = false
        if (baseUrl.isEmpty()) return
        // already live - never open a second socket on top of the first
        if (socketUp && socket != null) return
        // a half-dead one must go before its replacement is made, or the app
        // would quietly hold a new socket every time somebody taps "try now"
        val old = socket
        socket = null
        if (old != null) {
            try {
                old.close(1000, "retry")
            } catch (e: Exception) {
                // it was already gone
            }
        }
        val wsUrl = baseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + "/ws"
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: Exception) {
            return
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketUp = true
                attempts = 0
                notifyConnection(true)
                // say who we are: the server then answers with the state
                webSocket.send(
                    JSONObject().put("type", "hello").put("user", user).toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (msg.optString("type")) {
                    "state" -> {
                        val state = msg.optJSONObject("state") ?: return
                        val rev = msg.optLong("rev")
                        main.post { listener?.onState(state, rev) }
                    }
                    // "welcome" carries the client count - not needed here
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketUp = false
                notifyConnection(false)
                retryLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketUp = false
                notifyConnection(false)
                retryLater()
            }
        })
    }

    private fun retryLater() {
        if (stopped) return
        attempts += 1
        val wait = (400L * attempts).coerceAtMost(8000L)
        main.postDelayed({ if (!stopped && !socketUp) connectSocket() }, wait)
    }

    private fun notifyConnection(up: Boolean) {
        main.post { listener?.onConnection(up) }
    }

    fun stop() {
        stopped = true
        val current = socket
        socket = null
        socketUp = false
        try {
            current?.close(1000, "bye")
        } catch (e: Exception) {
            // the link is gone anyway
        }
    }

    /* ------------------------------------------------------------- actions */

    /**
     * One call to the main server.  Throws [PosException] carrying the
     * server's own message, which is always safe to show the waiter -
     * "Wrong password.", "Select a table for a dine-in order." and so on.
     */
    fun action(name: String, payload: JSONObject = JSONObject()): JSONObject {
        if (baseUrl.isEmpty()) throw PosException("No main server is set on this phone.")
        val envelope = JSONObject()
            .put("action", name)
            .put("payload", payload)
            .put("user", user)
            .put("id", System.currentTimeMillis())
        val request = Request.Builder()
            .url("$baseUrl/api/action")
            .post(envelope.toString().toRequestBody(jsonType))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw PosException("The main server sent no answer.")
            val obj = JSONObject(body)
            if (!obj.optBoolean("ok", false)) {
                val err = obj.optString("error")
                throw PosException(
                    if (err.isEmpty()) "The main server refused the request." else err
                )
            }
            return obj.optJSONObject("result") ?: JSONObject()
        }
    }

    /** The whole picture of the restaurant, straight from the server. */
    fun fetchState(): JSONObject {
        if (baseUrl.isEmpty()) throw PosException("No main server is set on this phone.")
        val request = Request.Builder().url("$baseUrl/api/state").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw PosException("The main server sent no answer.")
            if (!response.isSuccessful) {
                throw PosException("The main server answered ${response.code}.")
            }
            return JSONObject(body).optJSONObject("state")
                ?: throw PosException("The main server sent no data.")
        }
    }

    /** Is this really our POS, and not some other box on port 8484? */
    fun fetchInfo(): JSONObject {
        if (baseUrl.isEmpty()) throw PosException("No main server is set on this phone.")
        val request = Request.Builder().url("$baseUrl/api/info").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return JSONObject()
            if (!response.isSuccessful) return JSONObject()
            return JSONObject(body)
        }
    }

    fun isReachable(): Boolean = try {
        fetchInfo().optString("appName") == APP_NAME
    } catch (e: Exception) {
        false
    }

    /* ------------------------------------------------------ coroutine glue */

    suspend fun actionOn(name: String, payload: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) { action(name, payload) }

    suspend fun stateOn(): JSONObject = withContext(Dispatchers.IO) { fetchState() }

    suspend fun pingOn(): Boolean = withContext(Dispatchers.IO) { isReachable() }

    /* ----------------------------------------------------------- discovery */

    /**
     * Walk this phone's own subnet looking for the main server, the same way
     * the desktop program finds it.  Returns ready-to-use addresses such as
     * "http://192.168.10.50:8484".
     *
     * Only machines that answer "/api/info" with our own app name are
     * offered, so a printer or a camera on the router is never mistaken for
     * the till.
     */
    suspend fun discover(port: Int = DEFAULT_PORT, timeoutMs: Long = 2500L): List<String> =
        withContext(Dispatchers.IO) {
            val prefixes = subnetPrefixes()
            if (prefixes.isEmpty()) return@withContext emptyList()

            val probe = OkHttpClient.Builder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val found = ConcurrentLinkedQueue<String>()

            // Dispatchers.IO runs 64 of these at a time, which is the whole
            // LAN in about two seconds and polite to the router
            val jobs = prefixes.flatMap { prefix ->
                (1..254).map { host ->
                    async {
                        val url = "http://$prefix$host:$port"
                        if (isPosServer(probe, url)) found.add(url)
                    }
                }
            }
            jobs.awaitAll()
            found.toList()
        }

    private fun isPosServer(client: OkHttpClient, url: String): Boolean = try {
        val request = Request.Builder().url("$url/api/info").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body?.string() ?: return false
            JSONObject(body).optString("appName") == APP_NAME
        }
    } catch (e: Exception) {
        false
    }

    /** "192.168.10." - the network this phone is actually sitting on. */
    private fun subnetPrefixes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val networks = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (networks.hasMoreElements()) {
                val network = networks.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address !is Inet4Address) continue
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) continue
                    val parts = address.hostAddress?.split(".") ?: continue
                    if (parts.size != 4) continue
                    out.add("${parts[0]}.${parts[1]}.${parts[2]}.")
                }
            }
        } catch (e: Exception) {
            // some phones hide the interfaces - the waiter can type the address
        }
        return out.toList()
    }

    companion object {
        const val APP_NAME = "MUGHAL-E-AZAM POS"
        const val DEFAULT_PORT = 8484

        /** "192.168.10.50:8484" and "http://192.168.10.50:8484/" mean the same. */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
                u = "http://$u"
            }
            return u
        }

        /** "192.168.10.50:8484" from a bare "192.168.10.50". */
        fun withPort(url: String, port: Int): String {
            val u = normalize(url)
            if (u.isEmpty()) return u
            val host = u.removePrefix("http://").removePrefix("https://")
            return if (host.contains(":")) u else "$u:$port"
        }
    }
}
