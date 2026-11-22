package local.abu.miniapps

import local.abu.data.AppAsset
import local.abu.data.AppCatalogEntry
import local.abu.data.AppManifest
import local.abu.data.TodoItem
import local.abu.data.TodoSnapshot
import local.abu.data.TodoUpdate
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal data class TimedTodo(val snapshot: TodoSnapshot, val timeMillis: Long)
internal data class PendingTodo(val update: TodoUpdate, val command: TodoCommand.Change, val conflict: Boolean = false)
internal data class TodoJournal(val timed: TimedTodo? = null, val pending: PendingTodo? = null)

// Private per-identity directory, fixed filenames. A journal replaces the snapshot and pending marker together.
internal class TodoCache(private val root: File) {
    fun catalog(identity: String): AppCatalogEntry? = read(file(identity, "catalog"), 64) {
        val latest = readVersion()
        val count = read()
        // The previous cache format only stored currentVersion; its supported versions were fixed.
        val versions = if (count == -1) listOf("v1", "v2") else {
            require(count in 1..TodoVersions.size)
            List(count) { readVersion() }
        }
        validateCatalog(AppCatalogEntry(TodoAppId, "个人待办", latest, versions))
    }

    fun saveCatalog(identity: String, entry: AppCatalogEntry) {
        validateCatalog(entry)
        write(identity, "catalog") {
            writeUTF(entry.currentVersion)
            writeByte(entry.versions.size)
            entry.versions.forEach(::writeUTF)
        }
    }

    private fun validateCatalog(entry: AppCatalogEntry): AppCatalogEntry {
        require(entry.id == TodoAppId && entry.currentVersion in entry.versions &&
            entry.versions.distinct().size == entry.versions.size && entry.versions.all { it in TodoVersions })
        return entry
    }

    fun preferred(identity: String): String? = read(file(identity, "preferred"), 32) { readVersion() }

    fun prefer(identity: String, version: String) {
        require(version in TodoVersions)
        write(identity, "preferred") { writeUTF(version) }
    }

    fun bundle(identity: String, version: String): TodoBundle? {
        require(version in TodoVersions)
        return read(file(identity, "bundle-$version"), 1024 * 1024 + 4096) {
            val actualVersion = readVersion()
            require(actualVersion == version)
            val assets = TodoMime.map { (path, mime) ->
                val size = readInt()
                require(size in 1..TodoAssetLimit)
                AppAsset(path, mime, size, readUTF().also { require(it.length == 64) })
            }
            val manifest = AppManifest(1, TodoAppId, actualVersion, "index.html", listOf("todo.read", "todo.write"), assets)
            requireTodoManifest(manifest, version)
            val contents = assets.associate { it.path to ByteArray(it.size).also(::readFully) }
            TodoBundle(manifest, contents).also { it.verify() }
        }
    }

    fun saveBundle(identity: String, bundle: TodoBundle) {
        bundle.verify()
        write(identity, "bundle-${bundle.manifest.version}") {
            writeUTF(bundle.manifest.version)
            for (path in TodoMime.keys) {
                val asset = bundle.manifest.assets.single { it.path == path }
                writeInt(asset.size)
                writeUTF(asset.sha256)
            }
            for (path in TodoMime.keys) write(bundle.resources.getValue(path))
        }
    }

    // Unlike a broken download, a broken journal must not become a fresh empty writable state.
    fun data(identity: String): TodoJournal {
        val journal = file(identity, "journal")
        if (journal != null && journal.exists()) {
            return read(journal, 512 * 1024) {
                val timed = if (readBoolean()) {
                    val time = readLong()
                    require(time > 0)
                    val snapshot = TodoSnapshot(readLong(), readItems())
                    require(validTodoSnapshot(snapshot))
                    TimedTodo(snapshot, time)
                } else null
                val pending = if (readBoolean()) {
                    val update = TodoUpdate(readUTF(), readLong(), readItems())
                    val command = parseTodoCommand(readUTF()) as? TodoCommand.Change ?: error("Invalid pending command")
                    require(validTodoId(update.mutationId) && update.baseRevision in 0 until MaxTodoRevision && timed != null)
                    PendingTodo(update, command, readBoolean())
                } else null
                TodoJournal(timed, pending)
            } ?: throw IOException("Invalid todo journal")
        }
        return TodoJournal()
    }

    fun saveData(identity: String, journal: TodoJournal) = write(identity, "journal") {
        writeBoolean(journal.timed != null)
        journal.timed?.let { (snapshot, time) ->
            require(validTodoSnapshot(snapshot) && time > 0)
            writeLong(time)
            writeLong(snapshot.revision)
            writeItems(snapshot.items)
        }
        writeBoolean(journal.pending != null)
        journal.pending?.let { (update, command, conflict) ->
            require(journal.timed != null && validTodoId(update.mutationId) && update.baseRevision in 0 until MaxTodoRevision)
            writeUTF(update.mutationId)
            writeLong(update.baseRevision)
            writeItems(update.items)
            writeUTF(commandJson(command))
            writeBoolean(conflict)
        }
    }

    private fun DataInputStream.readVersion() = readUTF().also { require(it in TodoVersions) }

    private fun DataInputStream.readItems(): List<TodoItem> {
        val count = readInt()
        require(count in 0..100)
        return List(count) { TodoItem(readUTF(), readUTF(), readBoolean()) }.also { require(validTodoItems(it)) }
    }

    private fun DataOutputStream.writeItems(items: List<TodoItem>) {
        require(validTodoItems(items))
        writeInt(items.size)
        for ((id, text, done) in items) {
            writeUTF(id)
            writeUTF(text)
            writeBoolean(done)
        }
    }

    private fun file(identity: String, name: String): File? {
        require(identity.matches(Partition))
        val dir = File(root, "$identity-$TodoAppId")
        return if (dir.isDirectory) File(dir, name) else null
    }

    private fun write(identity: String, name: String, body: DataOutputStream.() -> Unit) {
        val target = file(identity, name) ?: run {
            if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create cache")
            val dirs = root.list() ?: throw IOException("Cannot list cache")
            // Do not silently evict another identity's unconfirmed write.
            if (dirs.count { it.endsWith("-$TodoAppId") } >= 4) throw IOException("Todo cache is full")
            val dir = File(root, "$identity-$TodoAppId")
            if (!dir.mkdir()) throw IOException("Cannot create identity cache")
            File(dir, name)
        }
        val stage = File(target.parentFile, ".$name.tmp")
        try {
            FileOutputStream(stage).use { stream ->
                val data = DataOutputStream(stream.buffered())
                data.writeInt(1)
                data.body()
                data.flush()
                stream.fd.sync()
            }
            Files.move(stage.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            stage.delete()
        }
    }

    private fun <T> read(path: File?, max: Int, body: DataInputStream.() -> T): T? {
        if (path == null || !path.isFile || path.length() !in 1..max.toLong()) return null
        return try {
            DataInputStream(path.inputStream().buffered()).use {
                require(it.readInt() == 1)
                val decoded = it.body()
                require(it.read() == -1)
                decoded
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private companion object {
        val Partition = Regex("[a-f0-9]{64}")
    }
}
