package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.MarkedCallRepository
import com.fraudguard.server.security.CallMarkToken
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * requirements.md 10.3章: Slackの通知から「これは家族の通話」とマークする導線。
 *
 * GETは確認画面を返すだけで、状態を変えるのはPOSTのみ。
 * Slackはチャンネルに貼られたURLをプレビュー生成のために自動で取得するため、
 * GETでマークしてしまうと通知を投稿した瞬間に自分でマークが付いてしまう。
 *
 * トークン自体が認証情報のため、他の家族向けエンドポイントと違いCognito認証は要求しない
 * (詳細と、それを許容する理由はCallMarkTokenのdocを参照)。
 */
fun Route.callMarkRoutes() {
    get("/calls/mark") {
        val token = call.request.queryParameters["token"]
        val payload = token?.let { CallMarkToken.verify(it) }
            ?: return@get call.respondText(
                page("このリンクは無効か、有効期限が切れています。"),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )

        call.respondText(confirmPage(token, payload.callId), ContentType.Text.Html)
    }

    post("/calls/mark") {
        val token = call.receiveParameters()["token"]
        val payload = token?.let { CallMarkToken.verify(it) }
            ?: return@post call.respondText(
                page("このリンクは無効か、有効期限が切れています。"),
                ContentType.Text.Html,
                HttpStatusCode.BadRequest,
            )

        // markedByはnull。Slackのリンクからは、家族の誰が押したかを特定できないため。
        MarkedCallRepository.mark(payload.deviceId, payload.callId, markedBy = null)

        call.respondText(
            page("家族の通話として記録しました。この通話について、これ以上の通知は行いません。"),
            ContentType.Text.Html,
        )
    }
}

private fun confirmPage(token: String, callId: String): String = page(
    body = """
        <p>この通話を<strong>家族の通話</strong>として記録します。</p>
        <p>記録すると、この通話についてはこれ以上の通知を行いません。<br>
        他の通話や他の端末には影響しません。</p>
        <form method="post" action="/calls/mark">
          <input type="hidden" name="token" value="${escape(token)}">
          <button type="submit">家族の通話として記録する</button>
        </form>
        <p class="sub">通話ID: ${escape(callId)}</p>
    """.trimIndent(),
)

private fun page(body: String): String = """
    <!doctype html>
    <html lang="ja">
    <head>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>FraudGuard</title>
      <style>
        body { font-family: system-ui, sans-serif; margin: 0; padding: 24px; line-height: 1.7; }
        button { font-size: 1rem; padding: 12px 20px; margin-top: 8px; }
        .sub { color: #666; font-size: .85rem; }
      </style>
    </head>
    <body>$body</body>
    </html>
""".trimIndent()

private fun escape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
