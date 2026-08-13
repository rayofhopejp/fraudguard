package com.fraudguard.server.security

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * requirements.md 8.1章[v2]: 遠隔コマンドの署名をEd25519(非対称鍵)で行う。
 * サーバーが秘密鍵で署名し、端末はペアリング時に受け取った公開鍵で検証する。
 * HMAC(対称鍵)は、端末側の漏洩が他端末へのなりすましに波及するため採用しない。
 */
class CommandSigner(private val privateKey: Ed25519PrivateKeyParameters) {

    fun sign(canonicalPayload: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(canonicalPayload, 0, canonicalPayload.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    companion object {
        fun verify(publicKey: Ed25519PublicKeyParameters, canonicalPayload: ByteArray, signatureBase64: String): Boolean {
            val signer = Ed25519Signer()
            signer.init(false, publicKey)
            signer.update(canonicalPayload, 0, canonicalPayload.size)
            return signer.verifySignature(Base64.getDecoder().decode(signatureBase64))
        }
    }
}

/**
 * コマンドの署名対象となる正規化ペイロード。フィールドの順序・区切りを固定し、
 * サーバー・端末双方で同一のバイト列を再現できるようにする。
 */
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
