package com.fraudguard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.fraudguard.monitor.call.CallMonitorService

/** requirements.md 30章: Android再起動後も監視が復帰可能であること。 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // TODO: ペアリング済みの場合のみ起動する(PairingRepositoryで判定)
        ContextCompat.startForegroundService(context, Intent(context, CallMonitorService::class.java))
    }
}
