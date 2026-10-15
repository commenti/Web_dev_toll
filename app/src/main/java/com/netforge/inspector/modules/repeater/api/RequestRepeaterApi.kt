package com.netforge.inspector.modules.repeater.api

import com.netforge.inspector.core.model.RepeaterRequest
import com.netforge.inspector.core.model.RepeaterResponse

/**
 * File 18 — Public contract of the request editor & repeater engine.
 *
 * Executes edited requests natively with OkHttpClient (CORS-free), syncing the
 * browser-session cookies supplied by the editor.
 */
interface RequestRepeaterApi {

    /**
     * Sends the modified request directly from Android native HTTP.
     * Runs on IO dispatcher; never throws — network failures are returned in
     * [RepeaterResponse.isError].
     */
    suspend fun execute(request: RepeaterRequest): RepeaterResponse

    /** Convenience: clones a captured packet into a repeatable editor model. */
    fun cloneForEditing(
        sourcePacketId: String?,
        method: String,
        url: String,
        headersJson: String,
        cookiesJson: String,
        body: String
    ): RepeaterRequest
}
