package com.fraudguard.monitor.appinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fraudguard.monitor.FraudGuardApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * requirements.md 11章: 新規アプリインストール検知の「速い経路」。
 *
 * このブロードキャストは実機(Pixel 7 Pro / Android 16)では届かないことを確認している。
 * Android 8以降の暗黙的ブロードキャスト制限やOEM差の影響を受けるため、検知の正は
 * AppInstallScanner(firstInstallTimeの差分スキャン)側に置き、ここはスキャンの起動のみを行う。
 *
 * 自前で報告せずスキャナに委ねることで、届いた場合と定期スキャンとで
 * 二重に報告されることが原理的に起きない(lastScanAtが一元的に進むため)。
 */
class PackageInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // アップデートは対象外

        val app = context.applicationContext as? FraudGuardApplication ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.appInstallScanner.scan()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
