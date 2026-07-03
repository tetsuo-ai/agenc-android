package ai.tetsuo.agenc.ledger

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Dependency-light Solana helpers for the Ledger signing flow.
 *
 * Deliberately NO external Solana SDK: the app only ever needs to (1) serialize one legacy
 * SystemProgram transfer message for the hardware wallet to sign, (2) wrap the returned 64-byte
 * signature into a wire transaction, and (3) talk to a JSON-RPC endpoint for blockhash / balance /
 * send. Everything here is a direct, auditable port of the tiny slices of the wire format we use
 * (shortvec/compact-u16, base58, the legacy message layout), built on the stack the app already
 * ships: OkHttp + kotlinx-serialization + java.util.Base64 (minSdk 26).
 *
 * All serialization functions are pure and deterministic — same inputs, same bytes — so they can
 * be unit-tested against fixtures from `@solana/web3.js` without a device. Nothing here logs key
 * material or amounts above Log.d.
 */

/**
 * Base58 with the Bitcoin alphabet — the encoding Solana uses for pubkeys, blockhashes, and
 * transaction signatures.
 *
 * Leading 0x00 bytes encode as leading '1' characters and vice versa (the classic base58check
 * rule), which matters for real keys: a pubkey starting with zero bytes must round-trip to the
 * exact same 32 bytes.
 */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    /** Digit value for each char code, or -1 when the char is not in the alphabet. */
    private val INDEXES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    /** Encodes [input] to a base58 string. Empty input encodes to the empty string. */
    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros] == 0.toByte()) zeros++

        val sb = StringBuilder()
        var num = BigInteger(1, input)
        while (num.signum() > 0) {
            val divRem = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[divRem[1].toInt()])
            num = divRem[0]
        }
        repeat(zeros) { sb.append('1') }
        return sb.reverse().toString()
    }

    /**
     * Decodes a base58 string back to bytes.
     *
     * @throws IllegalArgumentException on any character outside the Bitcoin alphabet.
     */
    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == '1') zeros++

        var num = BigInteger.ZERO
        for (c in input) {
            val digit = if (c.code < INDEXES.size) INDEXES[c.code] else -1
            require(digit >= 0) { "invalid base58 character '$c'" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        // BigInteger.toByteArray() may prepend a sign byte and never yields leading zeros — we
        // restore those from the counted '1' prefix instead.
        val magnitude = when {
            num.signum() == 0 -> ByteArray(0)
            else -> num.toByteArray().let { raw ->
                if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            }
        }
        return ByteArray(zeros) + magnitude
    }
}

/**
 * Legacy (non-versioned) Solana transaction serialization for a single SystemProgram transfer.
 *
 * Split from the RPC client on purpose: these are pure byte-level functions with no I/O, so the
 * exact bytes shown to (and signed by) the Ledger can be verified offline. The message produced by
 * [transferMessage] is what gets signed; [wrapSignedTx] then packages signature + message into the
 * wire format `sendTransaction` expects.
 */
object SolanaTx {
    /** SystemProgram id — 32 zero bytes ("11111111111111111111111111111111" in base58). */
    val SYSTEM_PROGRAM_ID = ByteArray(32)

    /** SystemProgram instruction index for Transfer (little-endian u32 on the wire). */
    private const val TRANSFER_INSTRUCTION_INDEX = 2

    /**
     * Serializes a legacy message with exactly one SystemProgram::Transfer instruction.
     *
     * Layout: header `[1 required sig][0 readonly signed][1 readonly unsigned]`, then
     * shortvec-prefixed account keys `[from (writable signer), to (writable), SystemProgram
     * (readonly)]`, the 32-byte recent blockhash, and a shortvec of one compiled instruction
     * (`programIdIndex`, shortvec account indices, shortvec data).
     *
     * Self-transfers (`to` == `from`) are legal on Solana but the account list must not contain
     * duplicates, so the key is deduped: keys become `[from, SystemProgram]` and the instruction
     * references account index 0 twice. The header is unchanged (still one signer, one readonly
     * unsigned program).
     *
     * @param from sender pubkey, 32 bytes (the sole signer; fee payer).
     * @param to recipient pubkey, 32 bytes.
     * @param lamports amount to move; must be >= 0.
     * @param recentBlockhash 32 bytes from [SolanaRpc.latestBlockhash].
     * @return the message bytes to hand to the signer.
     */
    fun transferMessage(from: ByteArray, to: ByteArray, lamports: Long, recentBlockhash: ByteArray): ByteArray {
        require(from.size == 32) { "from pubkey must be 32 bytes, got ${from.size}" }
        require(to.size == 32) { "to pubkey must be 32 bytes, got ${to.size}" }
        require(recentBlockhash.size == 32) { "recentBlockhash must be 32 bytes, got ${recentBlockhash.size}" }
        require(lamports >= 0) { "lamports must be non-negative" }

        val selfTransfer = from.contentEquals(to)
        val accountKeys = if (selfTransfer) {
            listOf(from, SYSTEM_PROGRAM_ID)
        } else {
            listOf(from, to, SYSTEM_PROGRAM_ID)
        }
        val programIdIndex = accountKeys.size - 1 // SystemProgram is always last.
        val instructionAccounts = if (selfTransfer) byteArrayOf(0, 0) else byteArrayOf(0, 1)

        // Instruction data: u32 LE instruction index (2 = Transfer) + u64 LE lamports.
        val data = ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TRANSFER_INSTRUCTION_INDEX)
            .putLong(lamports)
            .array()

