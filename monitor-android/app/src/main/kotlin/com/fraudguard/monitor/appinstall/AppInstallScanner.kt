package com.fraudguard.monitor.appinstall

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.fraudguard.monitor.data.EventReporter
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel

/**
 * requirements.md 11章, 12章: 新規アプリインストールの検知。
 *
 * ACTION_PACKAGE_ADDEDのマニフェスト宣言レシーバーは、Android 8以降の暗黙的ブロードキャスト制限や
 * アプリの停止状態・OEM独自の制限によって届かないことがある(実機テストで実際に届かなかった)。
 * 監視の取りこぼしは詐欺検知として致命的なので、`firstInstallTime`の差分スキャンを正とする。
 *
 * `firstInstallTime`はアプリ更新時には変化しないため、
 * requirements.md 11章「アップデートは新規インストール扱いしない」も自然に満たせる。
 */
class AppInstallScanner(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val eventReporter: EventReporter,
    private val launchDetector: AppLaunchDetector,
) {
    companion object {
        private const val KEY_LAST_SCAN_AT = "last_package_scan_at"

        private val REMOTE_CONTROL_PACKAGES = setOf(
            "com.anydesk.anydeskandroid",
            "com.teamviewer.quicksupport.market",
            "com.teamviewer.teamviewer.market.mobile",
            "com.rsupport.rs.activity.remote.ui",
            "com.sand.airdroid",
            "com.carriez.flutter_hbb", // RustDesk
        )

        private val MESSAGING_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.whatsapp",
        )
    }

    /**
     * 前回スキャン以降にインストールされたアプリを検出して報告する。
     * 初回はベースラインを記録するだけで、既存アプリを「新規」として大量報告しない。
     */
    suspend fun scan() {
        val now = System.currentTimeMillis()
        val lastScanAt = prefs.getLong(KEY_LAST_SCAN_AT, -1L)

        if (lastScanAt < 0) {
            prefs.edit().putLong(KEY_LAST_SCAN_AT, now).apply()
            return
        }

        val pm = context.packageManager
        val newlyInstalled = pm.getInstalledPackages(0)
            .filter { it.firstInstallTime > lastScanAt }
            // 自分自身の初回インストールは報告しない(ペアリング直後に自分を通知しても意味がない)。
            .filter { it.packageName != context.packageName }

        for (info in newlyInstalled) {
            val packageName = info.packageName
            val appName = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)

            val (type, riskLevel, title) = classify(packageName)
            eventReporter.report(
                type = type,
                riskLevel = riskLevel,
                title = title,
                detail = "$appName ($packageName)",
                metadata = EventMetadata(packageName = packageName, appName = appName),
            )
            // requirements.md 13章, 14.1章: 「導入直後に起動されたか」が遠隔操作詐欺の決め手になるため、
            // インストールを検知した時点で初回起動の監視対象に加える。
            launchDetector.watchForLaunch(packageName, appName)
        }

        prefs.edit().putLong(KEY_LAST_SCAN_AT, now).apply()
    }

    /**
     * requirements.md 12章: リスク分類。最終的なリスクレベルはサーバー側のRiskEngineが決めるが、
     * どのカテゴリかはパッケージ名を知る端末側でしか判定できないため、イベント種別として表現する。
     */
    private fun classify(packageName: String): Triple<EventType, RiskLevel, String> = when (packageName) {
        in REMOTE_CONTROL_PACKAGES -> Triple(
            EventType.APP_REMOTE_CONTROL_INSTALLED,
            RiskLevel.CRITICAL,
            "遠隔操作アプリがインストールされました",
        )
        in MESSAGING_PACKAGES -> Triple(
            EventType.APP_MESSAGING_INSTALLED,
            RiskLevel.WARNING,
            "メッセージングアプリがインストールされました",
        )
        else -> Triple(
            EventType.APP_INSTALLED,
            RiskLevel.INFO,
            "アプリがインストールされました",
        )
    }
}
