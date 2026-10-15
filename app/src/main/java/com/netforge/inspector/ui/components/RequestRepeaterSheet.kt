package com.netforge.inspector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netforge.inspector.core.model.RepeaterResponse
import com.netforge.inspector.ui.state.RepeaterMethods
import com.netforge.inspector.ui.state.RepeaterSheetState

/**
 * File 26 — Request Editor & Repeater sheet (mobile Burp Repeater workflow).
 *
 * Editable fields: URL, HTTP method, headers (Key: Value lines), cookies,
 * raw body. Native OkHttp execution -> live status/latency/response.
 */
@Composable
fun RequestRepeaterSheet(
    state: RepeaterSheetState,
    onMethodChanged: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onHeadersChanged: (String) -> Unit,
    onCookiesChanged: (String) -> Unit,
    onBodyChanged: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize(0.96f)
            .background(Color(0xFF12141A))
            .padding(12.dp)
    ) {
        // Header row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Request Repeater",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF8AB4F8))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) {
            // Method dropdown
            Box {
                var expanded by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { expanded = true }) {
                    Text(
                        text = state.method,
                        color = Color(0xFF2ECE9E),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Method",
                        tint = Color(0xFF2ECE9E)
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RepeaterMethods.ALL.forEach { method ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = method,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White
                                )
                            },
                            onClick = {
                                expanded = false
                                onMethodChanged(method)
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.url,
                onValueChange = onUrlChanged,
                placeholder = { Text("https://…", color = Color(0xFF6E7686)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = state.headersText,
            onValueChange = onHeadersChanged,
            label = { Text("Headers (Key: Value per line)", color = Color(0xFF6E7686)) },
            textStyle = MaterialTheme.typography.labelSmall.copy(
                color = Color(0xFFC9D1DB),
                fontFamily = FontFamily.Monospace
            ),
            minLines = 3,
            maxLines = 6,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )

        OutlinedTextField(
            value = state.cookiesText,
            onValueChange = onCookiesChanged,
            label = { Text("Session Cookies (Key: Value per line)", color = Color(0xFF6E7686)) },
            textStyle = MaterialTheme.typography.labelSmall.copy(
                color = Color(0xFFC9D1DB),
                fontFamily = FontFamily.Monospace
            ),
            minLines = 2,
            maxLines = 4,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        )

        if (state.method != "GET" && state.method != "HEAD") {
            OutlinedTextField(
                value = state.bodyText,
                onValueChange = onBodyChanged,
                label = { Text("Raw Body", color = Color(0xFF6E7686)) },
                textStyle = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFFC9D1DB),
                    fontFamily = FontFamily.Monospace
                ),
                minLines = 3,
                maxLines = 8,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            )
        }

        Button(
            onClick = onSend,
            enabled = !state.sending && state.url.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            if (state.sending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Color.White,
                    modifier = Modifier.height(18.dp)
                )
                Text("Sending…", modifier = Modifier.padding(start = 8.dp))
            } else {
                Icon(Icons.Default.Send, contentDescription = null)
                Text("Execute Request", modifier = Modifier.padding(start = 6.dp))
            }
        }

        val response = state.response
        if (response != null) {
            ResponseBlock(response = response)
        }
    }
}

@Composable
private fun ResponseBlock(response: RepeaterResponse) {
    Text(
        text = "Response",
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (response.isError) "NETWORK ERROR" else "${response.statusCode} ${response.statusMessage}",
            color = when {
                response.isError -> Color(0xFFFF6B6B)
                response.statusCode in 200..299 -> Color(0xFF2ECE9E)
                response.statusCode in 400..599 -> Color(0xFFFF6B6B)
                else -> Color(0xFFF4BF4F)
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = " · ${response.durationMs} ms",
            color = Color(0xFF6E7686),
            style = MaterialTheme.typography.labelSmall
        )
    }

    if (response.isError) {
        Text(
            text = response.errorMessage ?: "Unknown error",
            color = Color(0xFFFF6B6B),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(Color(0xFF1A1216), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )
    } else {
        Text(
            text = response.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            color = Color(0xFF8AB4F8),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(Color(0xFF161A22), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )
        Text(
            text = response.body.ifBlank { "(empty body)" },
            color = Color(0xFFC9D1DB),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp)
                .background(Color(0xFF161A22), RoundedCornerShape(8.dp))
                .padding(8.dp)
        )
    }
}
