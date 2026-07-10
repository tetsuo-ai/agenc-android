package ai.tetsuo.agenc.ledger

import java.io.IOException

/**
 * Client for the Ledger **Solana app** speaking APDUs over a [LedgerBleTransport].
 *
 * Two operations power the whole "fund/transfer with Ledger" flow:
 *  - [getPubkey] — derive the account's ed25519 public key (optionally confirming on-device)
 *  - [signTransaction] — sign a serialized legacy transaction MESSAGE; the device renders the
 *    transfer (amount + recipient) on its own screen and BLOCKS until the human approves. This is
 *    the security boundary: the private key never leaves the Flex, and the human verifies the
 *    exact recipient/amount on trusted hardware — not in the app, not in the agent.
 *
 * Wire format (app-solana):
 *  - CLA 0xE0; INS 0x05 GET_PUBKEY, 0x06 SIGN_MESSAGE
 *  - derivation path = [count] + 4-byte big-endian components (hardened bit 0x80000000)
 *  - SIGN payload = [numSigners=1] + path + message, chunked at 255 bytes;
 *    P2 flags per chunk: EXTEND(0x01) on continuations, MORE(0x02) unless last.
 */
class SolanaLedgerApp(private val transport: LedgerBleTransport) {

    /** Thrown when the device answers with a non-OK status word. */
    class ApduException(val sw: Int, message: String) : IOException(message)

    /** BOLOS metadata for the app currently running on the Ledger. */
    data class AppAndVersion(
        val name: String,
        val version: String,
        val flags: ByteArray? = null,
    )

    /** One common Solana derivation candidate, with stable persistence/UI identifiers. */
    data class AccountPath(
        val key: String,
        val label: String,
        val path: List<Int>,
    )

    /**
     * DMK's GetAppAndVersion command (BOLOS CLA 0xB0 / INS 0x01). It is available both on the
     * dashboard and inside an app, so the controller can decide whether it must close another app
     * before opening Solana instead of blindly sending OpenApp to the wrong target.
     */
    suspend fun getAppAndVersion(): AppAndVersion {
        val body = exchangeChecked(
            byteArrayOf(CLA_BOLOS, INS_GET_APP_AND_VERSION, 0x00, 0x00, 0x00),
        )
        var offset = 0
        fun nextByte(label: String): Int {
            if (offset >= body.size) throw IOException("getAppAndVersion: missing $label")
            return body[offset++].toInt() and 0xFF
        }
        fun nextField(label: String): ByteArray {
            val length = nextByte("$label length")
            if (offset + length > body.size) {
                throw IOException("getAppAndVersion: truncated $label")
            }
            return body.copyOfRange(offset, offset + length).also { offset += length }
        }

        val format = nextByte("format")
        if (format != 1) throw IOException("getAppAndVersion: unsupported format $format")
        val name = nextField("app name").toString(Charsets.UTF_8)
        val version = nextField("app version").toString(Charsets.UTF_8)
        val flags = if (offset < body.size) nextField("flags") else null
        if (offset != body.size) throw IOException("getAppAndVersion: trailing response bytes")
        return AppAndVersion(name = name, version = version, flags = flags)
    }

    /** DMK's CloseApp command. A successful command intentionally resets the device session. */
    suspend fun closeApp() {
        exchangeChecked(byteArrayOf(CLA_BOLOS, INS_CLOSE_APP, 0x00, 0x00, 0x00))
    }

    /**
     * BOLOS dashboard "open application" (INS 0xD8) — launches the named app (e.g. "Solana") so the
     * flow works even when the Flex is sitting on the dashboard. Best-effort: newer firmware opens
     * directly, older asks the human to confirm on the device. Sent to the dashboard, not the app.
     */
    suspend fun openApp(name: String) {
        val data = name.toByteArray(Charsets.US_ASCII)
        val apdu = byteArrayOf(CLA, INS_OPEN_APP, 0x00, 0x00, data.size.toByte()) + data
        exchangeChecked(apdu)
    }

