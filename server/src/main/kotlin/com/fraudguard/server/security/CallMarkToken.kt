package com.fraudguard.server.security

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * requirements.md 10.3章: Slackの通知から「これは家族の通話」とマークするためのトークン。
 *
 * 家族はSlackで通知を受け取るが、Slackのボタン(Interactivity)は受信専用のIncoming Webhookでは使えず、
 * Family WebはCognito前提で今は使えない。そのため通知本文にリンクを載せ、そのリンク自体を
 * 認証情報とする。Ed25519の署名付きで、対象の通話1件・短時間のみに限定する。
 *
 * このリンクを持つ者は、その通話1件の通知を止められる。逆に言えばそれ以上のことはできない
 * (他の通話・他の端末には及ばず、監視の停止や設定変更もできない)。
 * 家族用の非公開Slackチャンネルに投稿する前提でこの範囲を許容している。
 */
object CallMarkToken {

    /**
     * 有効期間。通話中に気づいてマークするのが本来の使い方だが、
     * 後から遡って「あれは家族の通話だった」と止めたい場合もあるため数時間は許容する。
     */
    private const val VALIDITY_SECONDS = 6 * 60 * 60L

    data class Payload(val deviceId: String, val callId: String)

    fun issue(deviceId: String, callId: String, now: Instant = Instant.now()): String {
        val body = "$deviceId|$callId|${now.epochSecond + VALIDITY_SECONDS}"
        return encode(body) + "." + CommandKeys.signer.sign(body.toByteArray())
            .let { encode(it) }
    }

    /** 署名・有効期限ともに正しい場合のみPayloadを返す。 */
    fun verify(token: String, now: Instant = Instant.now()): Payload? {
        val parts = token.split('.')
        if (parts.size != 2) return null

        val body = runCatching { String(decode(parts[0])) }.getOrNull() ?: return null
        val signature = runCatching { String(decode(parts[1])) }.getOrNull() ?: return null

        // Ed25519の署名は決定的なので、署名し直した結果と比較すれば公開鍵を持ち出さずに検証できる。
        val expected = runCatching { CommandKeys.signer.sign(body.toByteArray()) }.getOrNull() ?: return null
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) return null

        val fields = body.split('|')
        if (fields.size != 3) return null
        val expiresAt = fields[2].toLongOrNull() ?: return null
        if (now.epochSecond > expiresAt) return null

        return Payload(deviceId = fields[0], callId = fields[1])
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
