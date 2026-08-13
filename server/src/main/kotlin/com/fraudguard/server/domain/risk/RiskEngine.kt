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

    /**
     * requirements.md 7.1〜7.3, 7.6章: 着信/発信の単一イベント判定。
     *
     * `sourceApp` はLINE等のアプリ内通話(10.3章)の発生元パッケージ名。この場合は電話番号が存在せず、
     * 相手を識別する手段が無いため、番号による判定(ホワイトリスト・国内/海外)は一切成立しない。
     */
    fun evaluateCall(
        direction: CallDirection,
        classification: PhoneNumberClassifier.Classification,
        status: WhitelistStatus,
        sourceApp: String? = null,
    ): RiskAssessment {
        if (sourceApp != null) return appCallAssessment(sourceApp)
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

    /**
     * requirements.md 7.4章: ホワイトリスト外番号との長時間通話。
     *
     * 「警告に当たらない」場合もINFOとして明示的に返す(nullを返さない)。nullだと
     * RiskEvaluationService側で「サーバーは判断しない=端末の申告値を採用」という意味になり、
     * ホワイトリスト登録済みの番号との長電話でも端末が付けたWARNINGのまま家族へ通知されてしまう
     * (6章でホワイトリストは信頼済みと定めているため、これは誤警報になる)。
     */
    fun evaluateCallDuration(
        durationSeconds: Long,
        status: WhitelistStatus,
        sourceApp: String? = null,
    ): RiskAssessment {
        if (sourceApp != null) return appCallAssessment(sourceApp)
        if (status.isBlacklisted) return RiskAssessment(RiskLevel.CRITICAL, "ブラックリスト登録済みの番号との通話です")
        if (status.isWhitelisted) return RiskAssessment(RiskLevel.INFO, "ホワイトリスト登録済みの番号との通話です")
        if (durationSeconds < LONG_CALL_THRESHOLD_SECONDS) {
            return RiskAssessment(RiskLevel.NOTICE, "通話中です")
        }

        val minutes = durationSeconds / 60
        return RiskAssessment(RiskLevel.WARNING, "未登録の番号と${minutes}分以上通話しています")
    }

    /**
     * requirements.md 10.3章: LINE等のアプリ内通話に対する判定。
     *
     * アプリ内通話は相手を電話番号で識別できないため、6章のホワイトリスト(番号単位)が使えず、
     * 「未登録だから危険」とも「登録済みだから安全」とも言えない。
     * それでも既定では通知する。詐欺の側がLINEへ誘導してくるのが実際の手口であり、
     * 相手が分からないことを理由に黙るのは、最も知らせるべき通話を見逃すことになるため。
     *
     * 代わりに、家族が通知から「これは家族の通話」とマークすればその通話の以降の通知は止まる
     * (RiskEvaluationServiceがMarkedCallRepositoryを見て判定を下げる)。
     * 番号を持たない通話に対する、ホワイトリストの代わりの抑制手段という位置づけ。
     */
    private fun appCallAssessment(sourceApp: String): RiskAssessment =
        RiskAssessment(RiskLevel.WARNING, "${appLabel(sourceApp)}のアプリ内通話です(相手は特定できません)")

    /** requirements.md 10.3章: 家族が「家族の通話」とマークした通話。以降は通知しない。 */
    fun markedFamilyCallAssessment(): RiskAssessment =
        RiskAssessment(RiskLevel.INFO, "家族の通話としてマーク済みです")

    private fun appLabel(packageName: String): String = when (packageName) {
        "jp.naver.line.android" -> "LINE"
        "org.telegram.messenger" -> "Telegram"
        "org.thoughtcrime.securesms" -> "Signal"
        "com.whatsapp" -> "WhatsApp"
        else -> packageName
    }

    /**
     * requirements.md 9.1章: SMS本文の危険語判定。
     * 通話との相関(14.3章)が無くても、危険語を含むSMS単体で家族へ知らせる価値があるため
     * WARNINGへ引き上げる(9章「取得したSMSを家族へ通知する」と30章のアラート疲れ対策の折衷。
     * 危険語を含まない日常的なSMSはNOTICEに留め、Slack通知は行わない)。
     */
    fun evaluateSms(messageBody: String?): RiskAssessment {
        val matched = messageBody?.let { body -> DANGEROUS_SMS_KEYWORDS.filter { body.contains(it) } }.orEmpty()
        return if (matched.isEmpty()) {
            RiskAssessment(RiskLevel.NOTICE, "SMSを受信しました")
        } else {
            RiskAssessment(RiskLevel.WARNING, "詐欺でよく使われる語句を含むSMSを受信しました(${matched.joinToString("、")})")
        }
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

    /**
     * requirements.md 14.1章: 通話 → 遠隔操作アプリ導入、で「遠隔操作詐欺の可能性が非常に高い」。
     *
     * 当初は「導入 → 直後起動」まで揃うことを条件にしていたが、起動検知(UsageStatsManager)は
     * システムが使用状況を書き出すまで数分〜の遅れがあり、通話中というまさに介入したい時間帯に
     * 間に合わないことが実機で分かった。通話中に遠隔操作アプリを入れさせられた時点で十分に危険なため、
     * インストールだけで CRITICAL とし、起動が確認できた場合は文面を強める運用にしている。
     */
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

        return CorrelatedFinding(
            type = EventType.CORRELATED_RISK,
            riskLevel = RiskLevel.CRITICAL,
            title = "遠隔操作詐欺の可能性が非常に高い",
            detail = if (launchedMatchingInstall) {
                "未登録番号との通話の後、遠隔操作アプリがインストールされ直後に起動しました。"
            } else {
                "未登録番号との通話の最中に、遠隔操作アプリがインストールされました。"
            },
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
