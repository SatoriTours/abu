package local.abu.miniapps

import android.content.Context
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

private const val TodoCsp = "default-src 'none'; script-src 'self'; style-src 'self'; " +
    "connect-src 'none'; worker-src 'none'; child-src 'none'; frame-src 'none'; frame-ancestors 'none'; " +
    "object-src 'none'; form-action 'none'; base-uri 'none'"

private fun blocked() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(byteArrayOf())
)

@Suppress("DEPRECATION") // Explicitly disable legacy file URL access on supported older Android versions.
internal class TodoWebView(
    context: Context,
    private val bundle: TodoBundle,
    private val gate: TodoPortGate,
    private val identityStillActive: () -> Boolean,
    private val onCommand: (TodoCommand, () -> Boolean, (String) -> Unit) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebView(context) {
    private val ticket = gate.attach()
    private var disposed = false
    private var dead = false
    private var port: WebMessagePort? = null
    private var pageState = ""

    init {
        initializeWebPolicy()
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
            safeBrowsingEnabled = false // This view can serve only its fixed local HTTPS origin.
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        setDownloadListener { _, _, _, _, _ -> }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message) = false
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
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val path = bundle.pathFor(request.method, request.url.toString(), request.isForMainFrame) ?: return blocked()
                val mime = TodoMime.getValue(path).substringBefore(';')
                return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                    "Cache-Control" to "no-store",
                    "Content-Security-Policy" to TodoCsp,
                    "X-Content-Type-Options" to "nosniff",
                    "Referrer-Policy" to "no-referrer",
                    "Permissions-Policy" to "camera=(), microphone=(), geolocation=()",
                ), ByteArrayInputStream(bundle.resources.getValue(path)))
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!active() || url != bundle.entryUrl || port != null) return
                val ports = createWebMessageChannel()
                port = ports[0].apply {
                    setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                        override fun onMessage(target: WebMessagePort, message: WebMessage) {
                            val unexpectedPorts = message.ports
                            if (!unexpectedPorts.isNullOrEmpty()) {
                                unexpectedPorts.forEach { it.close() }
                                return
                            }
                            if (!active()) return
                            when (val cmd = parseTodoCommand(message.data, bundle.manifest.version)) {
                                TodoCommand.Read -> sendPageState()
                                null -> sendPageState()
                                else -> onCommand(cmd, ::active) { answer ->
                                    if (active()) {
                                        pageState = answer
                                        sendPageState()
                                    }
                                }
                            }
                        }
                    })
                }
                postWebMessage(WebMessage("abu-todo-port-v1", arrayOf(ports[1])), Uri.parse(TodoOrigin))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dead = true
                disconnect()
                onRendererGone()
                return true
            }
        }
        loadUrl(bundle.entryUrl)
    }

    private fun active() = !disposed && !dead && gate.active(ticket) && identityStillActive()

    fun updatePageState(data: String) {
        if (data == pageState) return
        pageState = data
        sendPageState()
    }

    private fun sendPageState() {
        if (active() && pageState.isNotEmpty()) port?.postMessage(WebMessage(pageState))
    }

    private fun disconnect() {
        gate.detach(ticket)
        port?.close()
        port = null
    }

    fun release() {
        if (disposed) return
        disposed = true
        disconnect()
        if (!dead) stopLoading()
        destroy()
    }

    private companion object {
        var initialized = false

        // A Worker callback has no owning WebView: never route any application resources here.
        fun initializeWebPolicy() {
            if (initialized) return
            setWebContentsDebuggingEnabled(false)
            CookieManager.getInstance().setAcceptCookie(false)
            ServiceWorkerController.getInstance().apply {
                serviceWorkerWebSettings.apply {
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                }
                setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = blocked()
                })
            }
            initialized = true
        }
    }
}
