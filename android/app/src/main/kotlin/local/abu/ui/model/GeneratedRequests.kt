package local.abu.ui.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import local.abu.data.*

internal sealed interface GenerationRequest {
    data class Draft(val input: DraftRequest) : GenerationRequest
    data class Proposal(val appId: String, val input: ProposalRequest) : GenerationRequest
}

internal sealed interface PublicationIntent {
    val appId: String
    val revision: Long
    data class Publish(
        override val appId: String, val jobId: String, val hash: String, val version: Int, override val revision: Long,
    ) : PublicationIntent
    data class Activate(
        override val appId: String, val id: String, val version: Int, override val revision: Long,
    ) : PublicationIntent
}

internal typealias GeneratedRequests = ExplicitGeneratedRequest<GenerationRequest, GeneratedJobSummary>
internal typealias GeneratedPublications = ExplicitGeneratedRequest<PublicationIntent, GeneratedPublication>

internal class ExplicitGeneratedRequest<I, O>(private val submit: suspend (I) -> O) {
    var pending by mutableStateOf<I?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var retired = false

    suspend fun send(input: I): O? {
        if (retired || busy || pending != null) return null
        pending = input
        return retry()
    }

    suspend fun retry(): O? {
        val input = pending ?: return null
        if (retired || busy) return null
        busy = true
        error = null
        return try {
            val result = submit(input)
            currentCoroutineContext().ensureActive()
            if (retired) null else result.also { pending = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (!retired) {
                val failure = e as? GeneratedAppsException
                // Explicit HTTP rejection is not a lost acknowledgement.
                if (failure?.statusCode?.let { it in 400..499 } == true && failure.code != "http_error") pending = null
                error = if (pending != null) "操作尚未确认。请手动重试，编号与内容不会改变。"
                    else generatedError(e)
            }
            null
        } finally {
            busy = false
        }
    }

    fun retire() {
        retired = true
        pending = null
        error = null
    }
}

// The observer is keyed by Compose state; remount itself changes that state.
internal class GeneratedThemeRemount {
    private var work by mutableStateOf<Job?>(null)
    val running get() = work != null

    fun request(scope: CoroutineScope, stillWanted: () -> Boolean, reopen: suspend () -> Unit) {
        if (running || !stillWanted()) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (stillWanted()) reopen()
            } finally {
                val ownJob = currentCoroutineContext()[Job]
                if (work === ownJob) work = null
            }
        }
        work = next
        if (!next.start()) work = null
    }

    fun cancel() {
        val old = work
        work = null
        old?.cancel()
    }
}

internal class GeneratedRetryGate {
    private var page by mutableStateOf<Any?>(null)
    fun allows(page: Any) = this.page === page
    fun unlock(page: Any) { this.page = page }
    fun lock() { page = null }
}

internal fun generatedError(error: Exception) = when ((error as? GeneratedAppsException)?.code) {
    "unauthorized", "invalid_app_token" -> "开发令牌或应用会话未获授权，请核对令牌并刷新。"
    "revision_conflict", "mutation_conflict", "version_conflict", "publication_conflict" ->
        "后台修订已变化。请先刷新核对；不会自动覆盖。"
    "draft_hash_conflict", "draft_not_ready" -> "草稿尚不可发布或内容已变化，请刷新并重新预览。"
    "generation_unavailable" -> "尚未配置生成模型。仍可使用已安装的小程序。"
    "storage_unavailable" -> "后台存储不可用，未确认保存。"
    "no_offline_bundle" -> "没有已验证的离线资源。请先联网打开。"
    "asset_integrity" -> "资源校验失败，未替换已验证的旧版。"
    "generated_job_limit", "generated_app_limit", "version_limit", "generated_storage_limit" ->
        "已达到生成任务、应用、版本或存储上限。"
    "approval_not_current", "proposal_scope_mismatch" -> "确认已过期或建议不属于当前应用，请重新核对。"
    "generated_not_found" -> "后台找不到这个应用或任务，请刷新。"
    else -> "暂时无法连接或读取，此操作未确认。"
}
