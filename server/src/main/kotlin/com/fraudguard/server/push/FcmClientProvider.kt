package com.fraudguard.server.push

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.FileInputStream

/**
 * Application.ktでfcm.serviceAccountPathの設定有無・妥当性に応じて初期化される、遠隔コマンド送信先。
 * サービスアカウントJSONが無い/不正でもサーバー起動はクラッシュさせず、NoopFcmClient(常に失敗扱い)へ
 * フォールバックする(この場合、遠隔コマンドは8.1章[v2]のpendingポーリングのみで配信される)。
 */
object FcmClientProvider {
    @Volatile
    private var instance: FcmClient = NoopFcmClient()

    private const val APP_NAME = "fraudguard"

    fun init(serviceAccountPath: String) {
        instance = try {
            val credentials = GoogleCredentials.fromStream(FileInputStream(serviceAccountPath))
            val options = FirebaseOptions.builder().setCredentials(credentials).build()
            // 同一JVM内で複数回initされても(テスト実行時など)クラッシュしないよう、既存アプリを再利用する。
            val app = FirebaseApp.getApps().find { it.name == APP_NAME }
                ?: FirebaseApp.initializeApp(options, APP_NAME)
            FirebaseFcmClient(app)
        } catch (e: Exception) {
            NoopFcmClient()
        }
    }

    fun get(): FcmClient = instance
}
