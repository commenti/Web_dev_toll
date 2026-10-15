package com.netforge.inspector.modules.repeater.internal

import com.netforge.inspector.core.model.RepeaterRequest
import com.netforge.inspector.core.model.RepeaterResponse
import com.netforge.inspector.modules.repeater.api.RequestRepeaterApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * File 19 — Native Repeater engine (mobile Burp Suite Repeater / Postman style).
 *
 * The OkHttpClient executes the request outside of the browser sandbox, so CORS
 * does not apply. Cookies supplied by the editor are merged into the Cookie
 * header so session-bound API testing matches the captured context.
 */
class OkHttpRepeaterEngine : RequestRepeaterApi {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    override suspend fun execute(request: RepeaterRequest): RepeaterResponse =
        withContext(Dispatchers.IO) {
            try {
                val call = client.newCall(buildCall(request))
                val started = System.nanoTime()
                val response = executeCall(call)
                val durationMs = (System.nanoTime() - started) / 1_000_000

                val headerMap = LinkedHashMap<String, String>()
                for (name in response.headers.names()) {
                    headerMap[name] = response.header(name).orEmpty()
                }

                val bodySnippet = response.body?.use { body ->
                    val bytes = body.bytes()
                    String(
                        bytes,
                        0,
                        minOf(bytes.size, MAX_RESPONSE_BYTES),
                        Charsets.UTF_8
                    )
                }.orEmpty()

                RepeaterResponse(
                    statusCode = response.code,
                    statusMessage = response.message,
                    headers = headerMap,
                    body = bodySnippet,
                    durationMs = durationMs,
                    isError = false,
                    errorMessage = null
                )
            } catch (e: Exception) {
                RepeaterResponse(
                    statusCode = 0,
                    statusMessage = "Failed",
                    headers = emptyMap(),
                    body = "",
                    durationMs = 0,
                    isError = true,
                    errorMessage = e.message ?: e.javaClass.simpleName
                )
            }
        }

    private fun executeCall(call: Call): Response = call.execute()

    internal fun buildCall(request: RepeaterRequest): Request {
        val builder = Request.Builder().url(request.url)

        for ((name, value) in request.headers) {
            if (HOP_BY_HOP.contains(name.lowercase())) continue
            builder.header(name, value)
        }

        // Sync session cookies into the Cookie header.
        if (request.cookies.isNotEmpty()) {
            val cookieHeader = request.cookies.entries.joinToString("; ") { (k, v) ->
                "$k=$v"
            }
            builder.header("Cookie", cookieHeader)
        }

        val method = request.method.uppercase()
        when (method) {
            "GET", "HEAD", "OPTIONS" -> builder.method(method, null)
            "POST", "PUT", "PATCH", "DELETE" -> {
                val contentType = request.headers.entries.firstOrNull {
                    it.key.equals("content-type", ignoreCase = true)
                }?.value ?: "application/json"
                val mediaType = contentType.substringBefore(";").trim()
                    .toMediaTypeOrNull()
                builder.method(method, request.body.toRequestBody(mediaType))
            }
            else -> builder.method(method, request.body.toRequestBody(null))
        }
        return builder.build()
    }

    override fun cloneForEditing(
        sourcePacketId: String?,
        method: String,
        url: String,
        headersJson: String,
        cookiesJson: String,
        body: String
    ): RepeaterRequest {
        val headers = jsonToObject(headersJson)
        val cookies = jsonToObject(cookiesJson)
        return RepeaterRequest(
            sourcePacketId = sourcePacketId,
            method = method.uppercase(),
            url = url,
            headers = headers,
            cookies = cookies,
            body = body
        )
    }

    private fun jsonToObject(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.optString(key, "")
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 512 * 1024

        private val HOP_BY_HOP = setOf(
            "host", "content-length", "connection", "accept-encoding",
            "transfer-encoding", "upgrade", "keep-alive"
        )
    }
}
