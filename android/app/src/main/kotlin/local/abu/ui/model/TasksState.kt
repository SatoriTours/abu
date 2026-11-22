package local.abu.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.abu.data.TaskDetail
import local.abu.data.TaskException
import local.abu.data.TaskList
import local.abu.data.TaskRecord
import local.abu.data.TaskStatus
import java.util.UUID

internal const val TaskTextLimit = 4000
internal const val TaskRecordLimit = 200

internal class TasksState(
    private val loadTasks: suspend (String) -> TaskList,
    private val createTask: suspend (String, String, String) -> TaskDetail,
    private val cancelTask: suspend (String, String) -> TaskDetail,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    data class Submission(val id: String, val content: String, val fromDraft: Boolean)
    enum class Operation { REFRESH, SUBMIT, CANCEL }

    private var activeToken by mutableStateOf("")
    private var generation = 0
    private var refreshRequested = false
    private var capacityReported by mutableStateOf(false)

    var tasks by mutableStateOf(emptyList<TaskRecord>())
        private set
    var draft by mutableStateOf("")
        private set
    var pending by mutableStateOf<Submission?>(null)
        private set
    var operation by mutableStateOf<Operation?>(null)
        private set
    var modelConfigured by mutableStateOf<Boolean?>(null)
        private set
    var storageAvailable by mutableStateOf<Boolean?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val hasToken get() = activeToken.isNotBlank()
    val busy get() = operation != null
    val atCapacity get() = capacityReported || tasks.size >= TaskRecordLimit
    val draftLength get() = draft.codePointCount(0, draft.length)
    private val canWrite get() = hasToken && !busy && storageAvailable == true
    private val canCreate get() = canWrite && modelConfigured == true
    val canSubmit get() = canCreate && (pending != null || (!atCapacity && draft.isNotBlank() && draftLength <= TaskTextLimit))

    fun setToken(value: String) {
        if (activeToken == value) return
        activeToken = value
        // A→B→A also starts a new session; a late reply belongs only to its original session.
        generation++
        refreshRequested = false
        tasks = emptyList()
        draft = ""
        pending = null
        operation = null
        modelConfigured = null
        storageAvailable = null
        capacityReported = false
        error = null
    }

    fun edit(value: String) {
        if (!busy && pending == null) draft = value
    }

    fun abandon() {
        if (busy || pending == null) return
        pending = null
        error = null
    }

    fun canCancel(task: TaskRecord) =
        canWrite && (task.status == TaskStatus.QUEUED || task.status == TaskStatus.RUNNING)

    fun canRerun(task: TaskRecord) =
        canCreate && !atCapacity && pending == null && when (task.status) {
            TaskStatus.FAILED, TaskStatus.INTERRUPTED, TaskStatus.CANCELLED -> true
            else -> false
        }

    suspend fun refresh() {
        if (!hasToken) return
        if (busy) {
            if (operation != Operation.REFRESH) refreshRequested = true
            return
        }
        refreshRequested = false
        perform(Operation.REFRESH, loadTasks) { response ->
            tasks = response.tasks.takeLast(TaskRecordLimit)
            modelConfigured = response.modelConfigured
            storageAvailable = response.storageAvailable
            capacityReported = false
        }
    }

    suspend fun submit() {
        if (!canSubmit) return
        submit(pending ?: Submission(newId(), draft, fromDraft = true))
    }

    suspend fun rerun(id: String) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (!canRerun(task)) return
        submit(Submission(newId(), task.content, fromDraft = false))
    }

    private suspend fun submit(submission: Submission) {
        pending = submission
        perform(Operation.SUBMIT, { createTask(it, submission.id, submission.content) }) { response ->
            if (response.task.id != submission.id || response.task.content != submission.content) {
                throw TaskException("invalid_response")
            }
            update(response)
            if (submission.fromDraft) draft = ""
            pending = null
        }
    }

    suspend fun cancel(id: String) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        if (!canCancel(task)) return
        perform(Operation.CANCEL, { cancelTask(it, id) }) { response ->
            if (response.task.id != id) throw TaskException("invalid_response")
            update(response)
        }
    }

    private fun update(response: TaskDetail) {
        modelConfigured = response.modelConfigured
        storageAvailable = response.storageAvailable
        val index = tasks.indexOfFirst { it.id == response.task.id }
        tasks = if (index == -1) {
            (tasks + response.task).takeLast(TaskRecordLimit)
        } else {
            tasks.toMutableList().apply { this[index] = response.task }
        }
    }

    private suspend fun <T> perform(kind: Operation, request: suspend (String) -> T, apply: (T) -> Unit) {
        val session = generation
        operation = kind
        error = null
        try {
            val response = request(activeToken)
            currentCoroutineContext().ensureActive()
            if (session == generation) apply(response)
        } catch (failure: TaskException) {
            currentCoroutineContext().ensureActive()
            if (session == generation) {
                when (failure.code) {
                    "model_not_configured" -> modelConfigured = false
                    "storage_unavailable" -> storageAvailable = false
                    "task_limit" -> capacityReported = true
                    "unauthorized" -> {
                        tasks = emptyList()
                        modelConfigured = null
                        storageAvailable = null
                        capacityReported = false
                    }
                }
                error = failure.userMessage()
            }
        } finally {
            if (session == generation) operation = null
        }
        if (session == generation && refreshRequested) refresh()
    }
}

private fun TaskException.userMessage(): String = when (code) {
    "unauthorized" -> "开发令牌无效，请到设置核对后刷新。"
    "invalid_task", "body_too_large" -> "服务没有接受这段文字，请核对任务正文。"
    "task_not_found" -> "找不到这条任务，请刷新记录。"
    "id_conflict" -> "任务编号与服务器记录冲突，请先刷新核对。"
    "task_limit" -> "已达到 200 条记录上限，不能新建任务。"
    "model_not_configured" -> "后台未配置模型，暂不能执行任务。"
    "storage_unavailable" -> "任务存储不可写，请修复后台存储后刷新。"
    "shutting_down" -> "后台正在关闭，恢复后请刷新。"
    "connection_failed" -> "无法连接任务服务，请检查后台与设备连接后重试。"
    "invalid_response", "response_too_large" -> "无法读取任务服务的响应，请刷新或重试。"
    else -> "任务服务暂不可用，请刷新或重试。"
}
