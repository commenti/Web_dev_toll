package com.netforge.inspector.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netforge.inspector.core.database.InspectorDatabase
import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.core.model.ExportResult
import com.netforge.inspector.core.model.RepeaterResponse
import com.netforge.inspector.modules.interceptor.api.NetworkInterceptorApi
import com.netforge.inspector.modules.storage.api.StorageExporterApi
import com.netforge.inspector.ui.state.ExportStatus
import com.netforge.inspector.ui.state.InspectorUiState
import com.netforge.inspector.ui.state.RepeaterSheetState
import com.netforge.inspector.ui.state.toMapJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * File 23 — Central ViewModel. Owns the console state and orchestrates the
 * decoupled module "api/" surfaces only.
 */
class InspectorViewModel(
    app: Application,
    private val interceptor: NetworkInterceptorApi,
    private val repeaterApi: com.netforge.inspector.modules.repeater.api.RequestRepeaterApi,
    private val storage: StorageExporterApi
) : AndroidViewModel(app) {

    private val database = InspectorDatabase.get(app)

    private val _ui = MutableStateFlow(InspectorUiState())
    val ui: StateFlow<InspectorUiState> = _ui.asStateFlow()

    /** MainActivity observes this to launch the ACTION_OPEN_DOCUMENT picker. */
    val pendingJsonlPickRequest = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            database.networkLogDao().observeAll().collect { rows ->
                _ui.update {
                    it.copy(
                        packets = rows,
                        expandedPacket = it.selectedPacketId?.let { id ->
                            rows.firstOrNull { r -> r.requestId == id }
                        }
                    )
                }
            }
        }
        interceptor.setListener(object : NetworkInterceptorApi.Listener {
            override fun onPacketLogged(entity: NetworkLogEntity) {
                // Room flow above already pushes rows into the state.
            }

            override fun onCaptureCountChanged(count: Int) {
                _ui.update { it.copy(captureCount = count) }
            }
        })
    }

    fun onUrlBarChanged(text: String) {
        _ui.update { it.copy(urlBarText = text, urlValidationError = null) }
    }

    fun onUrlSubmitted(): String? {
        val url = _ui.value.urlBarText.trim()
        if (url.isEmpty()) {
            _ui.update { it.copy(urlValidationError = "Enter a URL to load") }
            return null
        }
        return url
    }

    fun onPageLoading(progress: Int) {
        _ui.update { it.copy(pageProgress = progress) }
    }

    fun onBrowserUrlChanged(url: String) {
        _ui.update { it.copy(urlBarText = url) }
    }

    fun onHistoryChanged(canGoBack: Boolean) {
        _ui.update { it.copy(canGoBack = canGoBack) }
    }

    fun openConsole() {
        _ui.update { it.copy(consoleOpen = true) }
    }

    fun closeConsole() {
        _ui.update { it.copy(consoleOpen = false, selectedPacketId = null) }
    }

    fun selectPacket(packetId: String?) {
        _ui.update {
            it.copy(
                selectedPacketId = packetId,
                expandedPacket = packetId?.let { id ->
                    it.packets.firstOrNull { r -> r.requestId == id }
                }
            )
        }
    }

    fun clearLogs() {
        viewModelScope.launch { interceptor.clearLogs() }
    }

    // ------------------------------------------------------------------
    // JSONL import
    // ------------------------------------------------------------------

    fun requestJsonlImport() {
        pendingJsonlPickRequest.value = true
    }

    fun consumeJsonlPickRequest() {
        pendingJsonlPickRequest.value = false
    }

    fun onImportIntent(data: Intent?) {
        val uri = data?.data ?: return
        importFromUri(uri)
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _ui.update {
                it.copy(exportStatus = ExportStatus(busy = true, message = "Importing JSONL…"))
            }
            val loaded = storage.importJsonl(uri)
            _ui.update {
                it.copy(
                    exportStatus = ExportStatus(
                        message = "Imported $loaded packets into the console buffer.",
                        isError = false
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    fun exportLogs() {
        viewModelScope.launch {
            val nothing = _ui.value.captureCount == 0
            if (nothing) {
                _ui.update {
                    it.copy(
                        exportStatus = ExportStatus(
                            message = "Nothing captured yet — browse some traffic first.",
                            isError = true
                        )
                    )
                }
                return@launch
            }
            _ui.update {
                it.copy(
                    exportStatus = ExportStatus(
                        busy = true,
                        message = "Exporting to DevInspector_Logs…"
                    )
                )
            }
            val result: ExportResult = storage.exportAll()
            val message = if (result.success) {
                "Saved to ${result.folderPath} (${result.rowsExported} packets)."
            } else {
                "Export failed: ${result.folderPath.ifBlank { "unknown error" }}"
            }
            _ui.update {
                it.copy(
                    exportStatus = ExportStatus(
                        busy = false,
                        message = message,
                        isError = !result.success
                    )
                )
            }
        }
    }

    fun dismissExportStatus() {
        _ui.update { it.copy(exportStatus = ExportStatus()) }
    }

    // ------------------------------------------------------------------
    // Repeater
    // ------------------------------------------------------------------

    fun openRepeater(packetId: String) {
        val packet = _ui.value.packets.firstOrNull { it.requestId == packetId } ?: return
        val sheet = RepeaterSheetState(
            open = true,
            sourcePacketId = packet.requestId,
            method = packet.method,
            url = packet.url,
            headersText = RepeaterSheetState.formatMapText(packet.requestHeadersJson),
            cookiesText = RepeaterSheetState.formatMapText(packet.requestCookiesJson),
            bodyText = packet.requestBody,
            response = null
        )
        _ui.update {
            it.copy(
                repeaterSheet = sheet,
                consoleOpen = false,
                selectedPacketId = packetId
            )
        }
    }

    fun updateRepeater(transform: (RepeaterSheetState) -> RepeaterSheetState) {
        _ui.update { it.copy(repeaterSheet = transform(it.repeaterSheet)) }
    }

    fun closeRepeater() {
        _ui.update { it.copy(repeaterSheet = RepeaterSheetState()) }
    }

    fun sendRepeaterRequest() {
        val sheet = _ui.value.repeaterSheet
        if (sheet.url.isBlank() || sheet.sending) return
        viewModelScope.launch {
            _ui.update {
                it.copy(repeaterSheet = sheet.copy(sending = true, response = null))
            }
            val request = repeaterApi.cloneForEditing(
                sourcePacketId = sheet.sourcePacketId,
                method = sheet.method,
                url = sheet.url,
                headersJson = sheet.headersText.toMapJson(),
                cookiesJson = sheet.cookiesText.toMapJson(),
                body = sheet.bodyText
            )
            val response: RepeaterResponse = repeaterApi.execute(request)
            _ui.update {
                it.copy(
                    repeaterSheet = it.repeaterSheet.copy(sending = false, response = response)
                )
            }
        }
    }
}
