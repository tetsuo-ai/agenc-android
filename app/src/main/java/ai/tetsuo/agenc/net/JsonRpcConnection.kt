package ai.tetsuo.agenc.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Typed JSON-RPC 2.0 client over a WebSocket for agenc-core's app-server protocol.
 *
 * Direct port of the iOS app's JSONRPCConnection.swift. Local (loopback) connections speak raw
 * JSON-RPC, one object per frame. Remote connections go through the AgenC relay: each frame is
 * wrapped in the relay's `{"t":"data","payload":…}` multiplexing envelope (see [relayMode]).
 * Correlates responses by id, fans out server notifications via a [SharedFlow], and runs the
 * `initialize` (cookie auth) handshake.
 *
 * Swift used an `actor` + `AsyncStream`; here we use a [Mutex] to guard mutable state and a
 * [MutableSharedFlow] for the notification fan-out, all driven on a single application scope.
 */
class JsonRpcConnection(
    private val url: String,
    private val relayMode: Boolean = false,
    private val client: OkHttpClient = sharedClient,
) {
    data class Notification(val method: String, val params: JsonValue)

    sealed class ConnError(message: String) : Exception(message) {
        data class Timeout(val method: String) : ConnError("timeout: $method")
        data class Rpc(
            val code: Int,
            val rpcMessage: String,
            val dataCode: String?,
        ) : ConnError("rpc $code${dataCode?.let { "/$it" } ?: ""}: $rpcMessage")
        data object Closed : ConnError("connection closed") {
            private fun readResolve(): Any = Closed
        }
    }

    private val _notifications = MutableSharedFlow<Notification>(
        // Core may replay a capability-routed Ledger request while `initialize` is still awaiting
        // its response. DaemonHub starts the pump immediately after that response, so retain a
        // small connection-local window instead of dropping the privileged replay in the gap.
        replay = 32,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Server notifications (and the synthetic `$transport.closed` sentinel on disconnect). */
    val notifications: SharedFlow<Notification> = _notifications

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Response callbacks and close/failure callbacks originate on OkHttp threads, while requests
     * are registered by arbitrary coroutine dispatchers. A regular monitor keeps pending-response
     * mutation synchronous: most importantly, close() can fail every waiter before cancelling the
     * connection scope instead of launching cleanup into the scope it is about to cancel.
     */
    private val pendingLock = Any()
    private var webSocket: WebSocket? = null
    private var keepaliveJob: Job? = null
    private var nextId = 0
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonValue>>()
    @Volatile private var closed = false
    /** Wall-clock of the last inbound frame (incl. relay pong echoes) — drives zombie detection. */
    private val lastInboundAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    fun connect() {
        val request = Request.Builder().url(url.toWsUrl()).build()
        lastInboundAt.set(System.currentTimeMillis())
        val ws = client.newWebSocket(request, listener)
        webSocket = ws
        // The OkHttp keepalive pingInterval handles WS-level pings for loopback; the relay needs an
        // app-level {t:"ping"} because Cloudflare idle-closes the socket and echoes {t:"pong"}.
        keepaliveJob = scope.launch { keepaliveLoop() }
    }

    private suspend fun keepaliveLoop() {
        val relayPing = jsonObject("t" to "ping".jv()).encodedString
        while (scope.isActive && !closed) {
            delay(15.seconds)
            if (closed) return
            if (relayMode) {
                webSocket?.send(relayPing)
                // ZOMBIE DETECTION (relay only): the relay echoes {t:"pong"} to every ping, so a
                // healthy socket always has inbound traffic within a ping period. Cloudflare's edge
                // answers the WS-protocol pings itself, which masks a dead Durable-Object downlink —
                // the socket looks alive while every response silently vanishes. 45s of total
                // silence = half-open socket → declare the transport closed so the hub reconnects.
                // (Loopback needs none of this: OkHttp's pingInterval fails a dead socket itself.)
                if (System.currentTimeMillis() - lastInboundAt.get() > 45_000) {
                    onTransportClosed()
                    close()
                    return
                }
            }
        }
    }

    /**
     * First frame, required by the daemon: `initialize` carrying the daemon protocol version,
     * clientName "agenc-portal", the portal capabilities, and the local-trust `authCookie`.
     */
    suspend fun initializeHandshake(clientName: String, authCookie: String): JsonValue =
        send(
            "initialize",
            jsonObject(
                "protocolVersion" to "1.0.0".jv(),
                "protocol" to jsonObject("version" to "1.0.0".jv()),
                "clientName" to clientName.jv(),
                "capabilities" to portalCapabilities,
                "authCookie" to authCookie.jv(),
            ),
        )

    /** Send a JSON-RPC request and await its correlated response (or throw [ConnError]). */
    suspend fun send(
        method: String,
        params: JsonValue?,
        timeout: Duration? = 20.seconds,
    ): JsonValue {
        val deferred = CompletableDeferred<JsonValue>()
        val id = synchronized(pendingLock) {
            if (closed) throw ConnError.Closed
            nextId += 1
            pending[nextId] = deferred
            nextId
        }
        val text = jsonObject(
            "jsonrpc" to "2.0".jv(),
            "id" to id.jv(),
            "method" to method.jv(),
            "params" to (params ?: JsonValue.Null),
        ).encodedString
        val outgoing = if (relayMode) {
            jsonObject("t" to "data".jv(), "payload" to text.jv()).encodedString
        } else {
            text
        }

        val sent = webSocket?.send(outgoing) ?: false
        android.util.Log.d("JsonRpc", "out id=$id $method sent=$sent")
        if (!sent) {
            resume(id, ConnError.Closed)
            throw ConnError.Closed
        }
        return try {
            // Some daemon mutations (notably message.send) intentionally keep their JSON-RPC
            // request open for the whole agent turn. A null timeout lets transport close/zombie
            // detection remain the failure signal instead of inventing a delivery failure while
            // the model is still working or waiting for a tool approval.
            if (timeout == null) deferred.await() else withTimeout(timeout) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            android.util.Log.w("JsonRpc", "TIMEOUT id=$id $method after $timeout")
            synchronized(pendingLock) { pending.remove(id) }
            throw ConnError.Timeout(method)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        keepaliveJob?.cancel()
        webSocket?.close(NORMAL_CLOSURE, null)
        webSocket = null
        failAll(ConnError.Closed)
        scope.cancel()
    }

    // MARK: - internals

    private fun resume(id: Int, value: JsonValue) {
        val cont = synchronized(pendingLock) { pending.remove(id) }
        cont?.complete(value)
    }

    private fun resume(id: Int, error: Throwable) {
        val cont = synchronized(pendingLock) { pending.remove(id) }
        cont?.completeExceptionally(error)
    }

    private fun failAll(error: Throwable) {
        val conts = synchronized(pendingLock) {
            val all = pending.values.toList()
            pending.clear()
            all
        }
        conts.forEach { it.completeExceptionally(error) }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            lastInboundAt.set(System.currentTimeMillis()) // any frame (incl. pong) proves the downlink
            handleFrame(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            onTransportClosed()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onTransportClosed()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            onTransportClosed()
        }
    }

    private fun handleFrame(raw: String) {
        val text: String = if (relayMode) {
            // Unwrap `{"t":"data","payload":<json-rpc string>}`; drop welcome/peer/pong control frames.
            val env = JsonValue.parse(raw) ?: return
            if (env["t"]?.stringValue != "data") return
            env["payload"]?.stringValue ?: return
        } else {
            raw
        }
        when (val inbound = JsonRpcInbound.parse(text)) {
            is JsonRpcInbound.Response -> {
                android.util.Log.d("JsonRpc", "in response id=${inbound.id}")
                resume(inbound.id, inbound.result)
            }
            is JsonRpcInbound.Failure -> {
                android.util.Log.d("JsonRpc", "in error id=${inbound.id} code=${inbound.code}")
                inbound.id?.let {
                    resume(it, ConnError.Rpc(inbound.code, inbound.message, inbound.dataCode))
                }
            }
            is JsonRpcInbound.Notification -> {
                val ok = _notifications.tryEmit(Notification(inbound.method, inbound.params))
                if (!ok) android.util.Log.w("JsonRpc", "notification DROPPED (buffer full): ${inbound.method}")
            }
            null -> Unit
        }
    }

    @Volatile private var transportClosedEmitted = false

    private fun onTransportClosed() {
        if (transportClosedEmitted) return
        transportClosedEmitted = true
        android.util.Log.w("JsonRpc", "transport closed")
        failAll(ConnError.Closed)
        _notifications.tryEmit(Notification("\$transport.closed", JsonValue.Null))
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /** Shared OkHttp client; loopback WS keepalive rides on the pingInterval. */
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15.seconds.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        /**
         * Portal capabilities the client declares at handshake
         * (agenc-core app-server-protocol v0.4.0).
         */
        private val portalCapabilities: JsonValue = jsonObject(
            "portal.dashboard.read" to true.jv(),
            "portal.mobile.status.read" to true.jv(),
            // Explicit opt-in: Core may push status for sessions this phone has not attached yet.
            "portal.mobile.status.push.v1" to true.jv(),
            "portal.auth.read" to true.jv(),
            "portal.session.attach" to true.jv(),
            "portal.agent.list" to true.jv(),
            "portal.agent.start" to true.jv(),
            "portal.agent.attach" to true.jv(),
            "portal.transcript.read" to true.jv(),
            "portal.message.send" to true.jv(),
            // Core delivers privileged Ledger actions to capable initialized phones even when the
            // originating session is not currently open or attached in the Android UI.
            "portal.ledger.solana.sign.v1" to true.jv(),
        )

        /** OkHttp wants ws(s):// — map the daemon's ws/wss URLs through unchanged, https→wss if needed. */
        private fun String.toWsUrl(): String = when {
            startsWith("ws://") || startsWith("wss://") -> this
            startsWith("https://") -> "wss://" + removePrefix("https://")
            startsWith("http://") -> "ws://" + removePrefix("http://")
            else -> this
        }
    }
}
