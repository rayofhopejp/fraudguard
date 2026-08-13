package com.fraudguard.server.plugins

import com.auth0.jwk.JwkProviderBuilder
import com.fraudguard.server.db.repository.DeviceAuthRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.jwt
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 2種類の認証を提供する:
 *  - "family-auth" : 家族ユーザー(Family Web)向け。Cognito発行のJWTを検証し、FamilyUserPrincipalへ解決する。
 *  - "device-auth"  : 監視端末(Monitor)向け。ペアリング時に発行したAPIキーをBearerで検証し、DevicePrincipalへ解決する。
 *
 * requirements.md 25章: 監視端末ごとの認証・家族ユーザー認証を分離すること。
 */
fun Application.configureSecurity() {
    val cognitoIssuer = environment.config.propertyOrNull("auth.cognitoIssuer")?.getString().orEmpty()
    val cognitoAudience = environment.config.propertyOrNull("auth.cognitoAudience")?.getString().orEmpty()

    // COGNITO_ISSUER未設定(ローカル開発・テスト等)でもルーティング構築時に落ちないよう、
    // "family-auth" プロバイダは常に登録する。未設定時はダミーのissuerを使うため、
    // JWKS解決に失敗して全リクエストが401になるだけで、起動自体はクラッシュしない。
    val effectiveIssuer = cognitoIssuer.ifBlank { "https://unconfigured.invalid" }

    install(Authentication) {
        val jwkProvider = JwkProviderBuilder(URL("$effectiveIssuer/.well-known/jwks.json"))
            .cached(10, 24, TimeUnit.HOURS)
            .build()

        jwt("family-auth") {
            verifier(jwkProvider, effectiveIssuer)
            validate { credential ->
                if (cognitoAudience.isNotBlank() && !credential.payload.audience.contains(cognitoAudience)) {
                    return@validate null
                }
                val sub = credential.payload.subject ?: return@validate null
                val email = credential.payload.getClaim("email")?.asString() ?: "$sub@unknown.invalid"
                FamilyUserRepository.resolveOrCreate(cognitoSub = sub, email = email)
            }
        }

        bearer("device-auth") {
            realm = "FraudGuard Monitor"
            authenticate { credential ->
                DeviceAuthRepository.validate(credential.token)
            }
        }
    }
}
