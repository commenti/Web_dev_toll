package com.netforge.inspector.modules.interceptor.internal

import com.netforge.inspector.core.database.InspectorDatabase
import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.core.model.AnalyzedPacket
import com.netforge.inspector.modules.interceptor.api.NetworkInterceptorApi
import com.netforge.inspector.modules.interceptor.api.PacketWireFormat
import com.netforge.inspector.modules.parser.api.TrafficParserApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension

/**
 * File 15 — Bridges the built-in GeckoView WebExtension to the parser + Room
 * buffer layers over bidirectional native messaging.
 *
 * Wire flow (background.js -> here):
 *   port = browser.runtime.connectNative("netforge_bridge")
 *   port.postMessage(jsonPacket)
 *   -> MessageDelegate.onConnect(Port) registers a PortDelegate
 *   -> PortDelegate.onPortMessage routes packets into analyze + Room upsert + UI listener
 */
class GeckoExtensionBridge(
    private val parser: TrafficParserApi,
    private val database: InspectorDatabase
) : NetworkInterceptorApi {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listener: NetworkInterceptorApi.Listener? = null
    private var openPort: WebExtension.Port? = null
    private var externalReceiver: NetworkInterceptorApi.NativePacketReceiver? = null

    override fun setListener(listener: NetworkInterceptorApi.Listener?) {
        this.listener = listener
        // Publish the current count immediately so UI badges stay accurate.
        listener?.let { l ->
            scope.launch {
                val count = database.networkLogDao().observeCount().first()
                l.onCaptureCountChanged(count)
            }
        }
    }

    override fun attachNativeBridge(nativeMessageReceiver: NetworkInterceptorApi.NativePacketReceiver) {
        externalReceiver = nativeMessageReceiver
    }

    /** Entry point for JSON delivered outside the port channel (e.g. onMessage). */
    fun forwardExternalJson(json: String) {
        scope.launch { handleRawJson(json) }
    }

    /** Wires delegate + port handling for a specific loaded extension.
     *  Called by GeckoBrowserManager after `ensureBuiltIn(...)` resolves.
     */
    fun hook(extension: WebExtension) {
        extension.setMessageDelegate(
            object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    openPort = port
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            val json = message.toString()
                            scope.launch { handleRawJson(json) }
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (openPort === port) openPort = null
                        }
                    })
                }
            },
            NATIVE_APP_NAME
        )
    }

    private suspend fun handleRawJson(json: String) {
        if (json.isBlank()) return
        when (guessWireType(json)) {
            PacketWireFormat.TYPE_PONG, PacketWireFormat.TYPE_PING -> return
        }
        val raw = PacketWireFormat.parseRawPacket(json) ?: return

        val analyzed: AnalyzedPacket = parser.analyze(raw)
        val entity = analyzed.toEntity()
        database.networkLogDao().upsert(entity)
        listener?.onPacketLogged(entity)
        listener?.onCaptureCountChanged(currentCount())
    }

    private suspend fun currentCount(): Int =
        database.networkLogDao().observeCount().first()

    override suspend fun clearLogs() {
        database.networkLogDao().clearAll()
        listener?.onCaptureCountChanged(0)
    }

    fun postControlMessage(type: String): Boolean {
        val port = openPort ?: return false
        port.postMessage(JSONObject().put("type", type))
        return true
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val NATIVE_APP_NAME = "runtime"
        private const val DEFAULT_TYPE = ""

        private fun guessWireType(json: String): String {
            return try {
                JSONObject(json).optString("type", DEFAULT_TYPE)
            } catch (_: Exception) {
                DEFAULT_TYPE
            }
        }
    }
}

/** AnalyzedPacket -> Room row (JSON fields preserved for lossless export). */
private fun AnalyzedPacket.toEntity(): NetworkLogEntity {
    val r = raw
    return NetworkLogEntity(
        requestId = r.requestId.takeIf { it.isNotBlank() }
            ?: NetworkLogEntity.localId(),
        timestamp = r.requestTimestamp,
        domain = r.domain,
        method = r.method.uppercase(),
        url = r.url,
        requestHeadersJson = JSONObject(r.requestHeaders).toString(),
        requestCookiesJson = JSONObject(r.requestCookies).toString(),
        extractedTokensJson = com.netforge.inspector.modules.parser.internal.JwtAndCookieParserImpl.tokensToJson(extractedTokens),
        requestBody = r.requestBody,
        statusCode = r.statusCode,
        durationMs = r.durationMs,
        responseHeadersJson = JSONObject(r.responseHeaders).toString(),
        responseCookiesJson = JSONObject(r.responseCookies).toString(),
        responseBody = r.responseBody,
        category = analyzedCategory.name,
        isApiCall = analyzedCategory != com.netforge.inspector.core.model.TrafficCategory.PAGE,
        error = r.error
    ).let { entity ->
        entity
    }
}

// Expose category via a private property so `toEntity` stays readable.
private val AnalyzedPacket.analyzedCategory: com.netforge.inspector.core.model.TrafficCategory
    get() = category
