package local.abu.ui.model

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.abu.data.Bot
import local.abu.data.BotAvatar
import local.abu.data.BotsException
import java.util.UUID

internal data class BotFields(val name: String = "", val instructions: String = "", val avatar: BotAvatar = BotAvatar.ABU) {
    val valid get() = name.length <= 80 && instructions.length <= 8000 &&
        name.codePointCount(0, name.length) in 1..40 && instructions.codePointCount(0, instructions.length) <= 4000 &&
        name.codePoints().noneMatch { it in 0xd800..0xdfff } &&
        instructions.codePoints().noneMatch { it in 0xd800..0xdfff } &&
        name.codePoints().anyMatch {
            (!Character.isWhitespace(it) && !Character.isSpaceChar(it) && it != 0x85) || it in 0x1c..0x1f
        }
    fun asBot(id: String, revision: Long) = Bot(id,name,instructions,avatar,revision)
}

internal class BotsState(
    private val load: suspend (String) -> List<Bot>,
    private val create: suspend (String, Bot) -> Bot,
    private val update: suspend (String, Bot) -> Bot,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val delete: suspend (String, String) -> Unit = { _, _ -> throw BotsException("http_error") },
) {
    var bots by mutableStateOf(emptyList<Bot>())
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var editing by mutableStateOf(false)
        private set
    var fields by mutableStateOf(BotFields())
        private set
    var pendingCreate by mutableStateOf<Bot?>(null)
        private set
    var review by mutableStateOf<Bot?>(null)
        private set
    var conflict by mutableStateOf(false)
        private set
    private var original by mutableStateOf<Bot?>(null)
    val editingExisting get() = original != null
    val editingBotId get() = original?.id
    val canDelete get() = editing && editingExisting && !busy && original?.id != "abu"
    val hasToken get() = activeToken.isNotBlank()
    val dirty get() = fields != (original?.toFields() ?: BotFields())
    val canCreate get() = hasToken && loaded && !busy && bots.size < 32
    val canSave get() = editing && !busy && !conflict && (pendingCreate != null || fields.valid &&
        (editingExisting || bots.size < 32))
    private var activeToken by mutableStateOf("")
    private var generation = 0

    fun setToken(value: String) {
        if (activeToken == value) return
        activeToken = value
        generation++
        bots = emptyList()
        loaded = false
        busy = false
        resetEditor()
    }

    suspend fun refresh() = request { token, apply ->
        val list = load(token)
        apply { bots = list; loaded = true }
    }

    fun startCreate() {
        if (!canCreate) return
        resetEditor()
        editing = true
    }

    fun startEdit(id: String) {
        if (busy) return
        val bot = bots.firstOrNull { it.id == id } ?: return
        resetEditor()
        original = bot
        fields = bot.toFields()
        editing = true
    }

    fun editFields(value: BotFields) {
        if (editing && !busy && pendingCreate == null) fields = value
    }

    fun discardEditor() { if (!busy) resetEditor() }

    suspend fun save() {
        if (!canSave) return
        val before = original
        val payload = if (before == null) pendingCreate ?: fields.asBot(newId(),1).also { pendingCreate = it }
            else fields.asBot(before.id,before.revision)
        request { token, apply ->
            val bot = if (before == null) create(token,payload) else update(token,payload)
            apply {
                require(bot.id == payload.id)
                bots = if (bots.any { it.id == bot.id }) bots.map { if (it.id == bot.id) bot else it }
                    else bots + bot
                resetEditor()
            }
        }
    }

    suspend fun refreshConflict() {
        val id = original?.id ?: return
        if (!conflict) return
        request { token, apply ->
            val list = load(token)
            apply {
                review = list.firstOrNull { it.id == id }
                bots = list
                loaded = true
                if (review == null) error = "角色已不可用，请保留或复制草稿。"
            }
        }
    }

    fun acceptReview() {
        val latest = review ?: return
        if (!conflict || busy) return
        original = latest
        review = null
        conflict = false
        error = null
    }

    suspend fun deleteEditing(): String? {
        val target = original ?: return null
        if (!canDelete) return null
        var deletedId: String? = null
        request { token, apply ->
            delete(token, target.id)
            apply {
                bots = bots.filterNot { it.id == target.id }
                deletedId = target.id
                resetEditor()
            }
        }
        return deletedId
    }

    private fun resetEditor() {
        editing = false
        fields = BotFields()
        original = null
        review = null
        conflict = false
        pendingCreate = null
        error = null
    }

    private suspend fun request(block: suspend (String, suspend (() -> Unit) -> Unit) -> Unit) {
        if (!hasToken || busy) return
        val session = generation
        val currentToken = activeToken
        busy = true
        error = null
        try {
            block(currentToken) { apply ->
                currentCoroutineContext().ensureActive()
                if (session == generation) apply()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (session == generation) {
                error = when ((e as? BotsException)?.code) {
                    "revision_conflict" -> {
                        conflict = true
                        review = null
                        "角色已被修改，请刷新并核对最新版本。草稿已保留。"
                    }
                    "unauthorized" -> "开发令牌无效，请到设置核对。"
                    "storage_unavailable" -> "角色库不可写，草稿已保留。"
                    "bot_limit" -> "角色总数已达到 32 个。"
                    "id_conflict" -> "角色编号冲突，请刷新核对后再决定是否放弃。"
                    "cannot_delete_default_bot" -> "主助手阿布不能删除。"
                    "bot_busy" -> "该角色正在生成回复或有排队/执行中的任务，请稍后再删除。"
                    "bot_in_use" -> "该角色关联了已生成的小程序记录，暂不能删除。"
                    "bot_not_found" -> "角色已不存在，请刷新列表。"
                    "invalid_bot", "invalid_bot_id", "body_too_large" -> "角色内容不符合限制。"
                    else -> "角色请求未确认，请检查连接后重试。"
                }
            }
        } finally {
            if (session == generation) busy = false
        }
    }
}

private fun Bot.toFields() = BotFields(name,instructions,avatar)
