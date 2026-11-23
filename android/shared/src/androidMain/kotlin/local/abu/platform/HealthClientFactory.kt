package local.abu.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

fun createHealthHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    engine {
        config {
            retryOnConnectionFailure(false)
            followRedirects(false)
            followSslRedirects(false)
            addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp handles 503 + Retry-After: 0 even with connection recovery disabled.
                // Preserve the 503/body, but do not let the engine replay the request.
                if (response.code == 503 && response.header("Retry-After")?.toIntOrNull() == 0) {
                    response.newBuilder().removeHeader("Retry-After").build()
                } else {
                    response
                }
            }
        }
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 3_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 60_000
    }
}
