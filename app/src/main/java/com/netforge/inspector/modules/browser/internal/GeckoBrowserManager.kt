package com.netforge.inspector.modules.browser.internal

import android.content.Context
import android.net.Uri
import com.netforge.inspector.modules.browser.api.BrowserEngineApi
import com.netforge.inspector.modules.interceptor.internal.GeckoExtensionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * File 17 — GeckoView engine manager.
 *
 * Creates and owns:
 *  - the single GeckoRuntime (standard mobile UA defaults),
 *  - the primary GeckoSession with history + progress + URL callbacks,
 *  - popup/OAuth-window sessions sharing the cookie store,
 * and loads the built-in capture WebExtension, registering it with [GeckoExtensionBridge].
 */
class GeckoBrowserManager(
    private val context: Context,
    private val extensionBridge: GeckoExtensionBridge?,
    private val callbacks: Callbacks
) : BrowserEngineApi {

    interface Callbacks {
        fun onUrlChanged(url: String)
        fun onProgress(progress: Int)
        fun onHistoryStateChanged(canGoBack: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var attachedView: GeckoView? = null
    private var trackedCanGoBack = false

    private val runtimeSettings: GeckoRuntimeSettings by lazy {
        GeckoRuntimeSettings.Builder()
            .debugLogging(false)
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            // Standard mobile UA flows naturally; no disguised/disallowed UA is
            // forced, which keeps Google's OAuth endpoints happy.
            .build()
    }

    private fun ensureRuntime(): GeckoRuntime {
        val existing = runtime
        if (existing != null) return existing
        val created = GeckoRuntime.create(context, runtimeSettings)
        runtime = created
        return created
    }

    /** Opens the primary session and completes once the capture extension is live. */
    fun start() {
        if (session != null) return
        val rt = ensureRuntime()
        val primary = GeckoSession()
        primary.open(rt)
        installSessionDelegates(primary)
        session = primary
        attachedView?.setSession(primary)

        // Load the built-in WebExtension from app assets: inspector_ext.
        // GV-128 signature: ensureBuiltIn(location, extensionId).
        rt.webExtensionController
            .ensureBuiltIn(
                "resource://android/assets/inspector_ext/",
                "inspector_ext@netforge.inspector"
            )
            .accept { ext: WebExtension? ->
                if (ext != null) {
                    extensionBridge?.hook(ext)
                }
            }
    }

    private fun installSessionDelegates(target: GeckoSession) {
        target.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                callbacks.onProgress(progress)
            }

            override fun onPageStart(session: GeckoSession, url: String) {
                callbacks.onProgress(25)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                callbacks.onProgress(100)
            }
        }

        target.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url != null) callbacks.onUrlChanged(url)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                trackedCanGoBack = canGoBack
                callbacks.onHistoryStateChanged(canGoBack)
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                // OAuth popups / multi-window sign-in: run inside the same
                // cookie store by swapping the display to a fresh open session.
                val popup = GeckoSession()
                popup.open(ensureRuntime())
                installSessionDelegates(popup)
                this@GeckoBrowserManager.session = popup
                attachedView?.setSession(popup)
                return GeckoResult.fromValue(popup)
            }
        }
    }

    override fun attachView(view: Any?) {
        if (view is GeckoView) {
            attachedView = view
            session?.let { current ->
                view.setSession(current)
            }
        }
    }

    override fun loadUrl(url: String) {
        val normalized = normalize(url)
        start()
        session?.loadUri(normalized)
    }

    override fun loadUrlPasted(text: String) {
        val clipboard = text.trim()
        if (clipboard.isEmpty()) return
        loadUrl(clipboard)
    }

    override fun stop() {
        session?.stop()
    }

    override fun reload() {
        session?.reload()
    }

    override fun canGoBack(): Boolean = trackedCanGoBack

    override fun goBack() {
        if (trackedCanGoBack) {
            session?.goBack()
        }
    }

    override fun isSessionOpen(): Boolean = session != null

    override fun shutdown() {
        session?.close()
        session = null
        attachedView = null
        runtime?.let { rt ->
            try {
                rt.shutdown()
            } catch (_: Exception) {
                // GeckoRuntime.shutdown may throw if called twice.
            }
        }
        runtime = null
        scope.cancel()
    }

    companion object {
        /**
         * Paste & Go normalization: bare hosts get https://, search strings are
         * routed to a search engine.
         */
        fun normalize(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return trimmed
            val hasScheme = trimmed.contains("://")
            if (hasScheme) return trimmed
            val looksLikeHost = trimmed.contains(".") &&
                !trimmed.contains(" ") &&
                Uri.parse(trimmed).scheme == null
            return if (looksLikeHost) "https://$trimmed" else
                "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
        }
    }
}

/** Display holder so the Compose AndroidView factory stays engine-agnostic. */
class GeckoViewHost {
    val view: GeckoView? = null
}
