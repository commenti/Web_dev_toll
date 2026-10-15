package com.netforge.inspector.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.netforge.inspector.core.model.TrafficCategory

/**
 * File 09 — Room entity buffering captured traffic so the Compose UI never
 * reads in-flight capture structures directly (keeps 1000+ request sessions smooth).
 */
@Entity(tableName = "network_logs", indices = [androidx.room.Index("timestamp")])
data class NetworkLogEntity(
    /** Extension packet id, e.g. "req_101" — stable key for response upserts. */
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "domain")
    val domain: String,

    @ColumnInfo(name = "method")
    val method: String,

    @ColumnInfo(name = "url")
    val url: String,

    /** JSON object string: {"Content-Type": "application/json", ...} */
    @ColumnInfo(name = "request_headers_json")
    val requestHeadersJson: String,

    /** JSON object string: {"session_id": "abc123", ...} */
    @ColumnInfo(name = "request_cookies_json")
    val requestCookiesJson: String,

    /** JSON array string of ExtractedToken objects. */
    @ColumnInfo(name = "extracted_tokens_json")
    val extractedTokensJson: String,

    @ColumnInfo(name = "request_body")
    val requestBody: String,

    @ColumnInfo(name = "status_code")
    val statusCode: Int,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "response_headers_json")
    val responseHeadersJson: String,

    @ColumnInfo(name = "response_cookies_json")
    val responseCookiesJson: String,

    @ColumnInfo(name = "response_body")
    val responseBody: String,

    @ColumnInfo(name = "category")
    val category: String = TrafficCategory.OTHER.name,

    @ColumnInfo(name = "is_api_call")
    val isApiCall: Boolean = false,

    @ColumnInfo(name = "error")
    val error: String = ""
) {
    companion object {
        /** Synthetic ids used for manually-cloned Repeater requests. */
        fun localId(): String = "local_" + System.currentTimeMillis()
    }
}
