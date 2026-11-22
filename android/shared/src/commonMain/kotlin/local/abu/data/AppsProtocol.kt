package local.abu.data

import kotlinx.serialization.json.*

internal val appIdPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
internal val appVersionPattern = Regex("v[1-9][0-9]{0,8}")
internal val todoIdPattern = Regex("[A-Za-z0-9_-]{1,128}")
internal const val maxTodoRevision = (1L shl 53) - 1
private val assetSegment = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")
private val hexDigest = Regex("[a-f0-9]{64}")
private val capabilities = listOf("todo.read", "todo.write")

internal expect fun appSha256(data: ByteArray): ByteArray

/** Recheck a cached asset against the manifest before use. Hashes are not signatures. */
fun verifyAppAsset(asset: AppAsset, bytes: ByteArray): Boolean =
    validAppAsset(asset) && bytes.size == asset.size && appSha256(bytes).toHexString() == asset.sha256

internal fun validAppAssetPath(path: String): Boolean =
    path.length <= 192 && path.split('/').all { assetSegment.matches(it) }

internal fun validAppAsset(asset: AppAsset): Boolean {
    val expectedMime = when (asset.path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> return false
    }
    return validAppAssetPath(asset.path) && asset.mime == expectedMime &&
        asset.size in 1..256 * 1024 && hexDigest.matches(asset.sha256)
}

internal fun parseAppCatalog(root: JsonObject): AppCatalog {
    root.exact("apps")
    val rows = root.getValue("apps").jsonArray
    require(rows.size <= 16)
    val apps = rows.map { raw ->
        val app = raw.jsonObject
        app.exact("id", "name", "currentVersion", "versions")
        val id = app.string("id")
        val name = app.string("name")
        val version = app.string("currentVersion")
        val versions = app.getValue("versions").jsonArray.map { it.asString() }
        require(appIdPattern.matches(id) && name.isNotBlank() && scalarLength(name) in 1..80)
        require(versions.size in 1..16 && versions.toSet().size == versions.size &&
            versions.all { appVersionPattern.matches(it) } && version in versions)
        AppCatalogEntry(id, name, version, versions)
    }
    require(apps.distinctBy { it.id }.size == apps.size)
    return AppCatalog(apps)
}

internal fun parseAppManifest(root: JsonObject, appId: String, version: String): AppManifest {
    root.exact("schemaVersion", "id", "version", "entry", "capabilities", "assets")
    require(root.integer("schemaVersion") == 1L && root.string("id") == appId && root.string("version") == version)
    val requested = root.getValue("capabilities").jsonArray.map { it.asString() }
    require(requested == capabilities)
    val entry = root.string("entry")
    val records = root.getValue("assets").jsonArray
    require(records.size in 1..16)
    val assets = records.map { raw ->
        val asset = raw.jsonObject
        asset.exact("path", "mime", "size", "sha256")
        val size = asset.integer("size")
        require(size in 1..256L * 1024)
        AppAsset(asset.string("path"), asset.string("mime"), size.toInt(), asset.string("sha256")).also {
            require(validAppAsset(it))
        }
    }
    require(assets.sumOf { it.size } <= 1024 * 1024 && assets.distinctBy { it.path }.size == assets.size)
    require(assets.any { it.path == entry && it.mime == "text/html; charset=utf-8" })
    return AppManifest(1, appId, version, entry, requested, assets)
}

internal fun parseTodoSnapshot(root: JsonObject): TodoSnapshot {
    root.exact("revision", "items")
    val revision = root.integer("revision")
    val array = root.getValue("items").jsonArray
    require(revision in 0..maxTodoRevision && array.size <= 100)
    val items = array.map { raw ->
        val item = raw.jsonObject
        item.exact("id", "text", "done")
        val done = item.getValue("done").jsonPrimitive
        require(!done.isString && (done.content == "true" || done.content == "false"))
        TodoItem(item.string("id"), item.string("text"), done.content == "true")
    }
    require(validTodoItems(items) && (revision != 0L || items.isEmpty()))
    return TodoSnapshot(revision, items)
}

internal fun validTodoItems(items: List<TodoItem>): Boolean =
    items.size <= 100 && items.distinctBy { it.id }.size == items.size && items.all {
        todoIdPattern.matches(it.id) && scalarLength(it.text) in 1..200 && it.text.any { c -> !todoSpace(c) }
    }

// Match the server's Unicode White_Space rule, independent of platform trim behavior.
private fun todoSpace(c: Char): Boolean = c in '\u0009'..'\u000d' || c == ' ' || c == '\u0085' ||
    c == '\u00a0' || c == '\u1680' || c in '\u2000'..'\u200a' || c == '\u2028' || c == '\u2029' ||
    c == '\u202f' || c == '\u205f' || c == '\u3000'

private fun scalarLength(s: String): Int {
    var count = 0
    var i = 0
    while (i < s.length) {
        val ch = s[i++]
        if (ch in '\ud800'..'\udbff') {
            if (i == s.length || s[i++] !in '\udc00'..'\udfff') return -1
        } else if (ch in '\udc00'..'\udfff') return -1
        count++
    }
    return count
}

internal fun JsonObject.exact(vararg keys: String) = require(this.keys == keys.toSet())
internal fun JsonObject.string(key: String): String = getValue(key).asString()
private fun JsonElement.asString(): String = jsonPrimitive.let { require(it.isString); it.content }
private fun JsonObject.integer(key: String): Long = getValue(key).jsonPrimitive.let {
    require(!it.isString && jsonInteger.matches(it.content))
    requireNotNull(it.longOrNull)
}
private val jsonInteger = Regex("-?(0|[1-9][0-9]*)")

internal fun parseAppJson(raw: ByteArray): JsonObject {
    val source = raw.decodeToString(throwOnInvalidSequence = true)
    AppJsonKeys(source).check()
    return Json.parseToJsonElement(source).jsonObject
}

// Kotlin's JSON tree retains only the last duplicate key. Check each object before decoding.
private class AppJsonKeys(private val source: String) {
    private var pos = 0
    private val delimiters = " \t\r\n,]}"
    private fun peek(): Char? = source.getOrNull(pos)
    private fun whitespace() {
        while (peek()?.let { it in " \t\r\n" } == true) pos++
    }
    private fun take(c: Char): Boolean {
        whitespace()
        if (peek() != c) return false
        pos++
        return true
    }

    fun check() {
        value(0)
        whitespace()
        require(pos == source.length)
    }

    private fun value(depth: Int) {
        require(depth <= 8)
        whitespace()
        when (peek()) {
            '{' -> {
                pos++
                val seen = mutableSetOf<String>()
                if (!take('}')) {
                    do {
                        whitespace()
                        require(seen.add(string()) && take(':'))
                        value(depth + 1)
                    } while (take(','))
                    require(take('}'))
                }
            }
            '[' -> {
                pos++
                if (!take(']')) {
                    do { value(depth + 1) } while (take(','))
                    require(take(']'))
                }
            }
            '"' -> string()
            else -> {
                val start = pos
                while (peek()?.let { it !in delimiters } == true) pos++
                val token = source.substring(start, pos)
                require(token == "true" || token == "false" || token == "null" || jsonInteger.matches(token))
            }
        }
    }

    private fun string(): String {
        val start = pos
        require(peek() == '"')
        pos++
        while (pos < source.length) {
            when (val c = source[pos++]) {
                '\\' -> {
                    require(pos < source.length)
                    pos++
                }
                '"' -> return Json.parseToJsonElement(source.substring(start, pos)).asString().also {
                    require(scalarLength(it) >= 0)
                }
                else -> require(c >= ' ')
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }
}
