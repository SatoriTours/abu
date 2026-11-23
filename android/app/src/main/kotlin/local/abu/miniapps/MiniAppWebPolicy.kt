package local.abu.miniapps

import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream

internal fun blockedMiniAppRequest() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(byteArrayOf())
)

private var initialized = false

// A Worker callback has no owning WebView: do not serve any app resources there.
// Called on the UI thread by both miniapp hosts.
internal fun initializeMiniAppWebPolicy() {
    if (initialized) return
    WebView.setWebContentsDebuggingEnabled(false)
    CookieManager.getInstance().setAcceptCookie(false)
    ServiceWorkerController.getInstance().apply {
        serviceWorkerWebSettings.apply {
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
        }
        setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest) = blockedMiniAppRequest()
        })
    }
    initialized = true
}
