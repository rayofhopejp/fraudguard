package com.fraudguard.server.security

import java.io.File
import java.io.StringReader
import java.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.util.io.pem.PemReader

/**
 * requirements.md 8.1章: サーバー全体で1つのEd25519鍵ペアを保持し、遠隔コマンドの署名に使う。
 * ペアリング時に端末へ配布する公開鍵もここから取得する(deployment.md Part A5で生成したPEMを読み込む)。
 */
object CommandKeys {
    @Volatile
    private var privateKeyParams: Ed25519PrivateKeyParameters? = null

    fun init(pemPath: String) {
        privateKeyParams = loadFromPem(pemPath)
    }

    val signer: CommandSigner
        get() = CommandSigner(requireLoaded())

    val publicKeyBase64: String
        get() = Base64.getEncoder().encodeToString(requireLoaded().generatePublicKey().encoded)

    private fun requireLoaded(): Ed25519PrivateKeyParameters =
        privateKeyParams ?: error(
            "CommandKeys.init() has not been called. " +
                "commandSigning.privateKeyPath (application.conf) must point at a valid Ed25519 PKCS#8 PEM file.",
        )

    private fun loadFromPem(path: String): Ed25519PrivateKeyParameters {
        val pemText = File(path).readText()
        val derBytes = PemReader(StringReader(pemText)).readPemObject().content
        val privateKeyInfo = PrivateKeyInfo.getInstance(derBytes)
        return PrivateKeyFactory.createKey(privateKeyInfo) as Ed25519PrivateKeyParameters
    }
}
