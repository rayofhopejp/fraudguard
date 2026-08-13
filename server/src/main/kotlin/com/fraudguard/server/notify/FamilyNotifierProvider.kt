package com.fraudguard.server.notify

/** Application.ktでSLACK_WEBHOOK_URLの設定有無に応じて初期化される、家族通知の送信先。 */
object FamilyNotifierProvider {
    @Volatile
    private var instance: FamilyNotifier = NoopFamilyNotifier()

    fun init(slackWebhookUrl: String) {
        instance = if (slackWebhookUrl.isNotBlank()) SlackNotifier(slackWebhookUrl) else NoopFamilyNotifier()
    }

    fun get(): FamilyNotifier = instance
}
