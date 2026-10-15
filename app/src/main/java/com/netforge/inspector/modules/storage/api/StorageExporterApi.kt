package com.netforge.inspector.modules.storage.api

import android.net.Uri
import com.netforge.inspector.core.database.NetworkLogEntity
import com.netforge.inspector.core.model.ExportResult

/**
 * File 20 — Public contract of the Android 15 storage exporter.
 *
 * MediaStore.Downloads based, scoped-storage compliant, zero legacy permissions.
 * Export layout (created automatically):
 *
 *   Downloads/DevInspector_Logs/<domain>_<yyyy-MM-dd_HH-mm-ss>/
 *     network_traffic.jsonl
 *     auth_summary.json
 */
interface StorageExporterApi {

    /** One-click auto-folder export of all buffered logs. Runs on IO. */
    suspend fun exportAll(): ExportResult

    /**
     * Imports a previously saved .jsonl file (selected via ACTION_OPEN_DOCUMENT)
     * back into the network console buffer. Returns the number of rows loaded.
     */
    suspend fun importJsonl(uri: Uri): Int

    /** Builds the exact JSONL line for one packet (schema-critical, unit tested). */
    fun toJsonlLine(entity: NetworkLogEntity): String
}
