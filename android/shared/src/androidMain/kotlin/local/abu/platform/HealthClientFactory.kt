package local.abu.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

fun createHealthHttpClient(): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 3_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 60_000
    }
}
