package local.abu.miniapps.probe

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream

// Fixed, bundled debug fixtures only. Intent data/extras never choose a URL or asset.
class MiniAppProbeActivity : Activity() {
    private lateinit var area: LinearLayout
    private var current: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 28) {
            setContentView(TextView(this).apply { text = "Probe requires API 28+ for a separate WebView data directory" })
            return
        }
        synchronized(MiniAppProbeActivity::class.java) {
            if (!initialized) {
                WebView.setDataDirectorySuffix("miniapps_probe")
                initializeWorkers(applicationContext)
                initialized = true
            }
        }
        val buttons = LinearLayout(this)
        fun button(label: String, action: () -> Unit) {
            buttons.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        button("A v1") { open(ProbePage.A1) }
        button("A v2") { open(ProbePage.A2) }
        button("B") { open(ProbePage.B) }
        button("Close WebView") { closePage() }
        area = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        area.addView(buttons)
        setContentView(area)
        open(ProbePage.A1)
    }

    @Suppress("DEPRECATION") // Enforce file restrictions explicitly even on older probe engines.
    private fun open(page: ProbePage) {
        closePage()
        val view = WebView(this)
        current = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: Message): Boolean {
                Log.i(TAG, "DENY popup gesture=$gesture")
                return false
            }

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                Log.i(TAG, "DENY file chooser")
                callback.onReceiveValue(null)
                return true
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.url.toString() != page.url) {
                    Log.i(TAG, "DENY top navigation ${targetLabel(request)}")
                    return true
                }
                return false // Subframes still go through the resource allowlist.
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val asset = page.assetFor(request.method, request.url.toString())
                if (asset == null) {
                    Log.i(TAG, "DENY page=${page.name} resource ${targetLabel(request)} main=${request.isForMainFrame}")
                    return blocked()
                }
                Log.i(TAG, "ASSET page=${page.name} asset=$asset")
                return WebResourceResponse(
                    if (asset.endsWith(".js")) "text/javascript" else "text/html",
                    "UTF-8", 200, "OK", mapOf(
                        "Cache-Control" to "no-store",
                        "X-Content-Type-Options" to "nosniff",
                        // Deliberately let the fixed fetch/frame probes reach the host deny rules.
                        "Content-Security-Policy" to "default-src 'none'; script-src 'self'; worker-src 'self'; connect-src https: http://127.0.0.1:8080; frame-src https:; base-uri 'none'; form-action 'none'",
                        "Permissions-Policy" to "camera=(), microphone=(), geolocation=()"
                    ),
                    assets.open("miniapps-probe/$asset")
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == page.url) readResult(view, page, 96)
            }
        }
        area.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        view.loadUrl(page.url)
    }

    private fun readResult(view: WebView, page: ProbePage, remaining: Int) {
        if (current !== view) return
        view.evaluateJavascript("document.getElementById('result').dataset.finished === 'yes' ? document.getElementById('result').textContent : null") { result ->
            if (current !== view) return@evaluateJavascript
            if (result != "null") Log.i(TAG, "RESULT page=${page.name} json=$result")
            else if (remaining > 0) view.postDelayed({ readResult(view, page, remaining - 1) }, 250)
            else Log.i(TAG, "RESULT page=${page.name} timed_out")
        }
    }

    private fun closePage() {
        val view = current ?: return
        current = null
        area.removeView(view)
        view.stopLoading()
        view.destroy()
        Log.i(TAG, "CLOSED WebView")
    }

    override fun onDestroy() {
        closePage()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AbuMiniProbe"
        var initialized = false

        fun initializeWorkers(context: Context) {
            val controller = ServiceWorkerController.getInstance()
            controller.serviceWorkerWebSettings.apply {
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
            }
            controller.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse {
                    // This callback has no owning WebView. Never serve page/data routes here.
                    val asset = ProbePage.workerAssetFor(request.method, request.url.toString())
                    if (asset != null) {
                        Log.i(TAG, "ASSET worker asset=$asset")
                        return WebResourceResponse("text/javascript", "UTF-8", 200, "OK", mapOf(
                            "Cache-Control" to "no-store",
                            "X-Content-Type-Options" to "nosniff"
                        ), context.assets.open("miniapps-probe/$asset"))
                    }
                    Log.i(TAG, "DENY worker resource ${targetLabel(request)}")
                    return blocked()
                }
            })
        }

        fun targetLabel(request: WebResourceRequest): String {
            val uri = request.url
            return when (uri.scheme) {
                "file" -> "file"
                "content" -> "content"
                else -> when (uri.host) {
                    "a.abu.invalid", "b.abu.invalid", "blocked.invalid", "127.0.0.1" ->
                        "${uri.scheme}://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}${uri.encodedPath}"
                    else -> "outside allowlist"
                }
            }
        }

        fun blocked() = WebResourceResponse(
            "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream("Blocked by probe host".toByteArray())
        )
    }
}
