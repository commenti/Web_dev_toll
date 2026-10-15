package com.netforge.inspector.core.model

/**
 * File 08 — Core data models shared by every feature module.
 *
 * The packet model mirrors the JSONL export schema exactly so export/import
 * round-trips are lossless.
 */

/** Traffic category assigned by the parser's API classifier. */
enum class TrafficCategory {
    REST,
    GRAPHQL,
    XHR_FETCH,
    PAGE,
    OTHER;

    companion object {
        fun fromJson(value: String?): TrafficCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}

/** A token extracted from an auth header (or cookie) by the parser layer. */
data class ExtractedToken(
    val headerName: String,
    val tokenType: String,
    val rawToken: String,
    val decodedPayload: String
) {
    companion object {
        const val TYPE_BEARER_JWT = "Bearer_JWT"
        const val TYPE_BEARER = "Bearer"
        const val TYPE_BASIC = "Basic"
        const val TYPE_API_KEY = "ApiKey"
        const val TYPE_CUSTOM = "Custom_Auth"
    }
}

/** Cookie attributes parsed from a Set-Cookie / Cookie header. */
data class CookieInfo(
    val name: String,
    val value: String,
    val domain: String? = null,
    val path: String? = null,
    val httpOnly: Boolean = false,
    val secure: Boolean = false,
    val sameSite: String? = null,
    val expires: String? = null
)

/** Raw packet as it arrives from the GeckoView WebExtension bridge. */
data class RawCapturedPacket(
    val requestId: String,
    val requestTimestamp: Long,
    val domain: String,
    val method: String,
    val url: String,
    val isApiCallHint: Boolean,
    val requestHeaders: Map<String, String>,
    val requestCookies: Map<String, String>,
    val requestBody: String,
    val statusCode: Int,
    val durationMs: Long,
    val responseHeaders: Map<String, String>,
    val responseCookies: Map<String, String>,
    val responseBody: String,
    val error: String
)

/** Enriched packet produced by the parser layer and stored in Room. */
data class AnalyzedPacket(
    val raw: RawCapturedPacket,
    val category: TrafficCategory,
    val extractedTokens: List<ExtractedToken>
)

/** Parsed body of a JWT token (header.payload.signature). */
data class JwtPayload(
    val headerJson: String,
    val payloadJson: String,
    val signature: String,
    val expirationEpochSec: Long?
)

/** Editable request model cloned by the Repeater editor. */
data class RepeaterRequest(
    val sourcePacketId: String?,
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val cookies: Map<String, String>,
    val body: String
)

/** Result of a native OkHttp repeater execution. */
data class RepeaterResponse(
    val statusCode: Int,
    val statusMessage: String,
    val headers: Map<String, String>,
    val body: String,
    val durationMs: Long,
    val isError: Boolean,
    val errorMessage: String?
)

/** Result of the one-click MediaStore export. */
data class ExportResult(
    val success: Boolean,
    val displayName: String,
    val rowsExported: Int,
    val folderPath: String,
    val jsonlUriString: String,
    val summaryUriString: String
)
