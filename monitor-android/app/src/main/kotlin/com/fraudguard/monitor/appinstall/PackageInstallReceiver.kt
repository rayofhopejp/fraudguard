package com.fraudguard.monitor.appinstall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * requirements.md 11章, 12章: 新規アプリインストール監視・リスク分類。
 * ACTION_PACKAGE_ADDED は EXTRA_REPLACING=true の場合アップデートなので除外する
 * (requirements.md 11章「アプリアップデートは原則として新規インストール扱いしない」)。
 *
 * TODO: 12.3章の遠隔操作アプリ判定(パッケージ名リスト → 将来的にカテゴリ・署名判定に拡張)、
 *       13章の初回起動検知との連携。
 */
class PackageInstallReceiver : BroadcastReceiver() {

    private val remoteControlPackages = setOf(
        "com.anydesk.anydeskandroid",
        "com.teamviewer.quicksupport.market",
        "com.rsupport.rs.activity.remote.ui",
        "com.sand.airdroid",
    )

    private val messagingPackages = setOf(
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.whatsapp",
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return // アップデートは対象外

        val packageName = intent.data?.schemeSpecificPart ?: return
        val riskCategory = when (packageName) {
            in remoteControlPackages -> RiskCategory.REMOTE_CONTROL // requirements.md 15章: CRITICAL
            in messagingPackages -> RiskCategory.MESSAGING // WARNING
            else -> RiskCategory.NORMAL // INFO/NORMAL
        }

        val pm = context.packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

        // TODO: APP_MESSAGING_INSTALLED / APP_REMOTE_CONTROL_INSTALLED イベント生成 → EventRepositoryへ記録
        // TODO: 13章「インストールから数分以内の初回起動」検知のため、インストール時刻を記録しておく
    }

    enum class RiskCategory { NORMAL, MESSAGING, REMOTE_CONTROL }
}
