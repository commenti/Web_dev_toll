package com.netforge.inspector.modules.storage.internal

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.core.model.ExportResult
import com.netforge.inspector.modules.storage.api.StorageExporterApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * File 21 — MediaStore JSONL manager (Android 15 / API 35 scoped-storage ready).
 *
 * Writes the two canonical export files:
 *  1. network_traffic.jsonl — one full packet JSON object per line.
 *  2. auth_summary.json     — unique endpoints, decoded JWT tokens, cookies.
 * into Downloads/DevInspector_Logs/<domain>_<timestamp>/.
 * Also re-imports .jsonl exports back into the Room console buffer.
 */
class MediaStoreJsonlManager(
    private val context: Context
) : StorageExporterApi {

    override suspend fun exportAll(): ExportResult = withContext(Dispatchers.IO) {
        try {
            val dao = com.netforge.inspector.core.database.InspectorDatabase
                .get(context).networkLogDao()
            val packets = dao.getAllOnce()
            if (packets.isEmpty()) {
                return@withContext ExportResult(
                    success = false,
                    displayName = "",
                    rowsExported = 0,
                    folderPath = "",
                    jsonlUriString = "",
                    summaryUriString = ""
                )
            }

            val folderName = buildFolderName(packets)
            val relativeDir = "$EXPORT_ROOT/$folderName"

            val jsonlUri = createFile(relativeDir, JSONL_FILE, "application/jsonl")
                ?: return@withContext ExportResult(
                    success = false,
                    displayName = folderName,
                    rowsExported = 0,
                    folderPath = relativeDir,
                    jsonlUriString = "",
                    summaryUriString = ""
                )

            val exported = writeJsonl(jsonlUri, packets)

            val summaryUri = createFile(relativeDir, SUMMARY_FILE, "application/json")
                ?: return@withContext ExportResult(
                    success = true,
                    displayName = folderName,
                    rowsExported = exported,
                    folderPath = relativeDir,
                    jsonlUriString = jsonlUri.toString(),
                    summaryUriString = ""
                )

            writeSummary(summaryUri, packets)

            ExportResult(
                success = true,
                displayName = folderName,
                rowsExported = exported,
                folderPath = relativeDir,
                jsonlUriString = jsonlUri.toString(),
                summaryUriString = summaryUri.toString()
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                displayName = "",
                rowsExported = 0,
                folderPath = "error: ${e.message}",
                jsonlUriString = "",
                summaryUriString = ""
            )
        }
    }

    override suspend fun importJsonl(uri: Uri): Int = withContext(Dispatchers.IO) {
        var loaded = 0
        val rows = mutableListOf<NetworkLogEntity>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEachLine
                entityFromJsonlLine(trimmed)?.let {
                    if (it.requestId.isNotBlank()) {
                        rows.add(it.copy(requestId = dedupedRequestId(it.requestId)))
                        loaded++
                    }
                }
            }
        }
        if (rows.isNotEmpty()) {
            com.netforge.inspector.core.database.InspectorDatabase
                .get(context).networkLogDao().insertAll(rows)
        }
        loaded
    }

    // -----------------------------------------------------------------------
    // JSONL schema — must stay byte-compatible with the spec.
    // -----------------------------------------------------------------------

    override fun toJsonlLine(entity: NetworkLogEntity): String {
        val obj = JSONObject()
            .put("requestId", entity.requestId)
            .putOpt("timestamp", entity.timestamp)
            .put("domain", entity.domain)
            .put("method", entity.method)
            .put("url", entity.url)
            .put("isApiCall", entity.isApiCall)

        val requestHeaders = JSONObject(entity.requestHeadersJson.ifBlank { "{}" })
        obj.putOpt("requestHeaders", normalizeJsonObject(requestHeaders, entity.requestHeadersJson))

        val requestCookies = JSONObject(entity.requestCookiesJson.ifBlank { "{}" })
        obj.putOpt("requestCookies", normalizeJsonObject(requestCookies, entity.requestCookiesJson))

        val tokens = JSONArray(entity.extractedTokensJson.ifBlank { "[]" })
        obj.putOpt("extractedTokens", tokens)

        obj.put("requestBody", entity.requestBody)
            .put("statusCode", entity.statusCode)
            .put("durationMs", entity.durationMs)

        val responseHeaders = JSONObject(entity.responseHeadersJson.ifBlank { "{}" })
        obj.putOpt("responseHeaders", normalizeJsonObject(responseHeaders, entity.responseHeadersJson))

        val responseCookies = JSONObject(entity.responseCookiesJson.ifBlank { "{}" })
        obj.putOpt("responseCookies", normalizeJsonObject(responseCookies, entity.responseCookiesJson))

        obj.put("responseBody", entity.responseBody)
        return obj.toString()
    }

    private fun normalizeJsonObject(candidate: JSONObject, raw: String): JSONObject =
        if (raw.isBlank()) JSONObject() else candidate

    private fun entityFromJsonlLine(line: String): NetworkLogEntity? {
        return try {
            val o = JSONObject(line)
            if (o.has("requestId")) {
                NetworkLogEntity(
                    requestId = o.optString("requestId", ""),
                    timestamp = o.optLong("timestamp", 0L),
                    domain = o.optString("domain", ""),
                    method = o.optString("method", "GET"),
                    url = o.optString("url", ""),
                    requestHeadersJson = o.optJSONObject("requestHeaders")?.toString() ?: "{}",
                    requestCookiesJson = o.optJSONObject("requestCookies")?.toString() ?: "{}",
                    extractedTokensJson = o.optJSONArray("extractedTokens")?.toString() ?: "[]",
                    requestBody = o.optString("requestBody", ""),
                    statusCode = o.optInt("statusCode", 0),
                    durationMs = o.optLong("durationMs", 0),
                    responseHeadersJson = o.optJSONObject("responseHeaders")?.toString() ?: "{}",
                    responseCookiesJson = o.optJSONObject("responseCookies")?.toString() ?: "{}",
                    responseBody = o.optString("responseBody", ""),
                    category = "OTHER",
                    isApiCall = o.optBoolean("isApiCall", false),
                    error = ""
                )
            } else if (o.has("rawToken") || o.has("headerName")) {
                // Token-only summary line — attach as an entry row.
                NetworkLogEntity(
                    requestId = "",
                    timestamp = 0L,
                    domain = "",
                    method = "",
                    url = "",
                    requestHeadersJson = "{}",
                    requestCookiesJson = "{}",
                    extractedTokensJson = JSONArray().put(o).toString(),
                    requestBody = "",
                    statusCode = 0,
                    durationMs = 0,
                    responseHeadersJson = "{}",
                    responseCookiesJson = "{}",
                    responseBody = "",
                    isApiCall = false
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dedupedRequestId(original: String): String =
        "$original#imp_${System.currentTimeMillis()}"

    // -----------------------------------------------------------------------
    // auth_summary.json
    // -----------------------------------------------------------------------

    private fun writeSummary(uri: Uri, packets: List<NetworkLogEntity>) {
        val endpoints = JSONArray()
        val seenEndpoints = HashSet<String>()
        val tokens = JSONArray()
        val seenTokens = HashSet<String>()
        val cookies = JSONObject()

        for (p in packets) {
            val endpoint = JSONObject()
                .put("method", p.method)
                .put("url", p.url)
                .put("statusCode", p.statusCode)
                .put("isApiCall", p.isApiCall)
                .put("timestamp", p.timestamp)
            val endpointKey = "${p.method} ${p.url.substringBefore("?")}"
            if (seenEndpoints.add(endpointKey)) {
                endpoints.put(endpoint)
            }

            val tokenArr = JSONArray(p.extractedTokensJson.ifBlank { "[]" })
            for (i in 0 until tokenArr.length()) {
                val t = tokenArr.optJSONObject(i) ?: continue
                val raw = t.optString("rawToken")
                if (raw.isNotBlank() && seenTokens.add(raw)) {
                    tokens.put(t)
                }
            }

            mergeSessionCookies(cookies, p.responseCookiesJson)
            mergeSessionCookies(cookies, p.requestCookiesJson)
        }

        val summary = JSONObject()
            .put("exportedAt", System.currentTimeMillis())
            .put("totalPackets", packets.size)
            .put("endpoints", endpoints)
            .put("extractedTokens", tokens)
            .put("sessionCookies", cookies)

        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                w.write(summary.toString(2))
                w.flush()
            }
        }
    }

    private fun mergeSessionCookies(target: JSONObject, sourceJson: String) {
        if (sourceJson.isBlank()) return
        try {
            val o = JSONObject(sourceJson)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                target.put(k, o.optString(k, ""))
            }
        } catch (_: Exception) {
            // tolerate corrupt rows
        }
    }

    // -----------------------------------------------------------------------
    // MediaStore plumbing
    // -----------------------------------------------------------------------

    private fun createFile(relativeDir: String, fileName: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val uri = resolver.insert(collection, values) ?: return null
        return uri
    }

    private fun writeJsonl(uri: Uri, packets: List<NetworkLogEntity>): Int {
        var count = 0
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                for (p in packets) {
                    w.write(toJsonlLine(p))
                    w.write("\n")
                    count++
                }
                w.flush()
            }
        }
        // Release IS_PENDING once bytes are durable.
        try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } catch (_: Exception) {
            // Older devices ignore IS_PENDING; treat as success.
        }
        return count
    }

    companion object {
        const val EXPORT_ROOT = "Download/DevInspector_Logs"
        const val JSONL_FILE = "network_traffic.jsonl"
        const val SUMMARY_FILE = "auth_summary.json"

        fun buildFolderName(packets: List<NetworkLogEntity>): String {
            val domain = packets.firstOrNull { it.domain.isNotBlank() }
                ?.domain?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.take(48) ?: "mixed_domains"
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date())
            return "${domain}_$stamp"
        }
    }
}
