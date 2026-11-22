package local.abu.miniapps

import kotlinx.serialization.json.*
import local.abu.data.AppManifest
import local.abu.data.TodoItem
import local.abu.data.TodoSnapshot
import local.abu.data.verifyAppAsset
import java.security.MessageDigest

internal const val TodoAppId = "personal-todo"
internal const val TodoOrigin = "https://personal-todo.apps.abu.invalid"
internal val TodoVersions = listOf("v1", "v2", "v3")
internal enum class TodoTheme(val wire: String) { LIGHT("light"), DARK("dark") }
internal const val TodoAssetLimit = 256 * 1024
internal const val MaxTodoRevision = 9_007_199_254_740_991L
internal val TodoMime = mapOf(
    "index.html" to "text/html; charset=utf-8",
    "todo.js" to "text/javascript; charset=utf-8",
    "todo.css" to "text/css; charset=utf-8",
)
private val TodoId = Regex("[A-Za-z0-9_-]{1,128}")

internal data class TodoBundle(val manifest: AppManifest, val resources: Map<String, ByteArray>) {
    private val root get() = "$TodoOrigin/${manifest.version}/"
    val entryUrl get() = root + manifest.entry

    fun pathFor(method: String, url: String, mainFrame: Boolean): String? {
        if (method != "GET" || !url.startsWith(root)) return null
        val path = url.removePrefix(root)
        return path.takeIf { it in resources && (it == manifest.entry) == mainFrame }
    }

    fun verify() {
        requireTodoManifest(manifest, manifest.version)
        require(resources.keys == TodoMime.keys)
        require(manifest.assets.all { verifyAppAsset(it, resources.getValue(it.path)) })
    }
}

internal fun requireTodoManifest(manifest: AppManifest, version: String) {
    require(manifest.id == TodoAppId && manifest.version == version && version in TodoVersions &&
        manifest.schemaVersion == 1 && manifest.entry == "index.html")
    require(manifest.capabilities == listOf("todo.read", "todo.write"))
    require(manifest.assets.size == TodoMime.size && manifest.assets.map { it.path }.toSet() == TodoMime.keys)
    require(manifest.assets.all { TodoMime[it.path] == it.mime && it.size in 1..TodoAssetLimit &&
        it.sha256.matches(Regex("[a-f0-9]{64}")) })
}

internal fun todoIdentity(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal sealed interface TodoCommand {
    data object Read : TodoCommand
    data object Refresh : TodoCommand
    data object Retry : TodoCommand
    data object RecoverDraft : TodoCommand
    data object ClearDraft : TodoCommand
    data class Draft(val id: String?, val text: String) : TodoCommand
    sealed interface Change : TodoCommand
    data class Add(val text: String) : Change
    data class Edit(val id: String, val text: String) : Change
    data class Toggle(val id: String, val done: Boolean) : Change
    data class Delete(val id: String) : Change
}

internal fun validTodoText(text: String): Boolean {
    if (text.length > 400 || text.codePointCount(0, text.length) !in 1..200) return false
    return text.codePoints().noneMatch { it in 0xd800..0xdfff } && text.codePoints().anyMatch {
        !Character.isWhitespace(it) && !Character.isSpaceChar(it) && it != 0x85 || it in 0x1c..0x1f
    }
}

internal fun validTodoItems(items: List<TodoItem>): Boolean =
    items.size <= 100 && items.map { it.id }.toSet().size == items.size &&
        items.all { TodoId.matches(it.id) && validTodoText(it.text) }

internal fun validTodoSnapshot(snapshot: TodoSnapshot): Boolean =
    snapshot.revision in 0..MaxTodoRevision && (snapshot.revision != 0L || snapshot.items.isEmpty()) &&
        validTodoItems(snapshot.items)

internal fun validTodoId(value: String): Boolean = TodoId.matches(value)

// A flat positional JSON schema has no keys to duplicate or URL/app/function fields.
internal fun parseTodoCommand(raw: String?, version: String = "v2"): TodoCommand? {
    if (raw == null || raw.length > 4096 || !FlatCommand.matches(raw)) return null
    return try {
        val a = Json.parseToJsonElement(raw).jsonArray
        fun string(index: Int) = a[index].jsonPrimitive.let { require(it.isString); it.content }
        fun id(index: Int) = string(index).also { require(validTodoId(it)) }
        fun text(index: Int) = string(index).also { require(validTodoText(it)) }
        fun <T> exact(size: Int, read: () -> T): T { require(a.size == size); return read() }
        when (string(0)) {
            "read" -> exact(1) { TodoCommand.Read }
            "refresh" -> exact(1) { TodoCommand.Refresh }
            "retry" -> exact(1) { TodoCommand.Retry }
            "recoverDraft" -> exact(1) { TodoCommand.RecoverDraft }
            "clearDraft" -> exact(1) { TodoCommand.ClearDraft }
            "draft" -> exact(3) {
                require(version == "v3")
                val item = string(1).also { require(it.isEmpty() || validTodoId(it)) }
                val value = string(2)
                require(value.length <= 400 && value.codePoints().noneMatch { it in 0xd800..0xdfff })
                TodoCommand.Draft(item.ifEmpty { null }, value)
            }
            "add" -> exact(2) { TodoCommand.Add(text(1)) }
            "edit" -> exact(3) { TodoCommand.Edit(id(1), text(2)) }
            "toggle" -> exact(3) { TodoCommand.Toggle(id(1), a[2].jsonPrimitive.let {
                require(!it.isString)
                requireNotNull(it.booleanOrNull)
            }) }
            "delete" -> exact(2) { TodoCommand.Delete(id(1)) }
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IndexOutOfBoundsException) {
        null
    }
}

private const val JsonString = """"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9A-Fa-f]{4}))*+""""
private const val JsonSpace = """[ \t\r\n]*"""
private val FlatCommand = Regex("""$JsonSpace\[$JsonSpace$JsonString(?:$JsonSpace,$JsonSpace(?:$JsonString|true|false)){0,2}$JsonSpace\]$JsonSpace""")

internal class TodoPortGate {
    private var sequence = 0L
    private var current: Long? = null
    fun attach(): Long = (++sequence).also { current = it }
    fun active(ticket: Long) = ticket == current
    fun detach(ticket: Long) {
        if (active(ticket)) current = null
    }
}
