package local.abu.ui.model

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.json.*
import local.abu.data.GeneratedApp
import local.abu.data.validGeneratedAppId

// Only native labels and version metadata; bundles and documents stay in GeneratedCache.
internal class GeneratedOfflineEntries(private val path: File) {
    private val file = AtomicFile(path)
    private val json = Json
    private val identityKey = Regex("[a-f0-9]{64}")

    @Synchronized fun load(identity: String): List<GeneratedApp> =
        read().get(identity)?.let(::decode).orEmpty()

    @Synchronized fun save(identity: String, app: GeneratedApp): List<GeneratedApp> {
        require(identityKey.matches(identity))
        val all = read().filterKeys { it != identity }.toList().takeLast(3)
        val next = (load(identity).filterNot { it.id == app.id } + app).takeLast(15)
        val encoded = buildJsonObject {
            all.forEach { (id,data) -> put(id,data) }
            putJsonArray(identity) {
                next.forEach { row ->
                    addJsonObject {
                        put("id",row.id); put("name",row.name); put("version",row.currentVersion); put("revision",row.revision)
                        putJsonArray("versions") { row.versions.forEach { add(it) } }
                    }
                }
            }
        }
        path.parentFile?.mkdirs()
        val out = file.startWrite()
        try {
            out.write(encoded.toString().encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        return next
    }

    private fun read(): Map<String,JsonElement> = try {
        file.openRead().use { stream ->
            val bytes = ByteArray(131_073)
            var length = 0
            while (length < bytes.size) {
                val n = stream.read(bytes,length,bytes.size - length)
                if (n == -1) break
                length += n
            }
            require(length <= 131_072)
            json.parseToJsonElement(bytes.decodeToString(endIndex = length, throwOnInvalidSequence = true)).jsonObject
                .filterKeys { identityKey.matches(it) }.also { require(it.size <= 4) }
        }
    } catch (_: Exception) { emptyMap() }

    private fun decode(raw: JsonElement): List<GeneratedApp> = try {
        raw.jsonArray.also { require(it.size <= 15) }.map { node ->
            val item = node.jsonObject
            val id = item.getValue("id").jsonPrimitive.content.also { require(validGeneratedAppId(it)) }
            val name = item.getValue("name").jsonPrimitive.content
            require(name.length in 1..160)
            val version = item.getValue("version").jsonPrimitive.int
            val available = item.getValue("versions").jsonArray.map { it.jsonPrimitive.int }
            val revision = item.getValue("revision").jsonPrimitive.long
            require(available.size in 1..16 && available == (1..available.size).toList() &&
                version in available && revision >= available.size)
            GeneratedApp(id,name,version,revision,available)
        }.also { require(it.distinctBy { app -> app.id }.size == it.size) }
    } catch (_: Exception) { emptyList() }
}
