package com.fraudguard.monitor.command

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * requirements.md 8.1章[v2]: サーバーがEd25519秘密鍵で署名したコマンドを、
 * ペアリング時に受け取った公開鍵(server/security/CommandSigner.ktと対になる)で検証する。
 */
class CommandSignatureVerifier(serverPublicKeyBase64: String) {

    private val publicKey = Ed25519PublicKeyParameters(Base64.getDecoder().decode(serverPublicKeyBase64), 0)

    fun verify(canonicalPayload: ByteArray, signatureBase64: String): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, publicKey)
        signer.update(canonicalPayload, 0, canonicalPayload.size)
        return signer.verifySignature(Base64.getDecoder().decode(signatureBase64))
    }
}

/** server/security/CommandSigner.kt の canonicalCommandPayload と同一ロジックを維持すること。 */
fun canonicalCommandPayload(
    commandId: String,
    deviceId: String,
    callId: String,
    type: String,
    issuedAt: String,
    expiresAt: String,
    nonce: String,
): ByteArray {
    return listOf(commandId, deviceId, callId, type, issuedAt, expiresAt, nonce)
        .joinToString("|")
        .toByteArray(Charsets.UTF_8)
}
