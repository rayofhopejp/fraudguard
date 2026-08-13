package com.fraudguard.server.push

import com.fraudguard.server.domain.model.RemoteCommand
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * requirements.md 8章[v2]: 遠隔コマンド(通話切断)のFCM即時配信専用。
 * 家族への情報系通知はSlack(notify/SlackNotifier.kt, 要件定義2.2章)で完結させているため、
 * ここでは高優先度data messageの送信のみを扱う。
 *
 * 送信に失敗しても、要件定義8.1章[v2]の GET /devices/{deviceId}/commands/pending ポーリングが
 * フォールバックとして機能するため、呼び出し側は失敗を許容してよい。
 */
interface FcmClient {
    /** @return 送信に成功したか。 */
    suspend fun sendCommandDataMessage(fcmToken: String, command: RemoteCommand): Boolean
}

class FirebaseFcmClient(private val app: FirebaseApp) : FcmClient {
    override suspend fun sendCommandDataMessage(fcmToken: String, command: RemoteCommand): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val message = Message.builder()
                    .setToken(fcmToken)
                    .putData("commandId", command.commandId)
                    .putData("deviceId", command.deviceId)
                    .putData("callId", command.callId)
                    .putData("type", command.type.name)
                    .putData("issuedAt", command.issuedAt)
                    .putData("expiresAt", command.expiresAt)
                    .putData("nonce", command.nonce)
                    .putData("signature", command.signature)
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            // requirements.md 8章: 通話切断は速さが要件のため高優先度で送る。
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build(),
                    )
                    .build()
                FirebaseMessaging.getInstance(app).send(message)
                true
            } catch (e: Exception) {
                false
            }
        }
}

/** Firebaseサービスアカウント未設定/読み込み失敗時のフォールバック。常に失敗を返し、pendingポーリングに委ねる。 */
class NoopFcmClient : FcmClient {
    override suspend fun sendCommandDataMessage(fcmToken: String, command: RemoteCommand): Boolean = false
}
