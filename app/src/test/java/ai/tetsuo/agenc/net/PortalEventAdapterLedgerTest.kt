package ai.tetsuo.agenc.net

import ai.tetsuo.agenc.model.EventMsg
import ai.tetsuo.agenc.ledger.Base58
import ai.tetsuo.agenc.model.LedgerTransferStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PortalEventAdapterLedgerTest {
    private val now = Instant.parse("2026-07-10T10:00:00Z").toEpochMilli()

    @Test
    fun `accepts exact typed action with decimal string lamports`() {
        val action = PortalEventAdapter.parseLedgerClientAction(validAction(), now)

        assertNotNull(action)
        assertEquals("ledger_12345678", action?.intentId)
        assertEquals(RESPONSE_NONCE, action?.responseNonce)
        assertEquals(1_000_000L, action?.lamports)
        assertEquals(SYSTEM_ADDRESS, action?.to)
    }

    @Test
    fun `maps typed action even when display questions are empty`() {
        val mapped = PortalEventAdapter.map(
            JsonRpcConnection.Notification(
                method = "event.user_input_request",
                params = jsonObject(
                    "sessionId" to "session-1".jv(),
                    "requestId" to "request-1".jv(),
                    "questions" to jsonArray(emptyList()),
                    "clientAction" to validAction(
                        expiresAt = Instant.now().plusSeconds(600).toString(),
                    ),
                ),
            ),
        )

        val request = (mapped?.msg as? EventMsg.RequestUserInput)?.pending
        assertNotNull(request?.clientAction)
        assertEquals("ledger_transfer", request?.questionId)
    }

    @Test
    fun `rejects numeric overflow and non-decimal lamports`() {
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(lamports = JsonValue.Num(9_007_199_254_740_992.0)),
                now,
            ),
        )
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(lamports = "1e9".jv()),
                now,
            ),
        )
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(lamports = "9223372036854775808".jv()),
                now,
            ),
        )
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(lamports = "0001".jv()),
                now,
            ),
        )
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(lamports = "0".jv()),
                now,
            ),
        )
    }

    @Test
    fun `rejects expired wrong provenance and invalid recipient actions`() {
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(expiresAt = "2026-07-10T09:59:59Z"),
                now,
            ),
        )
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(source = "model-output"),
                now,
            ),
        )
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(to = "not-a-solana-address"),
                now,
            ),
        )
    }

    @Test
    fun `rejects generic input attempting to omit typed provenance`() {
        val mapped = PortalEventAdapter.map(
            JsonRpcConnection.Notification(
                method = "event.user_input_request",
                params = jsonObject(
                    "sessionId" to "session-1".jv(),
                    "requestId" to "request-1".jv(),
                    "questions" to jsonArray(emptyList()),
                ),
            ),
        )

        assertNull(mapped)
    }

    @Test
    fun `fails closed instead of mapping malformed privileged action as generic input`() {
        val mapped = PortalEventAdapter.map(
            JsonRpcConnection.Notification(
                method = "event.user_input_request",
                params = jsonObject(
                    "sessionId" to "session-1".jv(),
                    "requestId" to "request-1".jv(),
                    "questions" to jsonArray(
                        jsonObject(
                            "id" to "q".jv(),
                            "question" to "Type any answer".jv(),
                        ),
                    ),
                    "clientAction" to validAction(source = "model-output"),
                ),
            ),
        )

        assertNull(mapped)
    }

    @Test
    fun `rejects missing or weak response nonce`() {
        val missing = (validAction() as JsonValue.Obj).value - "responseNonce"
        assertNull(PortalEventAdapter.parseLedgerClientAction(jsonObject(missing), now))
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                jsonObject((validAction() as JsonValue.Obj).value + ("responseNonce" to "short".jv())),
                now,
            ),
        )
    }

    @Test
    fun `rejects intent ids outside the Core safe alphabet`() {
        assertNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(intentId = "ledger_1234567!"),
                now,
            ),
        )
        assertNotNull(
            PortalEventAdapter.parseLedgerClientAction(
                validAction(intentId = "ledger_safe-ID_123"),
                now,
            ),
        )
    }

    @Test
    fun `parses only explicit unconfirmed Core Ledger clean result`() {
        val signature = Base58.encode(ByteArray(64) { (it + 1).toByte() })
        val result = jsonObject(
            "type" to "ledger_solana_transfer_receipt_v1".jv(),
            "intentId" to "ledger_12345678".jv(),
            "status" to "submitted".jv(),
            "network" to "mainnet-beta".jv(),
            "to" to SYSTEM_ADDRESS.jv(),
            "lamports" to "1000000".jv(),
            "from" to SYSTEM_ADDRESS.jv(),
            "signature" to signature.jv(),
            "confirmed" to false.jv(),
        ).encodedString
        val mapped = PortalEventAdapter.map(
            JsonRpcConnection.Notification(
                method = "event.session_event",
                params = jsonObject(
                    "sessionId" to "session-1".jv(),
                    "event" to jsonObject(
                        "type" to "tool_call_completed".jv(),
                        "payload" to jsonObject(
                            "callId" to "call-1".jv(),
                            "result" to result.jv(),
                            "isError" to false.jv(),
                        ),
                    ),
                ),
            ),
        )

        val completed = mapped?.msg as? EventMsg.ToolCallCompleted
        assertEquals(LedgerTransferStatus.Submitted, completed?.ledgerResult?.status)
        assertEquals(false, completed?.ledgerResult?.confirmed)
        assertEquals(false, completed?.ledgerResult?.toJson()?.contains("responseNonce"))
    }

    private fun validAction(
        source: String = "agenc-core",
        intentId: String = "ledger_12345678",
        to: String = SYSTEM_ADDRESS,
        lamports: JsonValue = "1000000".jv(),
        expiresAt: String = "2026-07-10T10:10:00Z",
    ): JsonValue = jsonObject(
        "type" to "ledger_solana_transfer_v1".jv(),
        "source" to source.jv(),
        "targetCapability" to "portal.ledger.solana.sign.v1".jv(),
        "network" to "mainnet-beta".jv(),
        "intentId" to intentId.jv(),
        "responseNonce" to RESPONSE_NONCE.jv(),
        "to" to to.jv(),
        "lamports" to lamports,
        "expiresAt" to expiresAt.jv(),
    )

    private companion object {
        const val SYSTEM_ADDRESS = "11111111111111111111111111111111"
        const val RESPONSE_NONCE = "123e4567-e89b-12d3-a456-426614174000"
    }
}
