package local.abu.data

/** The loopback development server used when no address has been configured. */
const val DEFAULT_SERVER_BASE_URL = "http://127.0.0.1:8080"

private val hostLabel = Regex("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?")
private val ipv6Host = Regex("[0-9A-Fa-f:.]+")

/**
 * Normalizes one configured server address into a canonical origin, or returns null when the
 * value cannot be used safely.
 *
 * Only `scheme://host[:port]` is accepted: a path, a query, a fragment, user info, whitespace or
 * a control character would make the address ambiguous and could silently redirect a request to
 * another host. Cleartext `http://` is only allowed when the caller has opted in, because the
 * development token travels in a header.
 */
fun normalizeServerBaseUrl(raw: String, allowCleartext: Boolean): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > 2048) return null
    if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
    val scheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https"
        trimmed.startsWith("http://", ignoreCase = true) -> {
            if (!allowCleartext) return null
            "http"
        }
        else -> return null
    }
    // At most one trailing slash may be stripped; multiple slashes or any path is refused below.
    val body = trimmed.substring(scheme.length + 3).removeSuffix("/")
    if (body.isEmpty() || body.length > 260) return null
    if (body.any { it == '@' || it == '?' || it == '#' || it == '/' || it == '\\' }) return null
    val parts = splitHostPort(body) ?: return null
    val host = parts.first
    val port = parts.second
    if (port != null && (port.toIntOrNull() ?: 0) !in 1..65535) return null
    return if (port == null) scheme + "://" + host else scheme + "://" + host + ":" + port
}

private fun splitHostPort(body: String): Pair<String, String?>? {
    if (body.startsWith("[")) {
        val close = body.indexOf(']')
        if (close < 0) return null
        val inner = body.substring(1, close)
        if (inner.isEmpty() || inner.length > 45 || ':' !in inner || !ipv6Host.matches(inner)) return null
        val host = "[" + inner + "]"
        val tail = body.substring(close + 1)
        return when {
            tail.isEmpty() -> host to null
            tail.startsWith(":") -> host to tail.substring(1)
            else -> null
        }
    }
    val colon = body.indexOf(':')
    if (colon < 0) return if (validHostName(body)) body to null else null
    val host = body.substring(0, colon)
    if (body.indexOf(':', colon + 1) >= 0 || !validHostName(host)) return null
    return host to body.substring(colon + 1)
}

// A host name keeps its labels: no scheme characters, no empty label and no placeholder.
private fun validHostName(host: String): Boolean = host.length in 1..253 && hostLabel.matches(host)
