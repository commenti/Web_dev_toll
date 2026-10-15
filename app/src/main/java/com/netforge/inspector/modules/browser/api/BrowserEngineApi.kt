package com.netforge.inspector.modules.browser.api

/**
 * File 16 — Public contract of the GeckoView browser engine & OAuth session layer.
 *
 * Guarantees provided by the implementation:
 *  - Standard mobile browser User-Agent (Google Sign-In works, no "403: disallowed_useragent").
 *  - Multi-window OAuth popups handled inside the same cookie session.
 *  - Instant "Paste & Go" URL loading.
 */
interface BrowserEngineApi {

    /** Attaches the engine's display view to the given GeckoView. */
    fun attachView(view: Any?)

    /** Loads the url with the standard mobile session (Paste & Go entry point). */
    fun loadUrl(url: String)

    /** Loads the current search/clipboard content immediately. */
    fun loadUrlPasted(text: String)

    /** Stops the current session. */
    fun stop()

    /** Reloads the current page. */
    fun reload()

    /** Navigates back if possible. Returns false when there is no history. */
    fun canGoBack(): Boolean

    fun goBack()

    /** Whether the primary session is open and ready. */
    fun isSessionOpen(): Boolean

    /** Releases GeckoRuntime resources; call from onDestroy. */
    fun shutdown()
}
