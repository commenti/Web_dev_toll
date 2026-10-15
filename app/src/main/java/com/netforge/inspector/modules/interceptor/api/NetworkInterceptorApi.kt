package com.netforge.inspector.modules.interceptor.api

import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.core.model.RawCapturedPacket

/**
 * File 14 — Public contract of the full-fidelity network interception layer.
 *
 * The implementation registers the built-in GeckoView WebExtension ("assets/inspector_ext")
 * and streams captured packets to listeners after parser enrichment.
 */
interface NetworkInterceptorApi {

    /** Listener invoked for every analyzed packet (already on background dispatch). */
    fun setListener(listener: Listener?)

    /**
     * Hooks the WebExtension native-messaging bridge into the given GeckoView
     * WebExtension instance. Must be called after the browser runtime is ready.
     */
    fun attachNativeBridge(nativeMessageReceiver: NativePacketReceiver)

    /** Removes all buffered logs from the Room buffer. */
    suspend fun clearLogs()

    interface Listener {
        fun onPacketLogged(entity: NetworkLogEntity)
        fun onCaptureCountChanged(count: Int)
    }

    /** Implemented by the browser module's extension bridge. */
    fun interface NativePacketReceiver {
        fun onNativePacket(json: String)
    }
}

/** Thread-safe helper for converting the WebExtension JSON wire format. */
object PacketWireFormat {
    const val TYPE_PACKET = "netforge_packet"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val NATIVE_PORT_NAME = "netforge_bridge"

    fun parseRawPacket(json: String): RawCapturedPacket? {
        return try {
            val obj = org.json.JSONObject(json)
            if (!obj.optString("type", NetworkInterceptorDefaults.DEFAULT_TYPE).startsWith("netforge")) return null
            RawCapturedPacket(
                requestId = obj.optString("requestId", ""),
                requestTimestamp = obj.optLong("requestTimestamp", System.currentTimeMillis()),
                domain = obj.optString("domain", ""),
                method = obj.optString("method", "GET"),
                url = obj.optString("url", ""),
                isApiCallHint = obj.optBoolean("isApiCall", false),
                requestHeaders = mapOfStrings(obj.optJSONObject("requestHeaders")),
                requestCookies = mapOfStrings(obj.optJSONObject("requestCookies")),
                requestBody = obj.optString("requestBody", ""),
                statusCode = obj.optInt("statusCode", 0),
                durationMs = obj.optLong("durationMs", 0),
                responseHeaders = mapOfStrings(obj.optJSONObject("responseHeaders")),
                responseCookies = mapOfStrings(obj.optJSONObject("responseCookies")),
                responseBody = obj.optString("responseBody", ""),
                error = obj.optString("error", "")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun mapOfStrings(o: org.json.JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val map = LinkedHashMap<String, String>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = o.optString(key, "")
        }
        return map
    }
}

private object NetworkInterceptorDefaults {
    const val DEFAULT_TYPE = ""
}
