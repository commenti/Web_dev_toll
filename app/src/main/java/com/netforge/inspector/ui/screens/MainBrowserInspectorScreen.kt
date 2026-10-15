package com.netforge.inspector.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.netforge.inspector.modules.browser.internal.GeckoBrowserManager
import com.netforge.inspector.ui.components.NetworkConsoleBottomSheet
import com.netforge.inspector.ui.components.RequestRepeaterSheet
import com.netforge.inspector.ui.components.TopSmartUrlBar
import com.netforge.inspector.ui.state.InspectorUiState
import org.mozilla.geckoview.GeckoView

/**
 * File 27 — Main browser + inspector screen.
 *
 * Layout:
 *  - TopSmartUrlBar (Paste & Go smart bar)
 *  - GeckoView browser (standard mobile UA / full Google OAuth compatibility)
 *  - FAB with live capture-count badge opening the Network Console sheet
 *  - Modal sheets: Network Console, Request Repeater
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainBrowserInspectorScreen(
    ui: InspectorUiState,
    geckoView: GeckoView,
    onBrowserAttach: (GeckoView) -> Unit,
    onUrlChanged: (String) -> Unit,
    onUrlSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onOpenConsole: () -> Unit,
    onCloseConsole: () -> Unit,
    onSelectPacket: (String?) -> Unit,
    onRepeat: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onRepeatMethod: (String) -> Unit,
    onRepeatUrl: (String) -> Unit,
    onRepeatHeaders: (String) -> Unit,
    onRepeatCookies: (String) -> Unit,
    onRepeatBody: (String) -> Unit,
    onRepeatSend: () -> Unit,
    onCloseRepeater: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
    ) {
        TopSmartUrlBar(
            urlText = ui.urlBarText,
            progress = ui.pageProgress,
            canGoBack = ui.canGoBack,
            error = ui.urlValidationError,
            onUrlChanged = onUrlChanged,
            onSubmit = { onUrlSubmit(ui.urlBarText) },
            onBack = onBack,
            onReload = onReload
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            // Embedded GeckoView — file 17's engine attaches the session here.
            AndroidView(
                factory = { _ ->
                    onBrowserAttach(geckoView)
                    geckoView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Capture console FAB with live badge.
            FloatingActionButton(
                onClick = onOpenConsole,
                containerColor = Color(0xFF171B24),
                contentColor = Color(0xFF2ECE9E),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
            ) {
                Icon(Icons.Default.NetworkCheck, contentDescription = "Network Console")
                if (ui.captureCount > 0) {
                    Badge(
                        containerColor = Color(0xFF2ECE9E),
                        contentColor = Color(0xFF0B0D12),
                        modifier = Modifier.padding(all = 1.dp)
                    ) {
                        Text(text = "${ui.captureCount}")
                    }
                }
            }
        }
    }

    // Network console sheet
    if (ui.consoleOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onCloseConsole,
            sheetState = sheetState,
            containerColor = Color(0xFF12141A)
        ) {
            NetworkConsoleBottomSheet(
                packets = ui.packets,
                expandedPacket = ui.expandedPacket,
                exportStatus = ui.exportStatus,
                onSelect = onSelectPacket,
                onRepeat = { onRepeat(it) },
                onExport = onExport,
                onImport = onImport,
                onClearAll = onClearAll,
                onDismiss = onCloseConsole
            )
        }
    }

    // Repeater sheet
    if (ui.repeaterSheet.open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onCloseRepeater,
            sheetState = sheetState,
            containerColor = Color(0xFF12141A)
        ) {
            RequestRepeaterSheet(
                state = ui.repeaterSheet,
                onMethodChanged = onRepeatMethod,
                onUrlChanged = onRepeatUrl,
                onHeadersChanged = onRepeatHeaders,
                onCookiesChanged = onRepeatCookies,
                onBodyChanged = onRepeatBody,
                onSend = onRepeatSend,
                onDismiss = onCloseRepeater
            )
        }
    }
}
