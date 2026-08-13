package com.fraudguard.server.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.cors.routing.CORS

/**
 * requirements.md 23章: Family WebはAPIサーバーと別ドメインで動くため、
 * ブラウザからのAPI呼び出し(遠隔切断・確認済み操作など)にはCORSの許可が要る。
 *
 * 許可するオリジンは環境変数で与える。ワイルドカードは使わない。
 * このAPIは監視対象者の通話履歴とSMS本文を扱い、遠隔で通話を切れるため、
 * 任意のサイトのJavaScriptから叩ける状態にしてはならない(requirements.md 25章)。
 *
 * 未設定の場合はCORSを一切有効にしない。ブラウザ以外(Monitorアプリ、サーバー間)の
 * 呼び出しはCORSの対象外なので、未設定でも監視機能そのものには影響しない。
 */
fun Application.configureCors() {
    val origin = environment.config.propertyOrNull("familyWebOrigin")?.getString().orEmpty()
    if (origin.isBlank()) {
        log.warn("FAMILY_WEB_ORIGIN not set; the Family Web will not be able to call this API from a browser")
        return
    }

    val url = runCatching { java.net.URI(origin) }.getOrNull()
    val host = url?.host
    if (host.isNullOrBlank()) {
        log.error("FAMILY_WEB_ORIGIN is not a valid URL, CORS is disabled: check the deployment configuration")
        return
    }

    install(CORS) {
        allowHost(host, schemes = listOf(url.scheme ?: "https"))
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        // 認証はAuthorizationヘッダのJWTで行う。Cookieは使わないためallowCredentialsは有効にしない。
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
    }
    log.info("CORS enabled for $origin")
}
