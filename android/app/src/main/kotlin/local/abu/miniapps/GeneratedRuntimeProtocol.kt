package local.abu.miniapps

import java.security.MessageDigest
import java.util.UUID
import local.abu.data.*

// Defense in depth; this is not an OS egress sandbox.
internal const val GeneratedCsp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src data:; " +
    "connect-src 'none'; worker-src 'none'; child-src 'none'; frame-src 'none'; frame-ancestors 'none'; " +
    "object-src 'none'; media-src 'none'; form-action 'none'; base-uri 'none'; sandbox allow-scripts allow-same-origin"

private val generatedRequestId = Regex("[A-Za-z0-9_-]{1,128}")

internal class GeneratedOrigin private constructor(val origin: String, val root: String) {
    val entryUrl get() = root + "index.html"

    fun pathFor(method: String, url: String, mainFrame: Boolean): String? {
        if (method != "GET" || !url.startsWith(root)) return null
        val path = url.removePrefix(root)
        return path.takeIf { it in GENERATED_FILES && (it == "index.html") == mainFrame }
    }

    companion object {
        fun published(appId: String, version: Int): GeneratedOrigin {
            require(validGeneratedAppId(appId) && version in 1..16)
            val origin = "https://$appId.generated.abu.invalid"
            return GeneratedOrigin(origin, "$origin/v$version/")
        }

        fun preview(appId: String, draftId: String): GeneratedOrigin {
            require(validGeneratedAppId(appId) && generatedRequestId.matches(draftId))
            val digest = MessageDigest.getInstance("SHA-256").digest("$appId:$draftId".encodeToByteArray())
                .take(24).joinToString("") { "%02x".format(it) }
            val origin = "https://preview-$digest.generated.abu.invalid"
            return GeneratedOrigin(origin, "$origin/draft/")
        }
    }
}

internal class GeneratedBundle(val manifest: GeneratedManifest, resources: Map<String, ByteArray>) {
    private val resources = resources.mapValues { it.value.copyOf() }

    init {
        require(verifyGeneratedManifest(manifest) && this.resources.keys == GENERATED_FILES.toSet())
        require(manifest.assets.all { verifyGeneratedAsset(it, this.resources.getValue(it.path)) })
    }

    fun bytes(path: String): ByteArray = resources.getValue(path).copyOf()
}

internal sealed interface GeneratedBridgeCommand {
    data object Read : GeneratedBridgeCommand
    data class Propose(val baseRevision: Long, val document: kotlinx.serialization.json.JsonObject) : GeneratedBridgeCommand
}

internal fun parseGeneratedCommand(raw: String?): GeneratedBridgeCommand? {
    if (raw == null || raw.length > GENERATED_FILE_LIMIT + 128 ||
        raw.encodeToByteArray().size > GENERATED_FILE_LIMIT + 128) return null
    if (raw == """["read"]""") return GeneratedBridgeCommand.Read
    // Recognize only a fixed array prefix. The final object gets its own strict, depth-bounded parser.
    val head = ProposalHead.find(raw) ?: return null
    if (!raw.endsWith(']')) return null
    return try {
        val revision = head.groupValues[1].toLong()
        require(revision in 0 until GENERATED_MAX_REVISION)
        GeneratedBridgeCommand.Propose(revision, parseGeneratedDocument(raw.substring(head.range.last + 1, raw.lastIndex)))
    } catch (_: IllegalArgumentException) { null }
}

private val ProposalHead = Regex("""^\["propose",(0|[1-9][0-9]{0,15}),""")

/** Holds one native-only confirmation for one live page generation. Confined to the main thread. */
internal class GeneratedPortGate(private val nowMillis: () -> Long) {
    class Page internal constructor(val appId: String, val preview: Boolean)
    class Ticket internal constructor(
        internal val page: Page, val update: GeneratedUpdate, val prior: GeneratedSnapshot, internal val expires: Long,
    )
    private var page: Page? = null
    private var ticket: Ticket? = null
    private var window = 0L
    private var messages = 0

    fun attach(appId: String, preview: Boolean): Page {
        require(validGeneratedAppId(appId))
        revoke()
        return Page(appId, preview).also { page = it; window = nowMillis(); messages = 0 }
    }
    fun active(page: Page) = this.page === page
    fun revoke() { page = null; ticket = null }
    fun discard() { ticket = null }
    fun allow(page: Page): Boolean {
        if (!active(page)) return false
        val now = nowMillis()
        if (now - window >= 1000L) { window = now; messages = 0 }
        if (messages >= 12) return false
        messages++
        return true
    }

    fun propose(page: Page, change: GeneratedBridgeCommand.Propose, snapshot: GeneratedSnapshot, online: Boolean): Ticket? {
        if (currentApproval(page) != null || !active(page) || page.preview || !online || change.baseRevision != snapshot.revision ||
            change.baseRevision !in 0 until GENERATED_MAX_REVISION) return null
        val document = parseGeneratedDocument(change.document.toString())
        return Ticket(page, GeneratedUpdate(UUID.randomUUID().toString(), change.baseRevision, document), snapshot,
            nowMillis() + 60_000).also { ticket = it }
    }

    fun currentApproval(page: Page): Ticket? {
        if (!active(page)) return null
        if (ticket?.let { nowMillis() >= it.expires } == true) ticket = null
        return ticket
    }

    fun confirm(page: Page, approval: Ticket, snapshot: GeneratedSnapshot, online: Boolean): GeneratedUpdate? {
        if (currentApproval(page) !== approval || approval.page !== page) return null
        ticket = null
        return approval.update.takeIf { !page.preview && online && approval.prior == snapshot }
    }
}

internal fun encodeGeneratedPageState(
    snapshot: GeneratedSnapshot, mode: String, timeMillis: Long?, error: String? = null,
): String = kotlinx.serialization.json.buildJsonObject {
    put("revision", kotlinx.serialization.json.JsonPrimitive(snapshot.revision))
    put("document", snapshot.document)
    put("mode", kotlinx.serialization.json.JsonPrimitive(mode))
    if (error != null) put("error", kotlinx.serialization.json.JsonPrimitive(error))
    if (timeMillis != null) put("snapshotTimeMillis", kotlinx.serialization.json.JsonPrimitive(timeMillis))
}.toString()
