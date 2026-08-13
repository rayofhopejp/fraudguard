package com.fraudguard.server.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** requirements.md 25章: APIキーは平文保存しない。SHA-256ハッシュのみをDBへ保存する。 */
fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

private val secureRandom = SecureRandom()

fun generateRandomToken(byteLength: Int = 32): String {
    val bytes = ByteArray(byteLength)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
