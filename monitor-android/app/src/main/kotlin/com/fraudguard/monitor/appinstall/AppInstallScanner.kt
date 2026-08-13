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
        private const val KEY_RECENT_INSTALLS = "recent_installs"
        private const val SEPARATOR = "\u0000"

        /**
         * 「最近入れたアプリ」とみなす期間。requirements.md 14.2章の
         * 「通話 → メッセージングアプリ導入 → 起動 → 通知発生」を成立させるために、
         * 通知監視側がこの期間だけ当該アプリの通知を観測対象として報告する。
         */
        private const val RECENT_INSTALL_WINDOW_MS = 24 * 60 * 60 * 1000L

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
            rememberRecentInstall(packageName, now)
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

    /**
     * requirements.md 14.2章: 直近にインストールされたアプリか。
     * 通知監視(FraudGuardNotificationListenerService)が、
     * 「入れさせられたアプリが実際に使われ始めた」ことを観測するために使う。
     */
    fun isRecentlyInstalled(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        return prefs.getStringSet(KEY_RECENT_INSTALLS, emptySet())!!.any { entry ->
            val parts = entry.split(SEPARATOR)
            parts.size == 2 && parts[0] == packageName &&
                (parts[1].toLongOrNull()?.let { now - it <= RECENT_INSTALL_WINDOW_MS } == true)
        }
    }

    private fun rememberRecentInstall(packageName: String, now: Long) {
        val kept = prefs.getStringSet(KEY_RECENT_INSTALLS, emptySet())!!.filter { entry ->
            val parts = entry.split(SEPARATOR)
            parts.size == 2 && (parts[1].toLongOrNull()?.let { now - it <= RECENT_INSTALL_WINDOW_MS } == true)
        }.toMutableSet()
        kept += "$packageName$SEPARATOR$now"
        prefs.edit().putStringSet(KEY_RECENT_INSTALLS, kept).apply()
    }
}
