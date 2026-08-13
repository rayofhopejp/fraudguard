package com.fraudguard.server.domain.risk

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import java.time.Duration
import java.time.Instant

enum class CallDirection { INCOMING, OUTGOING }

data class WhitelistStatus(val isWhitelisted: Boolean, val isBlacklisted: Boolean)

enum class AppRiskCategory { NORMAL, MESSAGING, REMOTE_CONTROL }

data class RiskAssessment(val level: RiskLevel, val reason: String)

data class CorrelatedFinding(
    val type: EventType,
    val riskLevel: RiskLevel,
    val title: String,
    val detail: String,
    val metadata: EventMetadata = EventMetadata(),
)

/**
 * requirements.md 33章: 電話・SMS・通知・アプリインストールを最終的に集約する共通RiskEngine。
 * DB/IOに依存しない純粋関数として実装し(テストしやすさ・端末側との判定ロジック共有を優先)、
 * ホワイトリスト照会や永続化はDB層(db.repository.RiskEvaluationService)側の責務とする。
 */
object RiskEngine {

    // requirements.md 7.4章[v2]: 3分(180秒)で最初の警告。将来的に閾値を設定可能にする(30章)。
    private const val LONG_CALL_THRESHOLD_SECONDS = 180L

    // requirements.md 7.5章: 10分以内に3件以上で大量着信とみなす。
    private const val BURST_WINDOW_MINUTES = 10L
    private const val BURST_THRESHOLD_COUNT = 3

    // requirements.md 14章: 相関判定の対象とする直近イベントの時間窓。
    private const val CORRELATION_WINDOW_MINUTES = 30L

    // requirements.md 9.1章: SMS詐欺兆候のキーワード例。
    private val DANGEROUS_SMS_KEYWORDS = listOf(
        "ATM", "還付金", "振り込み", "振込", "暗証番号", "口座", "キャッシュカード",
        "コンビニ", "電子マネー", "投資", "仮想通貨", "遠隔操作", "アプリを入れて",
    )

    /** requirements.md 7.1〜7.3, 7.6章: 着信/発信の単一イベント判定。 */
    fun evaluateCall(
        direction: CallDirection,
        classification: PhoneNumberClassifier.Classification,
        status: WhitelistStatus,
    ): RiskAssessment {
        if (status.isBlacklisted) {
            return RiskAssessment(RiskLevel.CRITICAL, "ブラックリスト登録済みの番号です")
        }
        if (classification is PhoneNumberClassifier.Classification.Invalid) {
            return RiskAssessment(RiskLevel.WARNING, "電話番号の形式が不正です") // 7.6章
        }
        if (status.isWhitelisted) {
            return RiskAssessment(RiskLevel.INFO, "ホワイトリスト登録済みの番号です")
        }

        val valid = classification as PhoneNumberClassifier.Classification.Valid
        return when {
            valid.isDomestic && direction == CallDirection.INCOMING ->
                RiskAssessment(RiskLevel.NOTICE, "未登録の国内番号から着信がありました") // 15章 NOTICE
            valid.isDomestic && direction == CallDirection.OUTGOING ->
                RiskAssessment(RiskLevel.WARNING, "未登録の国内番号へ発信しました") // 7.3章
            !valid.isDomestic && direction == CallDirection.INCOMING ->
                RiskAssessment(RiskLevel.WARNING, "海外番号から着信がありました") // 7.1章
            else -> // !isDomestic && OUTGOING
                RiskAssessment(RiskLevel.CRITICAL, "海外番号へ発信しました") // 7.2章
        }
    }

    /** requirements.md 7.4章: ホワイトリスト外番号との長時間通話。180秒未満または対象外ならnull。 */
    fun evaluateCallDuration(
        durationSeconds: Long,
        status: WhitelistStatus,
    ): RiskAssessment? {
        if (status.isWhitelisted) return null
        if (status.isBlacklisted) return RiskAssessment(RiskLevel.CRITICAL, "ブラックリスト登録済みの番号との通話です")
        if (durationSeconds < LONG_CALL_THRESHOLD_SECONDS) return null

        val minutes = durationSeconds / 60
        return RiskAssessment(RiskLevel.WARNING, "未登録の番号と${minutes}分以上通話しています")
    }

    /** requirements.md 12章: 新規アプリのリスク分類。 */
    fun evaluateAppInstall(category: AppRiskCategory): RiskAssessment = when (category) {
        AppRiskCategory.NORMAL -> RiskAssessment(RiskLevel.INFO, "一般アプリの新規インストール")
        AppRiskCategory.MESSAGING -> RiskAssessment(RiskLevel.WARNING, "メッセージングアプリの新規インストール")
        AppRiskCategory.REMOTE_CONTROL -> RiskAssessment(RiskLevel.CRITICAL, "遠隔操作アプリの新規インストール")
    }

