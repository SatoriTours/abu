package local.abu.data

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.time.Instant

const val GENERATED_FILE_LIMIT = 64 * 1024
const val GENERATED_MAX_REVISION = 9_007_199_254_740_991L
val GENERATED_FILES: List<String> = listOf("index.html", "app.css", "app.js")
private val generatedMime = listOf("text/html; charset=utf-8", "text/css; charset=utf-8", "text/javascript; charset=utf-8")
private val generatedDigest = Regex("[a-f0-9]{64}")
private val generatedId = Regex("gen-[a-z0-9][a-z0-9-]{0,47}")
private val protocolNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

data class GeneratedAsset(val path: String, val mime: String, val size: Int, val sha256: String)
data class GeneratedManifest(
    val schemaVersion: Int, val appId: String, val version: Int, val hash: String,
    val entry: String, val assets: List<GeneratedAsset>,
)
data class GeneratedApp(
    val id: String, val name: String, val currentVersion: Int, val revision: Long, val versions: List<Int>,
)
data class DraftRequest(val id: String, val botId: String, val appId: String, val name: String, val request: String, val baseVersion: Int)
data class ProposalRequest(val id: String, val botId: String, val request: String, val baseRevision: Long)
data class GeneratedJobSummary(val id: String, val botId: String, val appId: String, val kind: String, val status: String, val name: String?)
data class GeneratedJobs(val jobs: List<GeneratedJobSummary>, val modelConfigured: Boolean)
data class GeneratedProposal(val baseRevision: Long, val document: JsonObject)
data class GeneratedJob(
    val summary: GeneratedJobSummary, val request: String, val baseVersion: Int,
    val error: String?, val manifest: GeneratedManifest?, val proposal: GeneratedProposal?, val publishedVersion: Int?,
)
data class GeneratedPublication(val appId: String, val version: Int, val revision: Long)
data class GeneratedSnapshot(val revision: Long, val document: JsonObject)
data class GeneratedUpdate(val mutationId: String, val baseRevision: Long, val document: JsonObject)

class GeneratedAppSession internal constructor(val appId: String, internal val token: String, val expiresAt: Instant) {
    override fun toString(): String = "GeneratedAppSession(appId=$appId, expiresAt=$expiresAt)"
}

fun validGeneratedAppId(id: String): Boolean = generatedId.matches(id)

private fun validNumber(text: String): Boolean {
    if (!protocolNumber.matches(text)) return false
    val n = text.toDoubleOrNull() ?: return false
    val lower = text.lowercase()
    val significand = lower.substringBefore('e')
    val limit = GENERATED_MAX_REVISION.toDouble()
    if (!n.isFinite() || abs(n) > limit) return false
    if (abs(n) == limit) {
        val parts = significand.removePrefix("-").split('.', limit = 2)
        val digits = parts.joinToString("")
        val normalized = digits.trimStart('0')
        val exponent = lower.substringAfter('e', "0").toIntOrNull() ?: return false
        if (exponent !in -GENERATED_FILE_LIMIT..GENERATED_FILE_LIMIT) return false
        val max = GENERATED_MAX_REVISION.toString()
        val places = parts[0].length - (digits.length - normalized.length) + exponent
        if (places != max.length) return places < max.length
        val padded = normalized.padEnd(places, '0')
        val first = padded.take(places)
        return first < max || first == max && padded.drop(places).none { it in '1'..'9' }
    }
    return n != 0.0 || significand.none { it in '1'..'9' }
}

/** Validates the exact UTF-8 size, duplicate keys, Unicode, numbers and eight container levels. */
fun parseGeneratedDocument(source: String): JsonObject {
    require(source.length <= GENERATED_FILE_LIMIT && source.encodeToByteArray().size <= GENERATED_FILE_LIMIT)
    AppJsonKeys(source, 8, ::validNumber).check()
    val document = Json.parseToJsonElement(source).jsonObject
    fun levels(value: JsonElement, depth: Int) {
        if (value is JsonObject || value is JsonArray) {
            require(depth < 8)
            (if (value is JsonObject) value.values else value.jsonArray).forEach { levels(it, depth + 1) }
        }
    }
    levels(document, 0)
    return document
}

internal fun generatedJson(raw: ByteArray): JsonObject {
    val s = raw.decodeToString(throwOnInvalidSequence = true)
    AppJsonKeys(s, 12, ::validNumber).check()
    return Json.parseToJsonElement(s).jsonObject
}

internal fun JsonObject.generatedLong(key: String): Long = getValue(key).generatedLong()

fun JsonElement.generatedLong(): Long = jsonPrimitive.let {
    require(!it.isString && it.content.isNotEmpty() && it.content.all { c -> c in '0'..'9' })
    requireNotNull(it.longOrNull).also { n -> require(n in 0..GENERATED_MAX_REVISION) }
}

fun verifyGeneratedManifest(m: GeneratedManifest): Boolean =
    m.schemaVersion == 1 && validGeneratedAppId(m.appId) && m.version in 0..16 && m.entry == "index.html" &&
        m.assets.map { it.path } == GENERATED_FILES && m.assets.map { it.mime } == generatedMime &&
        m.assets.all { it.size in 0..GENERATED_FILE_LIMIT && generatedDigest.matches(it.sha256) } &&
        m.assets.first().size > 0 &&
        appSha256(m.assets.joinToString("") { it.sha256 }.encodeToByteArray()).toHexString() == m.hash

fun verifyGeneratedAsset(a: GeneratedAsset, bytes: ByteArray): Boolean =
    a.path in GENERATED_FILES && a.mime == generatedMime[GENERATED_FILES.indexOf(a.path)] &&
        a.size in 0..GENERATED_FILE_LIMIT && bytes.size == a.size && appSha256(bytes).toHexString() == a.sha256

internal fun parseGeneratedManifest(root: JsonObject, appId: String, version: Int): GeneratedManifest {
    root.exact("schemaVersion", "appId", "version", "hash", "entry", "assets")
    require(root.generatedLong("schemaVersion") == 1L && root.generatedLong("version") == version.toLong() && root.string("appId") == appId)
    val assets = root.getValue("assets").jsonArray
    require(assets.size == 3)
    return GeneratedManifest(1, appId, version, root.string("hash"), root.string("entry"), assets.map {
        val a = it.jsonObject
        a.exact("path", "mime", "size", "sha256")
        GeneratedAsset(a.string("path"), a.string("mime"), a.generatedLong("size").also { n -> require(n <= GENERATED_FILE_LIMIT) }.toInt(), a.string("sha256"))
    }).also { require(verifyGeneratedManifest(it)) }
}
