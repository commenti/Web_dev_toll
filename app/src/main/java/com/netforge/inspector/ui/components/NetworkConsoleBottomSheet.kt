package com.netforge.inspector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.ui.state.ExportStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * File 25 — Network Console bottom sheet.
 *
 * Lists every buffered packet (Room Flow) with method + status color badges,
 * tap-to-expand full detail (headers, tokens, cookies, bodies), and the
 * one-click auto-folder export / JSONL import actions in the sheet header.
 */
@Composable
fun NetworkConsoleBottomSheet(
    packets: List<NetworkLogEntity>,
    expandedPacket: NetworkLogEntity?,
    exportStatus: ExportStatus,
    onSelect: (String?) -> Unit,
    onRepeat: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize(0.92f)
            .background(Color(0xFF12141A))
    ) {
        ConsoleHeader(
            exportStatus = exportStatus,
            expanded = expandedPacket != null,
            onBack = { onSelect(null) },
            onExport = onExport,
            onImport = onImport,
            onClearAll = onClearAll,
            onClose = onDismiss
        )

        if (expandedPacket != null) {
            PacketDetail(
                packet = expandedPacket,
                onRepeat = { onRepeat(expandedPacket.requestId) },
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        } else {
            PacketList(
                packets = packets,
                onSelect = onSelect,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun ConsoleHeader(
    exportStatus: ExportStatus,
    expanded: Boolean,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171B24))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        if (expanded) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to list",
                    tint = Color.White
                )
            }
        } else {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Close console",
                    tint = Color.White
                )
            }
        }
        Text(
            text = "Network Console",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 2.dp)
        )
        Box(Modifier.weight(1f))
        IconButton(onClick = onImport, enabled = !exportStatus.busy) {
            Icon(
                imageVector = Icons.Default.FileUpload,
                contentDescription = "Import JSONL",
                tint = Color(0xFF8AB4F8)
            )
        }
        IconButton(onClick = onClearAll, enabled = !exportStatus.busy) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Clear logs",
                tint = Color(0xFFFF6B6B)
            )
        }
        IconButton(onClick = onExport, enabled = !exportStatus.busy) {
            Icon(
                imageVector = Icons.Default.FileDownload,
                contentDescription = "Export JSONL + auth summary",
                tint = Color(0xFF2ECE9E)
            )
        }
    }

    exportStatus.message?.let { msg ->
        Text(
            text = msg,
            color = if (exportStatus.isError) Color(0xFFFF6B6B) else Color(0xFF2ECE9E),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PacketList(
    packets: List<NetworkLogEntity>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (packets.isEmpty()) {
            Text(
                text = "No traffic captured yet.\nBrowse any site — every request will appear here.",
                color = Color(0xFF6E7686),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(20.dp)
            )
        }
        packets.forEach { packet ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelect(packet.requestId)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                MethodBadge(method = packet.method, isApi = packet.isApiCall)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = packet.url,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = packet.domain.ifBlank { "??" } +
                            " · ${packet.durationMs} ms" +
                            if (packet.extractedTokensJson != "[]") " · 🔑" else "",
                        color = Color(0xFF6E7686),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                StatusBadge(status = packet.statusCode)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFF1D222C))
            )
        }
    }
}

@Composable
private fun MethodBadge(method: String, isApi: Boolean) {
    val color = when {
        method.equals("GET", ignoreCase = true) -> Color(0xFF8AB4F8)
        method.equals("POST", ignoreCase = true) -> Color(0xFF2ECE9E)
        method.equals("DELETE", ignoreCase = true) -> Color(0xFFFF6B6B)
        else -> Color(0xFFF4BF4F)
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isApi && method.length <= 4) "$method·API" else method,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun StatusBadge(status: Int) {
    val color = when {
        status in 200..299 -> Color(0xFF2ECE9E)
        status in 300..399 -> Color(0xFFF4BF4F)
        status in 400..599 -> Color(0xFFFF6B6B)
        else -> Color(0xFF6E7686)
    }
    Text(
        text = if (status == 0) "ERR" else status.toString(),
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 8.dp)
    )
}

@Composable
private fun PacketDetail(
    packet: NetworkLogEntity,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "${packet.method} ${packet.url}",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "${packet.domain} · ${packet.statusCode} · ${packet.durationMs} ms",
            color = Color(0xFF6E7686),
            style = MaterialTheme.typography.labelSmall
        )

        Button(onClick = onRepeat, modifier = Modifier.width(200.dp)) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Open in Repeater", modifier = Modifier.padding(start = 6.dp))
        }

        Section("Request Headers") {
            KeyValues(jsonText = packet.requestHeadersJson)
        }
        Section("Request Cookies") {
            KeyValues(jsonText = packet.requestCookiesJson)
        }
        TokensSection(tokensJson = packet.extractedTokensJson)
        if (packet.requestBody.isNotBlank()) {
            Section("Request Body") {
                MonoBlock(text = packet.requestBody)
            }
        }
        Section("Response Headers") {
            KeyValues(jsonText = packet.responseHeadersJson)
        }
        Section("Response Cookies") {
            KeyValues(jsonText = packet.responseCookiesJson)
        }
        if (packet.responseBody.isNotBlank()) {
            Section("Response Body") {
                MonoBlock(text = packet.responseBody.take(12_000))
            }
        }
        if (packet.error.isNotBlank()) {
            Section("Error") {
                MonoBlock(text = packet.error)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            color = Color(0xFF8AB4F8),
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        content()
    }
}

@Composable
private fun TokensSection(tokensJson: String) {
    val tokens = JSONArray(tokensJson.ifBlank { "[]" })
    if (tokens.length() == 0) return
    Section("Extracted Tokens (${tokens.length()})") {
        Column {
            for (i in 0 until tokens.length()) {
                val t = tokens.optJSONObject(i) ?: continue
                MonoBlock(
                    text = buildString {
                        append("[").append(t.optString("tokenType")).append("] ")
                        append(t.optString("headerName")).append('\n')
                        append("raw: ").append(t.optString("rawToken")).append('\n')
                        val payload = t.optString("decodedPayload")
                        if (payload.isNotBlank()) append("payload: ").append(payload)
                    }
                )
            }
        }
    }
}

@Composable
private fun KeyValues(jsonText: String) {
    val obj = runCatching { JSONObject(jsonText) }.getOrDefault(JSONObject())
    if (obj.length() == 0) {
        Text(text = "—", color = Color(0xFF555D6D), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161A22), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text(
            text = obj.toString(2),
            color = Color(0xFFC9D1DB),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun MonoBlock(text: String) {
    Text(
        text = text,
        color = Color(0xFFC9D1DB),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161A22), RoundedCornerShape(8.dp))
            .padding(8.dp)
    )
}
