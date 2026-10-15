package com.netforge.inspector.ui.state

import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.core.model.RepeaterResponse
import com.netforge.inspector.modules.repeater.api.RequestRepeaterApi

/**
 * File 22 — Immutable-ish UI state for the single Compose screen.
 */
data class InspectorUiState(
    val urlBarText: String = "",
    val pageProgress: Int = 0,
    val canGoBack: Boolean = false,
    val consoleOpen: Boolean = false,
    val captureCount: Int = 0,
    val packets: List<NetworkLogEntity> = emptyList(),
    val selectedPacketId: String? = null,
    val expandedPacket: NetworkLogEntity? = null,
    val repeaterSheet: RepeaterSheetState = RepeaterSheetState(),
    val exportStatus: ExportStatus = ExportStatus(),
    val captureEnabled: Boolean = true,
    val urlValidationError: String? = null
)

/** Holds a cloned packet inside the request editor & repeater sheet. */
data class RepeaterSheetState(
    val open: Boolean = false,
    val sourcePacketId: String? = null,
    val method: String = "GET",
    val url: String = "",
    val headersText: String = "",
    val cookiesText: String = "",
    val bodyText: String = "",
    val sending: Boolean = false,
    val response: RepeaterResponse? = null
) {
    companion object {
        /** Formats header/cookie maps into editable "Key: Value" line text. */
        fun formatMapText(mapJson: String): String {
            if (mapJson.isBlank()) return ""
            return try {
                val obj = org.json.JSONObject(mapJson)
                val sb = StringBuilder()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    sb.append(k).append(": ").append(obj.optString(k, "")).append('\n')
                }
                sb.toString().trimEnd()
            } catch (_: Exception) {
                ""
            }
        }
    }
}

data class ExportStatus(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

/** Shared method list for the repeater editor dropdown. */
object RepeaterMethods {
    val ALL = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
}

/** Re-exported for UI imports so api/internal stays decoupled in the UI layer. */
typealias RepeaterRequestModel = com.netforge.inspector.core.model.RepeaterRequest

/** Adapter facade so the ViewModel only depends on api/ surfaces. */
class RepeaterBridge(private val api: RequestRepeaterApi) {
    fun clone(sheet: RepeaterSheetState) = api.cloneForEditing(
        sourcePacketId = sheet.sourcePacketId,
        method = sheet.method,
        url = sheet.url,
        headersJson = sheet.headersText.toMapJson(),
        cookiesJson = sheet.cookiesText.toMapJson(),
        body = sheet.bodyText
    )

    suspend fun send(api2: RepeaterRequestModel) = api.execute(api2)
}

/** Parses "Key: Value\nKey2: Value2" editor text back into a JSON object string. */
fun String.toMapJson(): String {
    if (isBlank()) return "{}"
    val obj = org.json.JSONObject()
    for (rawLine in lines()) {
        val idx = rawLine.indexOf(':')
        if (idx <= 0) continue
        val key = rawLine.substring(0, idx).trim()
        val value = rawLine.substring(idx + 1).trim()
        if (key.isNotBlank()) obj.put(key, value)
    }
    return obj.toString()
}
