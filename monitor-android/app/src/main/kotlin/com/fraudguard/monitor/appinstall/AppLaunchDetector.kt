package com.fraudguard.monitor.appinstall

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.getSystemService
import com.fraudguard.monitor.data.EventReporter
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel

/**
 * requirements.md 13章: 新規インストールしたアプリの初回起動検知。
 *
 * 他アプリの起動を検知する手段はUsageStatsManager(使用状況アクセス)しかない。
 * これは通常のランタイム権限ではなくユーザーが設定画面で個別に許可する特別なアクセスのため、
 * 未許可でも他の監視機能は動き続けるように、ここでは黙って何もしない設計にしている。
 *
 * 重要な制約: 使用状況の履歴はシステムが定期的に書き出す方式で、起動直後には取得できない。
 * そのため本検知は「通話中にリアルタイムで介入する」用途には使えず、事後の裏付け情報の位置づけ。
 * requirements.md 14.1章の遠隔操作詐欺の判定は、通話中のインストール検知のみで成立させている
 * (起動が取れた場合はサーバー側で文面を強める)。
 */
class AppLaunchDetector(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val eventReporter: EventReporter,
) {
    companion object {
        private const val KEY_WATCHED = "watched_packages"

        /** 監視対象1件をSharedPreferencesへ詰めるときの区切り。アプリ名に空白が含まれうるため空白は使えない。 */
        private const val SEPARATOR = "\u0000"

        /**
         * インストール後この時間だけ初回起動を監視する。
         * requirements.md 13章が重視するのは「インストール直後の起動」であり、
         * 何日も後の起動まで追い続けても詐欺の兆候としての意味は薄いため。
         */
        private const val WATCH_WINDOW_MS = 24 * 60 * 60 * 1000L

        /**
         * 問い合わせ終端を現在時刻より先に取るための余裕。
         * 端末の時計が進んでいた履歴があると使用状況の集計バケットが未来日付になるため。
         */
        private const val CLOCK_SKEW_MARGIN_MS = 30L * 24 * 60 * 60 * 1000L
    }

    /** requirements.md 13章: AppInstallScannerが新規インストールを検知した時点で監視対象に加える。 */
    fun watchForLaunch(packageName: String, appName: String) {
        val watched = prefs.getStringSet(KEY_WATCHED, emptySet())!!.toMutableSet()
        watched += "$packageName${SEPARATOR}$appName${SEPARATOR}${System.currentTimeMillis()}"
        prefs.edit().putStringSet(KEY_WATCHED, watched).apply()
    }

    fun isUsageAccessGranted(): Boolean {
        // 「直近の履歴が取れるか」で判定すると、システムの書き出し遅延で許可済みでも空が返り、
        // 許可されていないと誤表示してしまう。AppOpsManagerに直接問い合わせる。
        val appOps = context.getSystemService<AppOpsManager>() ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 監視対象アプリが前面に出ていないかを確認し、出ていれば初回起動として報告する。
     * 通話中(FraudGuardInCallService)・通話終了時・定期同期・画面表示時から呼ばれる。
     */
    suspend fun checkLaunches() {
        val watched = prefs.getStringSet(KEY_WATCHED, emptySet())!!
        if (watched.isEmpty()) return

        val manager = context.getSystemService<UsageStatsManager>() ?: return
        val now = System.currentTimeMillis()
        // 「前回チェック以降」ではなく、監視窓の全体を毎回見る。
        // 使用状況の履歴はシステムが定期的に書き出す方式で直近数十秒は取得できないことがあり、
        // 前回チェック時刻を毎回nowへ進めると、後から書き出された起動イベントを永久に取りこぼす
        // (実機で「起動しているのにevents=0」として発覚)。
        // 報告した監視対象はリストから外すため、範囲が重複しても二重報告にはならない。
        val since = now - WATCH_WINDOW_MS
        // 終了時刻をnowにしない。使用状況の集計バケットは開始時刻で範囲判定されるため、
        // 端末の時計が一度でも進んでいると「開始時刻が未来のバケット」が生まれ、
        // そこに記録された起動イベントがnow終端の問い合わせから永久に漏れる
        // (実機で、集計ファイルが未来日付になっていて検知できない問題として発覚)。
        val until = now + CLOCK_SKEW_MARGIN_MS

        val launchedPackages = mutableSetOf<String>()
        val events = manager.queryEvents(since, until)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                launchedPackages += event.packageName
            }
        }

        val remaining = mutableSetOf<String>()
        for (entry in watched) {
            val parts = entry.split(SEPARATOR)
            if (parts.size != 3) continue
            val (packageName, appName, installedAtText) = parts
            val installedAt = installedAtText.toLongOrNull() ?: continue

            // 監視窓を過ぎたものは追跡をやめる(起動しなかった、で確定)。
            if (now - installedAt > WATCH_WINDOW_MS) continue

            if (packageName in launchedPackages) {
                val minutesSinceInstall = (now - installedAt) / 60_000
                eventReporter.report(
                    type = EventType.APP_LAUNCHED_AFTER_INSTALL,
                    riskLevel = RiskLevel.NOTICE, // 最終判定と相関はサーバー側RiskEngineが行う
                    title = "インストールしたアプリが起動されました",
                    detail = "$appName をインストールから${minutesSinceInstall}分後に起動しました。",
                    metadata = EventMetadata(packageName = packageName, appName = appName),
                )
                // 報告済みなので監視対象から外す(初回起動のみが対象)。
            } else {
                remaining += entry
            }
        }

        prefs.edit().putStringSet(KEY_WATCHED, remaining).apply()
    }
}
