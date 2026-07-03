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
        private const val INS_GET_PUBKEY = 0x05.toByte()
        private const val INS_SIGN_MESSAGE = 0x06.toByte()
        private const val INS_OPEN_APP = 0xD8.toByte()
        private const val P1_CONFIRM = 0x01.toByte()
        private const val P2_EXTEND = 0x01
        private const val P2_MORE = 0x02
        private const val MAX_CHUNK = 255
        private const val SW_OK = 0x9000
        private const val HARDENED = 0x80000000L

        /** The user's AgenC creator account on the Flex: 44'/501'/0'/0'. */
        val DEFAULT_PATH: List<Int> = listOf(44, 501, 0, 0)
    }
}
