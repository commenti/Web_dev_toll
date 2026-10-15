package com.netforge.inspector.modules.parser.api

import com.netforge.inspector.core.model.AnalyzedPacket
import com.netforge.inspector.core.model.RawCapturedPacket

/**
 * File 12 — Public contract of the Traffic Parser & Auth Extractor layer.
 *
 * Implementations run on Dispatchers.Default and are stateless.
 */
interface TrafficParserApi {

    /**
     * Classifies the packet (REST / GraphQL / XHR / PAGE / OTHER) and extracts
     * JWT + token material from auth headers, plus cookie attributes.
     */
    suspend fun analyze(raw: RawCapturedPacket): AnalyzedPacket

    /** Convenience header check used by the console UI to badge auth-bearing rows. */
    fun hasAuthHeaders(headers: Map<String, String>): Boolean

    companion object {
        /** Header names scanned for bearer/credential material (lowercase). */
        val AUTH_HEADER_NAMES = setOf(
            "authorization",
            "x-access-token",
            "x-api-key",
            "x-auth-token",
            "x-csrf-token",
            "x-xsrf-token",
            "api-key",
            "apikey",
            "token",
        )
    }
}
