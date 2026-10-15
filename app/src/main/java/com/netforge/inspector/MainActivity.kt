package com.netforge.inspector

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netforge.inspector.core.database.InspectorDatabase
import com.netforge.inspector.modules.browser.internal.GeckoBrowserManager
import com.netforge.inspector.modules.interceptor.internal.GeckoExtensionBridge
import com.netforge.inspector.modules.parser.internal.JwtAndCookieParserImpl
import com.netforge.inspector.modules.repeater.internal.OkHttpRepeaterEngine
import com.netforge.inspector.modules.storage.internal.MediaStoreJsonlManager
import com.netforge.inspector.ui.screens.MainBrowserInspectorScreen
import com.netforge.inspector.ui.viewmodel.InspectorViewModel
import org.mozilla.geckoview.GeckoView

/**
 * File 28 — App entry point & module wiring.
 *
 * Wires the five decoupled layers together:
 *  1. Browser engine & OAuth session  — GeckoBrowserManager
 *  2. Network interception            — GeckoExtensionBridge (WebExtension port)
 *  3. Parser & auth extractor         — JwtAndCookieParserImpl
 *  4. Repeater engine                 — OkHttpRepeaterEngine
 *  5. Room buffer + storage exporter  — InspectorDatabase / MediaStoreJsonlManager
 */
class MainActivity : ComponentActivity() {

    private var geckoView: GeckoView? = null
    private var browserManager: GeckoBrowserManager? = null
    private var viewModel: InspectorViewModel? = null

    /** System JSONL file picker (Storage Access Framework, no permissions needed). */
    private val jsonlPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel?.importFromUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.ensureInitialized(applicationContext)

        setContent {
            val vm: InspectorViewModel = viewModel(factory = ServiceLocator.requireVmFactory())
            val ui by vm.ui.collectAsState()
            viewModel = vm

            // Consume the console's "Import JSONL" action → launch the picker.
            val importRequested by vm.pendingJsonlPickRequest.collectAsState()
            LaunchedEffect(importRequested) {
                if (importRequested) {
                    vm.consumeJsonlPickRequest()
                    jsonlPicker.launch(arrayOf("*/*"))
                }
            }

            MainBrowserInspectorScreen(
                ui = ui,
                geckoView = remember { GeckoViewHolder.create(applicationContext) }.also {
                    geckoView = it
                },
                onUrlChanged = vm::onUrlBarChanged,
                onUrlSubmit = { url ->
                    vm.onUrlSubmitted()?.let { normalizable ->
                        browserManager?.loadUrl(normalizable)
                    }
                },
                onBack = { browserManager?.goBack() },
                onReload = { browserManager?.reload() },
                onOpenConsole = vm::openConsole,
                onCloseConsole = vm::closeConsole,
                onSelectPacket = vm::selectPacket,
                onRepeat = vm::openRepeater,
                onExport = vm::exportLogs,
                onImport = vm::requestJsonlImport,                onClearAll = vm::clearLogs,
                onRepeatMethod = { m -> vm.updateRepeater { it.copy(method = m) } },
                onRepeatUrl = { u -> vm.updateRepeater { it.copy(url = u) } },
                onRepeatHeaders = { h -> vm.updateRepeater { it.copy(headersText = h) } },
                onRepeatCookies = { c -> vm.updateRepeater { it.copy(cookiesText = c) } },
                onRepeatBody = { b -> vm.updateRepeater { it.copy(bodyText = b) } },
                onBrowserAttach = { gv ->
                    if (browserManager == null) prepareEngine()
                    browserManager?.attachView(gv)
                },
                onRepeatSend = vm::sendRepeaterRequest,
                onCloseRepeater = vm::closeRepeater
            )
        }
    }

    override fun onResume() {
        super.onResume()
        prepareEngine()
    }

    private fun prepareEngine() {
        if (browserManager != null) return
        browserManager = GeckoBrowserManager(
            context = this,
            extensionBridge = ServiceLocator.extensionBridge,
            callbacks = object : GeckoBrowserManager.Callbacks {
                override fun onUrlChanged(url: String) {
                    ServiceLocator.vmProxy?.onBrowserUrlChanged(url)
                }

                override fun onProgress(progress: Int) {
                    ServiceLocator.vmProxy?.onPageLoading(progress)
                }

                override fun onHistoryStateChanged(canGoBack: Boolean) {
                    ServiceLocator.vmProxy?.onHistoryChanged(canGoBack)
                }
            }
        )
        geckoView?.let { browserManager?.attachView(it) }
        browserManager?.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ServiceLocator.vmProxy?.onImportIntent(intent)
    }

    override fun onDestroy() {
        browserManager?.shutdown()
        browserManager = null
        geckoView = null
        super.onDestroy()
    }
}

/** Host object so we never leak the Activity into the engine module. */
object GeckoViewHolder {
    fun create(context: Context): org.mozilla.geckoview.GeckoView =
        org.mozilla.geckoview.GeckoView(context)
}

/**
 * Process-wide module wiring. Every feature module is instantiated exactly once
 * and only exposes its "api/" surface upward.
 */
object ServiceLocator {
    lateinit var extensionBridge: GeckoExtensionBridge
        private set

    var vmProxy: InspectorViewModelProxy? = null

    var vmFactory: androidx.lifecycle.ViewModelProvider.Factory? = null
        private set

    fun requireVmFactory(): androidx.lifecycle.ViewModelProvider.Factory {
        check(vmFactory != null) { "ServiceLocator.ensureInitialized must run first" }
        return vmFactory!!
    }

    fun ensureInitialized(appContext: Context) {
        if (this::extensionBridge.isInitialized) return
        val parser = JwtAndCookieParserImpl()
        val database = InspectorDatabase.get(appContext)
        extensionBridge = GeckoExtensionBridge(parser, database)
        val repeater = OkHttpRepeaterEngine()
        val storage = MediaStoreJsonlManager(appContext)

        vmFactory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val vm = InspectorViewModel(
                    app = appContext as android.app.Application,
                    interceptor = extensionBridge,
                    repeaterApi = repeater,
                    storage = storage
                )
                vmProxy = object : InspectorViewModelProxy {
                    override fun onBrowserUrlChanged(url: String) = vm.onBrowserUrlChanged(url)
                    override fun onPageLoading(progress: Int) = vm.onPageLoading(progress)
                    override fun onHistoryChanged(canGoBack: Boolean) = vm.onHistoryChanged(canGoBack)
                    override fun onImportIntent(data: Intent?) = vm.onImportIntent(data)
                }
                return vm as T
            }
        }

        attachNativeRoutingModule(appContext)
    }

    /**
     * WebExtension packets arrive as runtime messages on the port delegate that
     * [GeckoBrowserManager] wires into the bridge; this helper additionally
     * supports "onMessage"-style deliveries into the same pipeline.
     */
    private fun attachNativeRoutingModule(appContext: Context) {
        extensionBridge.attachNativeBridge(com.netforge.inspector.modules.interceptor.api.NetworkInterceptorApi.NativePacketReceiver { json ->
            // Route through the bridge's internal pipeline on its own scope.
            if (json.isNotBlank()) {
                extensionBridge.forwardExternalJson(json)
            }
        })
    }
}

/**
 * Proxy surface exposing the ViewModel callbacks without keeping a leak-prone
 * direct reference from the Gecko callbacks.
 */
interface InspectorViewModelProxy {
    fun onBrowserUrlChanged(url: String)
    fun onPageLoading(progress: Int)
    fun onHistoryChanged(canGoBack: Boolean)
    fun onImportIntent(data: Intent?)
}
