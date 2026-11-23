package local.abu.miniapps

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import local.abu.data.*

internal data class GeneratedTimed(val snapshot: GeneratedSnapshot, val millis: Long)
internal data class GeneratedPending(val update: GeneratedUpdate, val conflict: Boolean = false)
internal data class GeneratedJournal(val timed: GeneratedTimed? = null, val pending: GeneratedPending? = null)

internal class GeneratedCache(private val root: File) {
    @Synchronized fun bundle(identity: String, app: String, version: Int): GeneratedBundle? {
        require(version in 1..16)
        return read(file(identity, app, "bundle-$version"), 3 * GENERATED_FILE_LIMIT + 4096) {
            require(readInt() == version && readUTF() == app)
            val hash = readUTF()
            val assets = GENERATED_FILES.map { path ->
                GeneratedAsset(path, readUTF(), readInt().also { require(it in 0..GENERATED_FILE_LIMIT) }, readUTF())
            }
            val resources = assets.associate { asset -> asset.path to ByteArray(asset.size).also(::readFully) }
            GeneratedBundle(GeneratedManifest(1, app, version, hash, "index.html", assets), resources)
        }
    }

    @Synchronized fun store(identity: String, bundle: GeneratedBundle) {
        val manifest = bundle.manifest
        require(manifest.version in 1..16 && verifyGeneratedManifest(manifest))
        write(file(identity, manifest.appId, "bundle-${manifest.version}")) {
            writeInt(manifest.version); writeUTF(manifest.appId); writeUTF(manifest.hash)
            for (a in manifest.assets) {
                writeUTF(a.mime); writeInt(a.size); writeUTF(a.sha256)
            }
            for (a in manifest.assets) write(bundle.bytes(a.path))
        }
    }

    @Synchronized fun selected(identity: String, app: String): Int? =
        read(file(identity, app, "active"), 4) { readInt().also { require(it in 1..16) } }

    @Synchronized fun select(identity: String, app: String, version: Int) {
        require(bundle(identity, app, version) != null) { "No complete verified bundle" }
        write(file(identity, app, "active")) { writeInt(version) }
    }

    @Synchronized fun journal(identity: String, app: String): GeneratedJournal {
        val path = file(identity, app, "journal")
        if (!path.exists()) return GeneratedJournal()
        return read(path, 2 * GENERATED_FILE_LIMIT + 1024) {
            require(readInt() == 1)
            val timed = if (readBoolean()) {
                val millis = readLong().also { require(it > 0) }
                val revision = readLong().also { require(it in 0..GENERATED_MAX_REVISION) }
                GeneratedTimed(GeneratedSnapshot(revision, parseGeneratedDocument(readText())), millis)
            } else null
            val pending = if (readBoolean()) {
                val id = readUTF().also { require(mutationId.matches(it)) }
                val revision = readLong().also { require(it in 0 until GENERATED_MAX_REVISION) }
                val conflict = readBoolean()
                require(timed != null && (conflict || revision == timed.snapshot.revision))
                GeneratedPending(GeneratedUpdate(id, revision, parseGeneratedDocument(readText())), conflict)
            } else null
            GeneratedJournal(timed, pending)
        } ?: throw IOException("Invalid generated-app journal")
    }

    @Synchronized fun storeJournal(identity: String, app: String, journal: GeneratedJournal) {
        write(file(identity, app, "journal")) {
            writeInt(1)
            writeBoolean(journal.timed != null)
            journal.timed?.let {
                require(it.millis > 0 && it.snapshot.revision in 0..GENERATED_MAX_REVISION)
                writeLong(it.millis); writeLong(it.snapshot.revision); writeText(it.snapshot.document.toString())
            }
            writeBoolean(journal.pending != null)
            journal.pending?.let { pending ->
                val update = pending.update
                require(mutationId.matches(update.mutationId) && update.baseRevision in 0 until GENERATED_MAX_REVISION)
                require(journal.timed != null && (pending.conflict || journal.timed.snapshot.revision == update.baseRevision))
                writeUTF(update.mutationId); writeLong(update.baseRevision); writeBoolean(pending.conflict)
                writeText(update.document.toString())
            }
        }
    }

    private fun file(identity: String, app: String, name: String): File {
        require(identity.matches(Regex("[a-f0-9]{64}")) && validGeneratedAppId(app))
        return File(File(File(root, identity), app), name)
    }

    private fun <T> read(file: File, limit: Int, block: DataInputStream.() -> T): T? = try {
        if (!file.isFile || file.length() !in 1L..limit.toLong()) null
        else DataInputStream(file.inputStream().buffered()).use { stream ->
            stream.block().also { require(stream.read() == -1) }
        }
    } catch (_: IOException) { null }
      catch (_: IllegalArgumentException) { null }

    private fun write(file: File, block: DataOutputStream.() -> Unit) {
        val parent = checkNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create generated-app cache directory")
        val tmp = File.createTempFile("gen-", ".tmp", parent)
        try {
            FileOutputStream(tmp).use { out ->
                val stream = DataOutputStream(out)
                stream.block()
                stream.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        require(size in 1..GENERATED_FILE_LIMIT)
        return ByteArray(size).also(::readFully).decodeToString(throwOnInvalidSequence = true)
    }

    private fun DataOutputStream.writeText(value: String) {
        parseGeneratedDocument(value)
        val bytes = value.encodeToByteArray()
        writeInt(bytes.size); write(bytes)
    }

    private companion object { val mutationId = Regex("[A-Za-z0-9_-]{1,128}") }
}