        val out = ByteArrayOutputStream()
        // Header: numRequiredSignatures, numReadonlySignedAccounts, numReadonlyUnsignedAccounts.
        out.write(1)
        out.write(0)
        out.write(1)
        // Account keys.
        out.write(shortvec(accountKeys.size))
        accountKeys.forEach { out.write(it) }
        // Recent blockhash.
        out.write(recentBlockhash)
        // Instructions.
        out.write(shortvec(1))
        out.write(programIdIndex)
        out.write(shortvec(instructionAccounts.size))
        out.write(instructionAccounts)
        out.write(shortvec(data.size))
        out.write(data)
        return out.toByteArray()
    }

    /**
     * Wraps a signed message into the wire transaction: `shortvec(1)` + the 64-byte signature +
     * the exact message bytes that were signed. Feed the result to [SolanaRpc.sendTransaction].
     */
    fun wrapSignedTx(signature: ByteArray, message: ByteArray): ByteArray {
        require(signature.size == 64) { "signature must be 64 bytes, got ${signature.size}" }
        val out = ByteArrayOutputStream()
        out.write(shortvec(1))
        out.write(signature)
        out.write(message)
        return out.toByteArray()
    }

    /**
     * Solana's shortvec / compact-u16 length encoding: 7 value bits per byte, MSB set on every
     * byte except the last (little-endian groups). 0..127 → 1 byte, 128..16383 → 2 bytes, etc.
     */
    internal fun shortvec(length: Int): ByteArray {
        require(length in 0..0xFFFF) { "shortvec length out of range: $length" }
        val out = ByteArrayOutputStream()
        var rem = length
        while (true) {
            val byte = rem and 0x7F
            rem = rem ushr 7
            if (rem == 0) {
                out.write(byte)
                break
            }
            out.write(byte or 0x80)
        }
        return out.toByteArray()
    }
}

/**
 * Minimal Solana JSON-RPC 2.0 client — just the three calls the send flow needs.
 *
 * Follows the app's HTTP conventions (see BackendApiClient): blocking OkHttp on [Dispatchers.IO],
 * kotlinx-serialization with `ignoreUnknownKeys`, exceptions carrying the server's message. RPC
 * errors surface as [IOException] with the node's error message so callers can show it verbatim.
 *
 * Stateless apart from the shared connection pool; safe to construct per use.
 */
class SolanaRpc(private val endpoint: String = "https://api.mainnet-beta.solana.com") {

    /** Fetches the latest blockhash (confirmed commitment) and returns its raw 32 bytes. */
    suspend fun latestBlockhash(): ByteArray {
        val params = buildJsonArray {
            add(buildJsonObject { put("commitment", "confirmed") })
        }
        val result = call("getLatestBlockhash", params)
        val blockhash = result.jsonObject["value"]?.jsonObject?.get("blockhash")?.jsonPrimitive?.contentOrNull
            ?: throw IOException("getLatestBlockhash: missing result.value.blockhash")
        val bytes = Base58.decode(blockhash)
        if (bytes.size != 32) throw IOException("getLatestBlockhash: blockhash decoded to ${bytes.size} bytes, expected 32")
        return bytes
    }

    /** Returns the lamport balance of [pubkeyBase58] (confirmed commitment). */
    suspend fun balanceLamports(pubkeyBase58: String): Long {
        val params = buildJsonArray {
            add(JsonPrimitive(pubkeyBase58))
            add(buildJsonObject { put("commitment", "confirmed") })
        }
        val result = call("getBalance", params)
        return result.jsonObject["value"]?.jsonPrimitive?.longOrNull
            ?: throw IOException("getBalance: missing numeric result.value")
    }

    /**
     * Broadcasts a fully signed transaction (the bytes from [SolanaTx.wrapSignedTx]) as base64
     * with `preflightCommitment: confirmed`, and returns the base58 transaction signature.
     *
     * @throws IOException with the node's error message (e.g. blockhash expired, insufficient
     *   funds caught in preflight) when the RPC returns an error.
     */
    suspend fun sendTransaction(signedTx: ByteArray): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(signedTx)
        val params = buildJsonArray {
            add(JsonPrimitive(encoded))
            add(
                buildJsonObject {
                    put("encoding", "base64")
                    put("preflightCommitment", "confirmed")
                },
            )
        }
        val result = call("sendTransaction", params)
        return result.jsonPrimitive.contentOrNull
            ?: throw IOException("sendTransaction: missing signature in result")
    }

    // MARK: - JSON-RPC plumbing

    /**
     * POSTs one JSON-RPC 2.0 request and returns the `result` element. Throws [IOException]
     * carrying the RPC `error.message` (plus `error.code`) on error responses, and on transport
     * or malformed-body failures.
     */
    private suspend fun call(method: String, params: JsonArray): kotlinx.serialization.json.JsonElement =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ids.incrementAndGet())
                put("method", method)
                put("params", params)
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toString().toRequestBody(jsonMedia))
                .build()

            Log.d(TAG, "rpc $method")
            client.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    throw IOException("$method: HTTP ${response.code}")
                }
                val root = runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrElse { throw IOException("$method: malformed RPC response") }

                root["error"]?.let { error ->
                    val obj = runCatching { error.jsonObject }.getOrNull()
                    val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
                    val code = obj?.get("code")?.jsonPrimitive?.contentOrNull
                    throw IOException(if (code != null) "$method: $message (code $code)" else "$method: $message")
                }
                root["result"] ?: throw IOException("$method: missing result")
            }
        }

    private companion object {
        const val TAG = "SolanaRpc"
        val json = Json { ignoreUnknownKeys = true }
        val jsonMedia = "application/json".toMediaType()
        val ids = AtomicLong(0)

        /** One shared pool for all instances; RPC nodes can be slow, so generous read timeout. */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
