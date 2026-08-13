package com.fraudguard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        // 未ペアリング・ROLE_DIALER保持時の判定はサービス側に集約してある。
        CallMonitorService.startIfDialerRoleNotHeld(context)
    }
}
