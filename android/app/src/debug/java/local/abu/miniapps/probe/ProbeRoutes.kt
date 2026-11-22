package local.abu.miniapps.probe

internal enum class ProbePage(host: String, private val html: String) {
    A1("a.abu.invalid", "a-v1.html"),
    A2("a.abu.invalid", "a-v2.html"),
    B("b.abu.invalid", "b.html");

    private val origin = "https://$host"
    val url = "$origin/index.html"

    fun assetFor(method: String, url: String): String? {
        if (method != "GET") return null
        return when (url) {
            this.url -> html
            "$origin/probe.js" -> "probe.js"
            else -> null
        }
    }

    companion object {
        fun workerAssetFor(method: String, url: String): String? =
            if (method == "GET" && url == "https://a.abu.invalid/worker.js") "worker.js" else null
    }
}