    /** True when the status word means "you're on the dashboard / the Solana app isn't open". */
    fun isAppNotOpen(sw: Int): Boolean =
        sw == 0x6D00 || sw == 0x6D02 || sw == 0x6E00 || sw == 0x6E01 || sw == 0x6511 || sw == 0x6D07

    /** Derive + return the 32-byte ed25519 pubkey for [path]. */
    suspend fun getPubkey(path: List<Int>, confirmOnDevice: Boolean = false): ByteArray {
        val data = pathBytes(path)
        val apdu = byteArrayOf(
            CLA, INS_GET_PUBKEY,
            if (confirmOnDevice) 0x01 else 0x00, 0x00,
            data.size.toByte(),
        ) + data
        val resp = exchangeChecked(apdu)
        if (resp.size != 32) throw IOException("unexpected pubkey length ${resp.size}")
        return resp
    }

    /**
     * Sign a serialized legacy transaction message. Returns the 64-byte ed25519 signature.
     * Blocks (up to the transport's exchange timeout) while the human reviews on the device.
     */
    suspend fun signTransaction(path: List<Int>, message: ByteArray): ByteArray {
        val payload = byteArrayOf(0x01) + pathBytes(path) + message
        val chunks = payload.toList().chunked(MAX_CHUNK).map { it.toByteArray() }
        var last = ByteArray(0)
        for ((i, chunk) in chunks.withIndex()) {
            val first = i == 0
            val lastChunk = i == chunks.lastIndex
            var p2 = 0
            if (!first) p2 = p2 or P2_EXTEND
            if (!lastChunk) p2 = p2 or P2_MORE
            val apdu = byteArrayOf(
                CLA, INS_SIGN_MESSAGE, P1_CONFIRM, p2.toByte(), chunk.size.toByte(),
            ) + chunk
            last = exchangeChecked(apdu)
        }
        if (last.size != 64) throw IOException("unexpected signature length ${last.size}")
        return last
    }

    /** Send one APDU, validate the trailing status word, and return the payload without it. */
    private suspend fun exchangeChecked(apdu: ByteArray): ByteArray {
        val resp = transport.exchange(apdu)
        if (resp.size < 2) throw IOException("short APDU response")
        val sw = ((resp[resp.size - 2].toInt() and 0xFF) shl 8) or (resp[resp.size - 1].toInt() and 0xFF)
        if (sw != SW_OK) throw ApduException(sw, swMessage(sw))
        return resp.copyOfRange(0, resp.size - 2)
    }

    private fun pathBytes(path: List<Int>): ByteArray {
        val out = ByteArray(1 + path.size * 4)
        out[0] = path.size.toByte()
        for ((i, raw) in path.withIndex()) {
            val v = raw.toLong() or HARDENED
            val o = 1 + i * 4
            out[o] = ((v shr 24) and 0xFF).toByte()
            out[o + 1] = ((v shr 16) and 0xFF).toByte()
            out[o + 2] = ((v shr 8) and 0xFF).toByte()
            out[o + 3] = (v and 0xFF).toByte()
        }
        return out
    }

    private fun swMessage(sw: Int): String = when (sw) {
        0x6985, 0x6986 -> "rejected on the device"
        // 0x6Dxx = INS/app not available on the current app; 0x6Exx = wrong CLA (dashboard/other app).
        0x6D00, 0x6D02, 0x6E00, 0x6E01, 0x6511, 0x6D07 -> "open the Solana app on your Ledger Flex"
        0x5515, 0x6804 -> "the Ledger is locked — unlock it"
        0x6700 -> "wrong data length"
        0x6982 -> "the Ledger needs the Solana app open + unlocked"
        else -> "ledger error 0x${sw.toString(16)} — open the Solana app and retry"
    }

