package com.netforge.inspector.modules.parser.internal

import com.netforge.inspector.core.model.AnalyzedPacket
import com.netforge.inspector.core.model.CookieInfo
import com.netforge.inspector.core.model.ExtractedToken
import com.netforge.inspector.core.model.JwtPayload
import com.netforge.inspector.core.model.RawCapturedPacket
import com.netforge.inspector.core.model.TrafficCategory
import com.netforge.inspector.modules.parser.api.TrafficParserApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale

/**
 * File 13 — Parser implementation.
 *
 * Three responsibilities, executed on Dispatchers.Default:
 *  1. API Classifier      — REST / GraphQL / XHR-Fetch / Page / Other.
 *  2. JWT & Token Extractor — parses Authorization / x-access-token / custom auth
 *     headers, splits "Header.Payload.Signature", Base64URL-decodes the payload
 *     and its `exp` timestamp.
 *  3. Cookie Jar Parser   — request `Cookie` and response `Set-Cookie` attributes
 *     (HttpOnly, Secure, SameSite, domain, expiry).
 */
class JwtAndCookieParserImpl : TrafficParserApi {

    override suspend fun analyze(raw: RawCapturedPacket): AnalyzedPacket =
        withContext(Dispatchers.Default) {
            val category = classify(raw)
            val tokens = extractTokens(raw)
            AnalyzedPacket(raw = raw, category = category, extractedTokens = tokens)
        }

    override fun hasAuthHeaders(headers: Map<String, String>): Boolean =
        headers.keys.any { key ->
            TrafficParserApi.AUTH_HEADER_NAMES.contains(key.lowercase(Locale.US)) &&
                !headers[key].isNullOrBlank()
        } || headers.keys.any { it.lowercase(Locale.US) == "cookie" && !headers[it].isNullOrBlank() }

    // -----------------------------------------------------------------------
    // 1) API Classifier
    // -----------------------------------------------------------------------

    private fun classify(raw: RawCapturedPacket): TrafficCategory {
        val path = raw.url.substringBefore("?").lowercase(Locale.US)
        val headers = raw.requestHeaders

        val contentTypeReq = headers.entries.firstOrNull {
            it.key.equals("content-type", ignoreCase = true)
        }?.value?.lowercase(Locale.US) ?: ""

        val accept = headers.entries.firstOrNull {
            it.key.equals("accept", ignoreCase = true)
        }?.value?.lowercase(Locale.US) ?: ""

        val pathLooksApi = API_PATH_HINTS.any { path.contains(it) }

        return when {
            // GraphQL: endpoint path or application/graphql content types.
            path.contains("/graphql") ||
                contentTypeReq.contains("application/graphql") ||
                raw.requestBody.trimStart('{').startsWith("\"query\"") -> TrafficCategory.GRAPHQL

            // Document navigation.
            raw.method.equals("GET", ignoreCase = true) &&
                (accept.contains("text/html") || contentTypeReq.contains("text/html")) &&
                !pathLooksApi -> TrafficCategory.PAGE

            // REST/XHR-Fetch: JSON traffic or API-looking paths on non-GET verbs.
            contentTypeReq.contains("application/json") ||
                accept.contains("application/json") ||
                pathLooksApi ||
                raw.isApiCallHint -> TrafficCategory.REST

            else -> TrafficCategory.XHR_FETCH
        }
    }

    // -----------------------------------------------------------------------
    // 2) JWT & Token Extractor
    // -----------------------------------------------------------------------

    private fun extractTokens(raw: RawCapturedPacket): List<ExtractedToken> {
        val tokens = LinkedHashMap<String, ExtractedToken>()

        for ((name, value) in raw.requestHeaders) {
            val lower = name.lowercase(Locale.US)
            if (!TrafficParserApi.AUTH_HEADER_NAMES.contains(lower)) continue
            val trimmed = value.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
                val jwt = trimmed.substring(7).trim()
                val payload = decodeJwt(jwt)
                tokens["jwt:$name"] = if (payload != null) {
                    ExtractedToken(
                        headerName = name,
                        tokenType = ExtractedToken.TYPE_BEARER_JWT,
                        rawToken = jwt,
                        decodedPayload = payload.payloadJson
                    )
                } else {
                    ExtractedToken(
                        headerName = name,
                        tokenType = ExtractedToken.TYPE_BEARER,
                        rawToken = jwt,
                        decodedPayload = ""
                    )
                }
            } else if (trimmed.startsWith("Basic ", ignoreCase = true)) {
                val decoded = runCatching {
                    String(
                        Base64.getDecoder().decode(trimmed.substring(6).trim()),
                        StandardCharsets.UTF_8
                    )
                }.getOrDefault("")
                tokens["basic:$name"] = ExtractedToken(
                    headerName = name,
                    tokenType = ExtractedToken.TYPE_BASIC,
                    rawToken = trimmed.substring(6).trim(),
                    decodedPayload = decoded
                )
            } else {
                // x-api-key / x-access-token / custom credential headers.
                tokens["custom:$name"] = ExtractedToken(
                    headerName = name,
                    tokenType = if (lower == "authorization") ExtractedToken.TYPE_BEARER
                    else ExtractedToken.TYPE_API_KEY,
                    rawToken = trimmed,
                    decodedPayload = ""
                )
            }
        }