    /**
     * requirements.md 14章, 7.5章: 直近イベント履歴から相関パターン・大量着信を検出する。
     * `recentEvents` は呼び出し側が対象デバイスの直近イベントを時系列で渡す想定
     * (db.repository側でCORRELATION_WINDOW_MINUTES分を目安に絞り込む)。
     */
    fun evaluateCorrelation(recentEvents: List<Event>, now: Instant = Instant.now()): List<CorrelatedFinding> {
        val window = recentEvents.filter {
            val ts = runCatching { Instant.parse(it.timestamp) }.getOrNull() ?: return@filter false
            Duration.between(ts, now).toMinutes() <= CORRELATION_WINDOW_MINUTES
        }

        val findings = mutableListOf<CorrelatedFinding>()

        evaluateRemoteControlPattern(window)?.let { findings += it } // 14.1章
        evaluateMessagingLurePattern(window)?.let { findings += it } // 14.2章
        evaluateSmsDuringCallPattern(window)?.let { findings += it } // 14.3章
        evaluateBurstForeignCalls(window, now)?.let { findings += it } // 7.5章

        return findings
    }

    private fun hasNonWhitelistedCallActivity(events: List<Event>): Boolean =
        events.any {
            it.type in setOf(EventType.CALL_INCOMING, EventType.CALL_OUTGOING, EventType.CALL_LONG_DURATION) &&
                it.riskLevel != RiskLevel.INFO
        }

    /** requirements.md 14.1章: 通話 → 遠隔操作アプリ導入 → 直後起動、で「遠隔操作詐欺の可能性が非常に高い」。 */
    private fun evaluateRemoteControlPattern(events: List<Event>): CorrelatedFinding? {
        if (!hasNonWhitelistedCallActivity(events)) return null

        val installedPackages = events
            .filter { it.type == EventType.APP_REMOTE_CONTROL_INSTALLED }
            .mapNotNull { it.metadata.packageName }
            .toSet()
        if (installedPackages.isEmpty()) return null

        val launchedMatchingInstall = events.any {
            it.type == EventType.APP_LAUNCHED_AFTER_INSTALL && it.metadata.packageName in installedPackages
        }
        if (!launchedMatchingInstall) return null

        return CorrelatedFinding(
            type = EventType.CORRELATED_RISK,
            riskLevel = RiskLevel.CRITICAL,
            title = "遠隔操作詐欺の可能性が非常に高い",
            detail = "未登録番号との通話の後、遠隔操作アプリがインストールされ直後に起動しました。",
        )
    }

    /** requirements.md 14.2章: 通話 → メッセージングアプリ導入 → 起動 → 通知発生、で誘導の可能性。 */
    private fun evaluateMessagingLurePattern(events: List<Event>): CorrelatedFinding? {
        if (!hasNonWhitelistedCallActivity(events)) return null

        val installedPackages = events
            .filter { it.type == EventType.APP_MESSAGING_INSTALLED }
            .mapNotNull { it.metadata.packageName }
            .toSet()
        if (installedPackages.isEmpty()) return null

        val launched = events.any {
            it.type == EventType.APP_LAUNCHED_AFTER_INSTALL && it.metadata.packageName in installedPackages
        }
        val notified = events.any {
            it.type == EventType.NOTIFICATION_OBSERVED &&
                (it.metadata.packageName in installedPackages || it.metadata.sourceApp in installedPackages)
        }
        if (!launched || !notified) return null

        return CorrelatedFinding(
            type = EventType.CORRELATED_RISK,
            riskLevel = RiskLevel.CRITICAL,
            title = "メッセージアプリへの誘導の可能性",
            detail = "未登録番号との通話の後、新規メッセージングアプリがインストール・起動され通知が発生しました。",
        )
    }

    /** requirements.md 14.3章: 通話中に危険なSMSを受信した場合、リスクレベルを引き上げる。 */
    private fun evaluateSmsDuringCallPattern(events: List<Event>): CorrelatedFinding? {
        if (!hasNonWhitelistedCallActivity(events)) return null

        val dangerousSms = events.firstOrNull { event ->
            event.type == EventType.SMS_RECEIVED &&
                DANGEROUS_SMS_KEYWORDS.any { keyword -> event.metadata.messageBody?.contains(keyword) == true }
        } ?: return null

        return CorrelatedFinding(
            type = EventType.CORRELATED_RISK,
            riskLevel = RiskLevel.CRITICAL,
            title = "詐欺を示唆するSMSと不審な通話が重なっています",
            detail = "未登録番号との通話と同時期に、詐欺でよく使われる語句を含むSMSを受信しました。",
            metadata = dangerousSms.metadata,
        )
    }

    /** requirements.md 7.5章: 海外番号(ホワイトリスト外)から10分以内に3件以上の着信。 */
    private fun evaluateBurstForeignCalls(events: List<Event>, now: Instant): CorrelatedFinding? {
        val foreignIncoming = events.filter { event ->
            if (event.type != EventType.CALL_INCOMING || event.riskLevel == RiskLevel.INFO) return@filter false
            val classification = PhoneNumberClassifier.classify(event.metadata.phoneNumber)
            val isForeign = classification is PhoneNumberClassifier.Classification.Valid && !classification.isDomestic
            if (!isForeign) return@filter false
            val ts = runCatching { Instant.parse(event.timestamp) }.getOrNull() ?: return@filter false
            Duration.between(ts, now).toMinutes() <= BURST_WINDOW_MINUTES
        }

        if (foreignIncoming.size < BURST_THRESHOLD_COUNT) return null

        return CorrelatedFinding(
            type = EventType.CALL_BURST_FOREIGN,
            riskLevel = RiskLevel.CRITICAL,
            title = "海外番号から短時間に複数回の着信を検出しました",
            detail = "${BURST_WINDOW_MINUTES}分以内に${foreignIncoming.size}件の海外番号からの着信がありました。",
        )
    }
}
