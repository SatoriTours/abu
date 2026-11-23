package local.abu.miniapps

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.webkit.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.abu.data.verifyGeneratedManifest

internal object GeneratedPageHost {
    fun mount(
        context: Context, runtime: GeneratedRuntimeState, page: GeneratedOpenPage,
        dark: Boolean, onFailure: (String) -> Unit,
    ): GeneratedWebView {
        require(runtime.active(page) && verifyGeneratedManifest(page.bundle.manifest))
        initializeMiniAppWebPolicy()
        return GeneratedWebView(context, runtime, page, dark, onFailure).also { view ->
            var bound = false
            try {
                runtime.bind(page, view::updateState, view::dispose, onDenied = onFailure)
                bound = true
                view.loadUrl(page.origin.entryUrl)
            } catch (e: Exception) {
                if (bound) view.release() else view.dispose()
                throw e
            }
        }
    }
}

@Suppress("DEPRECATION") // Disable legacy file and cross-file access on all supported API levels.
internal class GeneratedWebView(
    context: Context,
    private val runtime: GeneratedRuntimeState,
    private val page: GeneratedOpenPage,
    dark: Boolean,
    private val onFailure: (String) -> Unit,
) : WebView(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val theme = if (dark) "dark" else "light"
    private val assets = page.bundle.manifest.assets.associateBy { it.path }
    @Volatile private var disposed = false
    private var dead = false
    private var started = false
    private var port: WebMessagePort? = null
    private var latest: String? = null
    private var lastSent: String? = null
    private var throttleUntil = 0L

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setGeolocationEnabled(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message) = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
                closeDialog(result)
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
                closeDialog(result)
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
                closeDialog(result)
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean =
                closeDialog(result)

            private fun closeDialog(result: JsResult): Boolean {
                result.cancel()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)

            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                callback.onReceiveValue(null)
                return true
            }
        }
        if (Build.VERSION.SDK_INT >= 29) installRendererWatcher(this)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val path = if (disposed) null else page.origin.pathFor(request.method, request.url.toString(), request.isForMainFrame)
                val asset = assets[path] ?: return blockedMiniAppRequest()
                return WebResourceResponse(asset.mime.substringBefore(';'), "UTF-8", 200, "OK", mapOf(
                    "Cache-Control" to "no-store",
                    "Content-Security-Policy" to GeneratedCsp,
                    "X-Content-Type-Options" to "nosniff",
                    "Referrer-Policy" to "no-referrer",
                    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), display-capture=()",
                ), ByteArrayInputStream(page.bundle.bytes(asset.path)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (started || url != page.origin.entryUrl) {
                    post { fail("unexpected_navigation") }
                } else started = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (started && url == page.origin.entryUrl && active() && port == null) connect()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) post { fail("page_load_failed") }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dead = true
                fail("renderer_gone")
                return true
            }
        }
    }

    @android.annotation.TargetApi(29)
    private fun installRendererWatcher(view: WebView) {
        view.webViewRenderProcessClient = object : WebViewRenderProcessClient() {
            override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                fail("renderer_unresponsive")
            }
            override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) = Unit
        }
    }

    private fun active() = !disposed && !dead && runtime.active(page) && url == page.origin.entryUrl

    private fun connect() {
        val pair = createWebMessageChannel()
        port = pair[0]
        pair[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(target: WebMessagePort, message: WebMessage) {
                val transferred = message.ports
                if (!transferred.isNullOrEmpty()) {
                    transferred.forEach { it.close() }
                    return
                }
                if (!active() || port !== target || android.os.SystemClock.uptimeMillis() < throttleUntil) return
                scope.launch {
                    val answer = runtime.portCommand(page, message.data) ?: return@launch
                    if (!active() || port !== target) return@launch
                    if (answer.substringAfterLast("},").contains("\"error\":\"rate_limited\"")) {
                        throttleUntil = android.os.SystemClock.uptimeMillis() + 1000
                    }
                    send(answer, force = true)
                }
            }
        })
        postWebMessage(WebMessage("abu-generated-v1", arrayOf(pair[1])), Uri.parse(page.origin.origin))
        latest?.let { send(it) }
    }

    internal fun updateState(state: GeneratedPageState) {
        val json = state.webState()
        latest = json
        if (port != null) send(json)
    }

    private fun send(json: String, force: Boolean = false) {
        if (!active() || (!force && lastSent == json)) return
        val target = port ?: return
        try {
            target.postMessage(WebMessage(json.dropLast(1) + ",\"theme\":\"$theme\"}"))
            lastSent = json
        } catch (_: IllegalStateException) {
            post { fail("port_closed") }
        }
    }

    private fun fail(code: String) {
        if (disposed) return
        release()
        onFailure(code)
    }

    fun release() {
        if (runtime.state?.page === page) runtime.close()
        dispose()
    }

    internal fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        port?.close()
        port = null
        if (!dead) stopLoading()
        (parent as? android.view.ViewGroup)?.removeView(this)
        destroy()
    }
}