        // Auth cookies: session/identity-looking cookie values become tokens too.
        val authCookieNames = setOf("auth", "session", "sess", "sid", "jwt", "token", "access", "refresh", "id")
        raw.requestCookies.forEach { (name, value) ->
            val matches = authCookieNames.any { name.lowercase(Locale.US).contains(it) }
            if (matches && value.isNotBlank()) {
                tokens["cookie:$name"] = ExtractedToken(
                    headerName = "Cookie: $name",
                    tokenType = ExtractedToken.TYPE_CUSTOM,
                    rawToken = value,
                    decodedPayload = ""
                )
            }
        }

        return tokens.values.toList()
    }

    /** Splits "Header.Payload.Signature" and Base64URL-decodes header + payload. */
    fun decodeJwt(token: String): JwtPayload? {
        val parts = token.split(".")
        if (parts.size != 2 && parts.size != 3) return null
        val headerJson = runCatching {
            String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8)
        }.getOrNull()
        val payloadJson = runCatching {
            String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8)
        }.getOrNull()
        if (headerJson == null || payloadJson == null) return null
        val looksJson = payloadJson.trimStart().startsWith("{") &&
            payloadJson.trimEnd().endsWith("}")
        if (!looksJson) return null

        val exp = runCatching {
            val obj = org.json.JSONObject(payloadJson)
            if (obj.has("exp") && !obj.isNull("exp")) obj.getLong("exp") else null
        }.getOrNull()

        return JwtPayload(
            headerJson = headerJson,
            payloadJson = payloadJson,
            signature = parts.getOrNull(2) ?: "",
            expirationEpochSec = exp
        )
    }

    private fun base64UrlDecode(segment: String): ByteArray {
        val normalized = segment.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> normalized + "=="
            3 -> normalized + "="
            else -> normalized
        }
        return Base64.getDecoder().decode(padded)
    }

    companion object {
        private val API_PATH_HINTS = listOf(
            "/api/", "/rest/", "/rpc/", "/service/", "/ajax/", "/_api/",
            "/v1/", "/v2/", "/v3/", "/v4/", "/graphql", "/gateway/", "/json/"
        )

        /**
         * Serializes AnalyzedPacket.token list into the JSONL schema shape:
         * [{"headerName","tokenType","rawToken","decodedPayload"}].
         */
        fun tokensToJson(tokens: List<ExtractedToken>): String {
            val arr = JSONArray()
            for (t in tokens) {
                arr.put(
                    org.json.JSONObject()
                        .put("headerName", t.headerName)
                        .put("tokenType", t.tokenType)
                        .put("rawToken", t.rawToken)
                        .put("decodedPayload", t.decodedPayload)
                )
            }
            return arr.toString()
        }

        /** Serializes Set-Cookie attribute parsing into a full CookieInfo JSON string. */
        fun parseSetCookie(headerValue: String): CookieInfo {
            val segments = headerValue.split(";")
            val first = segments.firstOrNull().orEmpty()
            val eq = first.indexOf('=')
            val name = if (eq > 0) first.substring(0, eq).trim() else first.trim()
            val value = if (eq > 0) first.substring(eq + 1).trim() else ""
            var domain: String? = null
            var path: String? = null
            var httpOnly = false
            var secure = false
            var sameSite: String? = null
            var expires: String? = null
            segments.drop(1).forEach { attr ->
                val a = attr.trim()
                val lower = a.lowercase(Locale.US)
                when {
                    lower.startsWith("domain=") -> domain = a.substring(7).trim()
                    lower.startsWith("path=") -> path = a.substring(5).trim()
                    lower == "httponly" -> httpOnly = true
                    lower == "secure" -> secure = true
                    lower.startsWith("samesite=") -> sameSite = a.substring(9).trim()
                    lower.startsWith("expires=") -> expires = a.substring(8).trim()
                }
            }
            return CookieInfo(name, value, domain, path, httpOnly, secure, sameSite, expires)
        }
    }
}