    companion object {
        private const val CLA = 0xE0.toByte()
        private const val CLA_BOLOS = 0xB0.toByte()
        private const val INS_GET_APP_AND_VERSION = 0x01.toByte()
        private const val INS_GET_PUBKEY = 0x05.toByte()
        private const val INS_SIGN_MESSAGE = 0x06.toByte()
        private const val INS_OPEN_APP = 0xD8.toByte()
        private const val INS_CLOSE_APP = 0xA7.toByte()
        private const val P1_CONFIRM = 0x01.toByte()
        private const val P2_EXTEND = 0x01
        private const val P2_MORE = 0x02
        private const val MAX_CHUNK = 255
        private const val SW_OK = 0x9000
        private const val HARDENED = 0x80000000L

        /** The user's AgenC creator account on the Flex: 44'/501'/0'/0'. */
        val DEFAULT_PATH: List<Int> = listOf(44, 501, 0, 0)

        /** Stable non-secret key used to persist a derivation path. */
        fun pathKey(path: List<Int>): String = path.joinToString("/")

        /** Human-readable fully qualified hardened path. */
        fun pathLabel(path: List<Int>): String = path.joinToString("/") { "$it'" }

        /** Parse only the Solana paths this app emits; arbitrary paths are never accepted. */
        fun pathFromKey(key: String?): List<Int>? {
            val parts = key?.split('/')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (parts.size !in 3..4 || parts[0] != 44 || parts[1] != 501) return null
            if (parts.drop(2).any { it !in 0..MAX_LEDGER_ACCOUNT_INDEX }) return null
            if (parts.size == 4 && parts[2] != 0 && parts[3] != 0) return null
            return parts
        }

        /**
         * Bounded discovery order shared with AgenC marketplace Ledger discovery:
         *
         *  1. Ledger-Live-style `N/0`, [count] entries.
         *  2. Legacy bare `N`, at least ten entries (`max(count, 10)`).
         *  3. Alternate `0/N`, [count] entries, deduplicating `0/0` from family 1.
         *
         * Solana derivation space is infinite, so callers page [count] upward rather than claiming
         * to enumerate "all" accounts. The returned list is deterministically ordered and unique.
         */
        fun commonAccountPaths(count: Int): List<AccountPath> {
            require(count in 1..MAX_COMMON_ACCOUNT_COUNT) {
                "common account count must be 1..$MAX_COMMON_ACCOUNT_COUNT"
            }
            val paths = LinkedHashMap<String, AccountPath>()
            fun add(path: List<Int>) {
                val key = pathKey(path)
                paths.putIfAbsent(key, AccountPath(key, pathLabel(path), path))
            }
            repeat(count) { index -> add(listOf(44, 501, index, 0)) }
            repeat(maxOf(count, MIN_BARE_ACCOUNT_COUNT)) { index -> add(listOf(44, 501, index)) }
            repeat(count) { index -> add(listOf(44, 501, 0, index)) }
            return paths.values.toList()
        }

        /** Exact account index lookup for wallets outside the fast bounded common-account scan. */
        fun accountPathsAtIndex(index: Int): List<AccountPath> {
            require(index in 0..MAX_LEDGER_ACCOUNT_INDEX) {
                "Ledger account index must be 0..$MAX_LEDGER_ACCOUNT_INDEX"
            }
            val paths = LinkedHashMap<String, AccountPath>()
            fun add(path: List<Int>) {
                val key = pathKey(path)
                paths.putIfAbsent(key, AccountPath(key, pathLabel(path), path))
            }
            add(listOf(44, 501, index, 0))
            add(listOf(44, 501, index))
            add(listOf(44, 501, 0, index))
            return paths.values.toList()
        }

        const val MAX_COMMON_ACCOUNT_COUNT = 25
        const val MAX_LEDGER_ACCOUNT_INDEX = 1_000_000
        private const val MIN_BARE_ACCOUNT_COUNT = 10
    }
}
