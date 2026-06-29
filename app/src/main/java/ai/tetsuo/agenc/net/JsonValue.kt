package ai.tetsuo.agenc.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Direct port of the iOS app's JSONValue.swift — type-erased JSON for the dynamic daemon
 * payloads (RPC params, tool input, metadata).
 *
 * On Android we lean on kotlinx.serialization's [JsonElement] tree as the underlying
 * representation and expose the same convenience accessors the Swift code used
 * (`["key"]`, `stringValue`, `intValue`, `boolValue`, `arrayValue`, `stringArray`,
 * `encodedString`). A thin builder DSL ([jsonObject], [jsonArray]) mirrors the Swift
 * `.object([...])` / `.array([...])` literals used all over DaemonHub.
 */
sealed interface JsonValue {
    /** The wrapped kotlinx.serialization element (the canonical wire form). */
    val element: JsonElement

    data class Str(val value: String) : JsonValue {
        override val element: JsonElement get() = JsonPrimitive(value)
    }

    data class Num(val value: Double) : JsonValue {
        override val element: JsonElement get() = JsonPrimitive(value)
    }

    data class Bool(val value: Boolean) : JsonValue {
        override val element: JsonElement get() = JsonPrimitive(value)
    }

    data class Obj(val value: Map<String, JsonValue>) : JsonValue {
        override val element: JsonElement
            get() = buildJsonObject { value.forEach { (k, v) -> put(k, v.element) } }
    }

    data class Arr(val value: List<JsonValue>) : JsonValue {
        override val element: JsonElement
            get() = buildJsonArray { value.forEach { add(it.element) } }
    }

    data object Null : JsonValue {
        override val element: JsonElement get() = JsonNull
    }

    // MARK: - Convenience accessors (mirror the Swift API)

    /** Subscript by key — returns null on non-objects or missing keys. */
    operator fun get(key: String): JsonValue? = (this as? Obj)?.value?.get(key)

    val stringValue: String? get() = (this as? Str)?.value
    val doubleValue: Double? get() = (this as? Num)?.value
    val intValue: Int? get() = doubleValue?.toInt()
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val arrayValue: List<JsonValue>? get() = (this as? Arr)?.value
    val stringArray: List<String> get() = arrayValue?.mapNotNull { it.stringValue } ?: emptyList()

    /** Compact JSON string for this value (used as the relay/local wire payload). */
    val encodedString: String get() = WireJson.encodeToString(JsonElement.serializer(), element)

    companion object {
        /** Re-create a [JsonValue] from a parsed [JsonElement] tree. */
        fun from(e: JsonElement): JsonValue = when (e) {
            is JsonNull -> Null
            is JsonObject -> Obj(e.mapValues { from(it.value) })
            is JsonArray -> Arr(e.map { from(it) })
            is JsonPrimitive -> when {
                e.isString -> Str(e.content)
                e.booleanOrNull != null -> Bool(e.boolean)
                e.doubleOrNull != null -> Num(e.double)
                else -> Str(e.contentOrNull ?: "")
            }
        }

        /** Parse a JSON string into a [JsonValue], or null on malformed input. */
        fun parse(text: String): JsonValue? = runCatching {
            from(WireJson.parseToJsonElement(text))
        }.getOrNull()
    }
}

/** Shared lenient JSON codec used for all wire encode/decode. */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = true
}

// MARK: - Builder helpers (mirror Swift's `.object([...])` / `.array([...])`)

fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj =
    JsonValue.Obj(pairs.toMap())

fun jsonObject(map: Map<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(map)

fun jsonArray(values: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(values)

fun jsonArray(vararg values: JsonValue): JsonValue.Arr = JsonValue.Arr(values.toList())

fun String.jv(): JsonValue.Str = JsonValue.Str(this)
fun Int.jv(): JsonValue.Num = JsonValue.Num(this.toDouble())
fun Double.jv(): JsonValue.Num = JsonValue.Num(this)
fun Boolean.jv(): JsonValue.Bool = JsonValue.Bool(this)
