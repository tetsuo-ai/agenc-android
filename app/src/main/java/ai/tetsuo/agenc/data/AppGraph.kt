package ai.tetsuo.agenc.data

import ai.tetsuo.agenc.AppConfig
import ai.tetsuo.agenc.notif.NotificationCoordinator
import android.content.Context

/**
 * Application-scoped dependency holder wiring the foundation layer.
 *
 * Mirrors the iOS singletons (AuthManager.shared / the app's DaemonHub): one [AuthManager] and one
 * [DaemonHub] live for the process lifetime. Initialize once from MainActivity (or an Application
 * subclass) with [init], then read [auth] and [hub] anywhere.
 *
 * Host selection: when the signed-in account has a relay client ticket (account or paired-Mac),
 * the hub dials the relay (`{RELAY_BASE}/v1/client?ticket=…`, relay-mode envelope). Otherwise it
 * falls back to the same-network local daemon (raw JSON-RPC at [AppConfig.LOCAL_DAEMON]). Call
 * [reconfigureForCurrentTicket] after a sign-in / pairing change to re-point the live connection.
 */
object AppGraph {

    @Volatile private var initialized = false

    lateinit var prefs: Prefs
        private set
    lateinit var secure: SecureStore
        private set
    lateinit var auth: AuthManager
        private set
    lateinit var hub: DaemonHub
        private set

    /** Local notification poster + channel owner. Shared by the hub (post) and the receiver (clear). */
    lateinit var notifications: NotificationCoordinator
        private set

    /** Ledger Flex BLE signing flow (fund/transfer with on-device approval). */
    lateinit var ledger: ai.tetsuo.agenc.ledger.LedgerController
        private set

    /**
     * The daemon auth cookie. With the real daemon this is the 64-hex value from
     * ~/.agenc/daemon.cookie, injected by the connector. The relay transport carries this empty
     * placeholder to the paired Mac, where the connector replaces it before forwarding initialize;
     * a direct local connection fails closed rather than dialing with a bundled credential.
     * Set via [DAEMON_COOKIE_OVERRIDE] (e.g. for a dev daemon) before [init].
     */
    var daemonCookie: String = ""

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            prefs = Prefs(app)
            secure = SecureStore(app)
            auth = AuthManager(secure = secure, prefs = prefs)
            // Restore the persisted relay ticket before resolving DaemonHub's initial endpoint.
            // Otherwise MainActivity can briefly start a loopback handshake and then reconfigure
            // to relay, producing a harmless but noisy second `initialize`/late close on phones.
            auth.restore()
            notifications = NotificationCoordinator(app).also { it.ensureChannels() }
            ledger = ai.tetsuo.agenc.ledger.LedgerController(app, prefs)
            hub = DaemonHub(
                endpoint = resolveEndpoint(),
                cookie = daemonCookie,
                relayMode = resolveRelayMode(),
                prefs = prefs,
                notifications = notifications,
            )
            initialized = true
        }
    }

    /**
     * Re-point the hub at the endpoint implied by the current relay ticket. Call this whenever
     * [AuthManager.relayTicketVersion] changes (login, pairing, unlink).
     */
    suspend fun reconfigureForCurrentTicket() {
        hub.reconfigure(url = resolveEndpoint(), relay = resolveRelayMode())
    }

    private fun resolveRelayMode(): Boolean = auth.relayTicket()?.isNotEmpty() == true

    private fun resolveEndpoint(): String {
        val ticket = auth.relayTicket()
        if (ticket.isNullOrEmpty()) return AppConfig.LOCAL_DAEMON
        val relayBase = auth.relayUrl()?.takeIf { it.isNotEmpty() } ?: AppConfig.RELAY_BASE
        return AppConfig.relayClientUrl(relayBase, ticket)
    }

    /** Set before [init] to override the daemon cookie (e.g. AGENC_DEV_COOKIE equivalent). */
    var DAEMON_COOKIE_OVERRIDE: String? = null
        set(value) {
            field = value
            if (!value.isNullOrEmpty()) daemonCookie = value
        }
}
