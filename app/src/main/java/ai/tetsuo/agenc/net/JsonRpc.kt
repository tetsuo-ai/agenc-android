package ai.tetsuo.agenc.net

/**
 * Direct port of the iOS app's JSONRPC.swift — the JSON-RPC 2.0 inbound-frame classifier.
 *
 * The outbound request envelope is built inline in [JsonRpcConnection.send] (mirroring the
 * Swift `send(_:_:)` which constructs the object literal there too).
 */
sealed interface JsonRpcInbound {
    data class Response(val id: Int, val result: JsonValue) : JsonRpcInbound
    data class Failure(val id: Int?, val code: Int, val message: String) : JsonRpcInbound
    data class Notification(val method: String, val params: JsonValue) : JsonRpcInbound

    companion object {
        /** Classify a raw JSON-RPC frame: response / error / server notification, or null. */
        fun parse(text: String): JsonRpcInbound? {
            val value = JsonValue.parse(text) ?: return null

            value["method"]?.stringValue?.let { method ->
                return Notification(method, value["params"] ?: JsonValue.Null)
            }
            val id = value["id"]?.intValue
            val err = value["error"]
            if (err is JsonValue.Obj) {
                return Failure(
                    id = id,
                    code = err["code"]?.intValue ?: -1,
                    message = err["message"]?.stringValue ?: "RPC error",
                )
            }
            if (id != null) {
                return Response(id, value["result"] ?: JsonValue.Null)
            }
            return null
        }
    }
}
