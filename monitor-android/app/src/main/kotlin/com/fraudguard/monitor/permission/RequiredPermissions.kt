package com.fraudguard.monitor.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * requirements.md 34.5章: ペアリング完了直後に要求する各種ランタイム権限。
 * 通知アクセス(NotificationListenerService, 10.2章)はランタイム権限ダイアログの対象外のため、
 * 設定画面への誘導と、画面復帰時の再チェックが別途必要になる。
 */
object RequiredPermissions {

    /** requirements.md 4章: 通話監視・9章: SMS監視 に必要な権限一覧(端末のAPIレベルに応じて変わる)。 */
    fun runtimePermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        )
        // requirements.md 16章: Android 13(API 33)以降はPush通知表示にも許可が要る。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions
    }

    fun missingRuntimePermissions(context: Context): List<String> =
        runtimePermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** requirements.md 10.2章, 35章[v2]: 通知アクセスは設定画面での手動許可が必要(自動申請不可)。 */
    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** requirements.md 34.5章: 画面上に表示する、各権限のユーザー向け説明。 */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.READ_PHONE_STATE -> "通話の状態(着信/発信の検知に使用します)"
        Manifest.permission.READ_CALL_LOG -> "通話履歴(電話番号の取得に使用します)"
        Manifest.permission.RECEIVE_SMS -> "SMSの受信(詐欺兆候のあるSMSを検知します)"
        Manifest.permission.READ_SMS -> "SMSの読み取り(詐欺兆候のあるSMSを検知します)"
        Manifest.permission.POST_NOTIFICATIONS -> "通知の表示(監視状態のお知らせに使用します)"
        else -> permission
    }
}
