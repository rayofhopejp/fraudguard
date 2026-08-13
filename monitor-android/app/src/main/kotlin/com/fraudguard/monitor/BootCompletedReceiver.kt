package com.fraudguard.monitor

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.fraudguard.monitor.call.CallMonitorService

/**
 * requirements.md 30章: Android再起動後も監視が復帰可能であること。
 *
 * 注意: startForegroundService()で起動したサービスは短時間内にstartForeground()を呼ばないと
 * ForegroundServiceDidNotStartInTimeExceptionでアプリごとクラッシュする(実機のログで発生を確認)。
 * CallMonitorService側でstartForeground()を呼ぶよう修正済み。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 未ペアリングの端末で監視サービスを起こしても送信先が無く、常駐通知だけが出て紛らわしい。
        val app = context.applicationContext as? FraudGuardApplication ?: return
        if (!app.pairingRepository.isPaired()) return

        // requirements.md 4.3章[v2]: ROLE_DIALER保持時はFraudGuardInCallServiceが通話を監視するため、
        // 軽量版のCallMonitorServiceは不要(常駐通知が二重に出るのも避ける)。
        val roleHeld = context.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        if (roleHeld) return

        ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
    }
}
