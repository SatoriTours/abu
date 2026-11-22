package local.abu.data

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

sealed interface HealthResult {
    data object Connected : HealthResult
    data class Failed(val message: String) : HealthResult
}

// The caller owns the HTTP client. This initial endpoint is development-only.
class HealthClient(private val client: HttpClient) {
    suspend fun check(): HealthResult = try {
        withTimeout(8_000) {
            client.prepareGet("http://127.0.0.1:8080/healthz").execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    return@execute HealthResult.Failed("服务返回 HTTP ${response.status.value}")
                }
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(4097)
                var size = 0
                while (size < buffer.size) {
                    val count = channel.readAvailable(buffer, size, buffer.size - size)
                    if (count == -1) break
                    size += count
                }
                if (size > 4096) return@execute HealthResult.Failed("服务响应过大")
                val body = Json.parseToJsonElement(buffer.decodeToString(0, size)) as? JsonObject
                val status = body?.get("status") as? JsonPrimitive
                if (status?.isString == true && status.content == "ok") HealthResult.Connected
                else HealthResult.Failed("服务响应不符合健康检查协议")
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        HealthResult.Failed("无法连接服务，请检查后端和设备端口转发")
    }
}
