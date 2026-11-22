package local.abu.data

import java.security.MessageDigest

internal actual fun appSha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
