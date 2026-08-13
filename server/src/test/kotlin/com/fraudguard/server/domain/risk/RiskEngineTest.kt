package com.fraudguard.server.domain.risk

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** requirements.md 7章・14章・15章のルール表がそのまま実装されているかを検証する純粋ロジックのテスト(DB不要)。 */
class RiskEngineTest {

    private val whitelisted = WhitelistStatus(isWhitelisted = true, isBlacklisted = false)
    private val notListed = WhitelistStatus(isWhitelisted = false, isBlacklisted = false)
    private val blacklisted = WhitelistStatus(isWhitelisted = false, isBlacklisted = true)

    private fun classify(number: String) = PhoneNumberClassifier.classify(number)

    // --- 7.1〜7.3, 7.6, 15章 ---

    @Test
    fun `foreign incoming call from unlisted number is WARNING`() {
        val result = RiskEngine.evaluateCall(CallDirection.INCOMING, classify("+14155552671"), notListed)
        assertEquals(RiskLevel.WARNING, result.level)
    }

    @Test
    fun `foreign outgoing call to unlisted number is CRITICAL`() {
        val result = RiskEngine.evaluateCall(CallDirection.OUTGOING, classify("+14155552671"), notListed)
        assertEquals(RiskLevel.CRITICAL, result.level)
    }

    @Test
    fun `domestic outgoing call to unlisted number is WARNING`() {
        val result = RiskEngine.evaluateCall(CallDirection.OUTGOING, classify("09012345678"), notListed)
        assertEquals(RiskLevel.WARNING, result.level)
    }

    @Test
    fun `domestic incoming call from unlisted number is NOTICE`() {
        val result = RiskEngine.evaluateCall(CallDirection.INCOMING, classify("09012345678"), notListed)
        assertEquals(RiskLevel.NOTICE, result.level)
    }

    @Test
    fun `whitelisted number is always INFO regardless of direction or country`() {
        assertEquals(RiskLevel.INFO, RiskEngine.evaluateCall(CallDirection.OUTGOING, classify("+14155552671"), whitelisted).level)
        assertEquals(RiskLevel.INFO, RiskEngine.evaluateCall(CallDirection.INCOMING, classify("09012345678"), whitelisted).level)
    }

    @Test
    fun `blacklisted number is always CRITICAL even before other checks`() {
        assertEquals(RiskLevel.CRITICAL, RiskEngine.evaluateCall(CallDirection.INCOMING, classify("09012345678"), blacklisted).level)
    }

    @Test
    fun `unparseable number is WARNING`() {
        val result = RiskEngine.evaluateCall(CallDirection.INCOMING, classify("not-a-number"), notListed)
        assertEquals(RiskLevel.WARNING, result.level)
    }

    /**
     * requirements.md 4.3章[v2]: 番号が取れなかった通話を「番号の形式が不正」と判定してはいけない。
     * 判定理由が事実と食い違ううえ、本人がかけた電話まですべて警告になる。
     */
    @Test
    fun `a call with no number is not treated as a malformed number`() {
        val result = RiskEngine.evaluateCall(
            CallDirection.OUTGOING,
            PhoneNumberClassifier.classify(null),
            notListed,
            hasPhoneNumber = false,
        )
        assertEquals(RiskLevel.NOTICE, result.level)
        assertFalse(result.reason.contains("不正"))
        assertTrue(result.level.ordinal < RiskLevel.WARNING.ordinal, "単体では通知しないこと")
    }

    /** 番号が取れなくても、長電話であること自体は警告に値する。 */
    @Test
    fun `a long call still warns when the number could not be obtained`() {
        assertEquals(RiskLevel.WARNING, RiskEngine.evaluateCallDuration(180, notListed).level)
    }

    // --- 7.4 ---

    /** 端末が報告する最短の経過時間は1分。それ未満は報告として扱わない。 */
    @Test
    fun `call under a minute with unlisted number does not reach warning level`() {
        assertEquals(RiskLevel.NOTICE, RiskEngine.evaluateCallDuration(59, notListed).level)
    }

    /** [v3] 1分の通知が通知閾値(WARNING以上)に届くこと。180秒のままだと1分の報告が黙殺される。 */
    @Test
    fun `long call at one minute with unlisted number is WARNING`() {
        val result = RiskEngine.evaluateCallDuration(60, notListed)
        assertEquals(RiskLevel.WARNING, result.level)
        assertTrue(result.reason.contains("1分"), "何分通話しているかが判定理由に含まれること")
    }

    @Test
    fun `long call at 15 minutes with unlisted number is WARNING`() {
        val result = RiskEngine.evaluateCallDuration(900, notListed)
        assertEquals(RiskLevel.WARNING, result.level)
        assertTrue(result.reason.contains("15分"))
    }

    @Test
    fun `long call with whitelisted number is downgraded to INFO so the family is not alerted`() {
        // nullを返すと端末が申告したWARNINGがそのまま採用されてしまうため、明示的にINFOへ下げる。
        assertEquals(RiskLevel.INFO, RiskEngine.evaluateCallDuration(600, whitelisted).level)
    }

    @Test
    fun `long call with blacklisted number is CRITICAL`() {
        assertEquals(RiskLevel.CRITICAL, RiskEngine.evaluateCallDuration(600, blacklisted).level)
    }

    // --- 9.1章: SMSの危険語判定 ---

    @Test
    fun `sms containing fraud keywords is escalated to WARNING with the matched words as the reason`() {
        val result = RiskEngine.evaluateSms("ATMで還付金の手続きをしてください")
        assertEquals(RiskLevel.WARNING, result.level)
        assertTrue(result.reason.contains("ATM"), "判定理由に一致した語句が含まれること")
        assertTrue(result.reason.contains("還付金"))
    }

    @Test
    fun `harmless sms stays at NOTICE`() {
        assertEquals(RiskLevel.NOTICE, RiskEngine.evaluateSms("今日は何時に帰る?").level)
    }

    @Test
    fun `sms with no body does not crash and stays at NOTICE`() {
        assertEquals(RiskLevel.NOTICE, RiskEngine.evaluateSms(null).level)
    }

    // --- 10.3章: メッセージアプリの通知 ---

    @Test
    fun `notification containing fraud keywords is escalated to WARNING`() {
        val result = RiskEngine.evaluateNotification("口座番号を教えてください。至急、電子マネーを購入してください")
        assertEquals(RiskLevel.WARNING, result.level)
        assertTrue(result.reason.contains("口座"))
        assertTrue(result.reason.contains("電子マネー"))
    }

    /**
     * 本文の無い観測は記録に留める。端末は「最近入れたアプリが通知を出した」ことだけを送ってくる
     * (中身は送らない)ので、単体では警告にせず14.2章の相関判定の材料として使う。
     */
    @Test
    fun `notification without a body stays below the notification threshold`() {
        val result = RiskEngine.evaluateNotification(null)
        assertEquals(RiskLevel.INFO, result.level)
        assertTrue(result.level.ordinal < RiskLevel.WARNING.ordinal)
    }

    @Test
    fun `harmless notification body does not raise a warning`() {
        assertEquals(RiskLevel.INFO, RiskEngine.evaluateNotification("今夜ごはんいる?").level)
    }

    /** requirements.md 14.2章: 通話 → メッセージングアプリ導入 → 起動 → 通知発生。 */
    @Test
    fun `messaging app install followed by launch and a notification correlates`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(900)),
            event(EventType.APP_MESSAGING_INSTALLED, RiskLevel.WARNING, now.minusSeconds(600), packageName = "org.telegram.messenger"),
            event(EventType.APP_LAUNCHED_AFTER_INSTALL, RiskLevel.NOTICE, now.minusSeconds(300), packageName = "org.telegram.messenger"),
            event(EventType.NOTIFICATION_OBSERVED, RiskLevel.INFO, now.minusSeconds(60), packageName = "org.telegram.messenger"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(
            findings.any { it.type == EventType.CORRELATED_RISK && it.title.contains("メッセージアプリ") },
            "14.2章の相関が成立すること",
        )
    }

    // --- 12章 ---

    @Test
    fun `app install risk categories map to the documented levels`() {
        assertEquals(RiskLevel.INFO, RiskEngine.evaluateAppInstall(AppRiskCategory.NORMAL).level)
        assertEquals(RiskLevel.WARNING, RiskEngine.evaluateAppInstall(AppRiskCategory.MESSAGING).level)
        assertEquals(RiskLevel.CRITICAL, RiskEngine.evaluateAppInstall(AppRiskCategory.REMOTE_CONTROL).level)
    }

    // --- 10.3章: LINE等のアプリ内通話 ---

    @Test
    fun `app internal call is notified and not mistaken for an invalid phone number`() {
        val notWhitelisted = WhitelistStatus(isWhitelisted = false, isBlacklisted = false)
        // 電話番号が無いため Invalid になるが、7.6章の「番号の形式が不正」には当たらない。
        val assessment = RiskEngine.evaluateCall(
            direction = CallDirection.INCOMING,
            classification = PhoneNumberClassifier.classify(null),
            status = notWhitelisted,
            sourceApp = "jp.naver.line.android",
        )
        assertEquals(RiskLevel.WARNING, assessment.level)
        assertTrue(assessment.reason.contains("LINE"))
        assertFalse(assessment.reason.contains("不正"))
    }

    @Test
    fun `long app internal call is notified`() {
        val assessment = RiskEngine.evaluateCallDuration(
            durationSeconds = 600,
            status = WhitelistStatus(isWhitelisted = false, isBlacklisted = false),
            sourceApp = "jp.naver.line.android",
        )
        assertEquals(RiskLevel.WARNING, assessment.level)
    }

    /** マーク後はINFOになり、通知閾値(WARNING以上)を下回ること。 */
    @Test
    fun `marked family call falls below the notification threshold`() {
        val assessment = RiskEngine.markedFamilyCallAssessment()
        assertEquals(RiskLevel.INFO, assessment.level)
        assertTrue(assessment.level.ordinal < RiskLevel.WARNING.ordinal)
    }

    /** マークで通知を止めても、他の兆候と重なれば14章で上がること(NOTICE相当でも相関対象)。 */
    @Test
    fun `app internal call still correlates with a remote control app install`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.CALL_INCOMING, RiskLevel.NOTICE, now.minusSeconds(600)),
            event(EventType.APP_REMOTE_CONTROL_INSTALLED, RiskLevel.CRITICAL, now.minusSeconds(300), packageName = "com.anydesk.anydeskandroid"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.any { it.type == EventType.CORRELATED_RISK && it.riskLevel == RiskLevel.CRITICAL })
    }

    // --- 14.1章: 遠隔操作詐欺 ---

    @Test
    fun `call followed by remote control app install and launch triggers CRITICAL correlation`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(600)),
            event(EventType.APP_REMOTE_CONTROL_INSTALLED, RiskLevel.CRITICAL, now.minusSeconds(300), packageName = "com.anydesk.anydeskandroid"),
            event(EventType.APP_LAUNCHED_AFTER_INSTALL, RiskLevel.INFO, now.minusSeconds(60), packageName = "com.anydesk.anydeskandroid"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.any { it.type == EventType.CORRELATED_RISK && it.riskLevel == RiskLevel.CRITICAL })
    }

    /**
     * 起動検知(UsageStatsManager)はシステムの書き出し待ちで通話中には間に合わないため、
     * 通話中のインストールだけで CRITICAL になる必要がある。
     */
    @Test
    fun `call followed by remote control app install alone triggers CRITICAL correlation`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(600)),
            event(EventType.APP_REMOTE_CONTROL_INSTALLED, RiskLevel.CRITICAL, now.minusSeconds(300), packageName = "com.anydesk.anydeskandroid"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        val finding = findings.single { it.type == EventType.CORRELATED_RISK }
        assertEquals(RiskLevel.CRITICAL, finding.riskLevel)
        assertTrue(finding.detail.contains("通話の最中に"))
    }

    @Test
    fun `remote control app install without a preceding suspicious call does not correlate`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.APP_REMOTE_CONTROL_INSTALLED, RiskLevel.CRITICAL, now.minusSeconds(300), packageName = "com.anydesk.anydeskandroid"),
            event(EventType.APP_LAUNCHED_AFTER_INSTALL, RiskLevel.INFO, now.minusSeconds(60), packageName = "com.anydesk.anydeskandroid"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.none { it.type == EventType.CORRELATED_RISK })
    }

    // --- 14.3章: SMSと通話 ---

    @Test
    fun `dangerous SMS keyword during a suspicious call escalates risk`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(120)),
            event(EventType.SMS_RECEIVED, RiskLevel.NOTICE, now.minusSeconds(30), messageBody = "ATMでキャッシュカードの暗証番号を入力してください"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.any { it.type == EventType.CORRELATED_RISK && it.riskLevel == RiskLevel.CRITICAL })
    }

    @Test
    fun `harmless SMS during a suspicious call does not escalate`() {
        val now = Instant.now()
        val events = listOf(
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(120)),
            event(EventType.SMS_RECEIVED, RiskLevel.NOTICE, now.minusSeconds(30), messageBody = "今日の夕飯は何にする?"),
        )
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.none { it.type == EventType.CORRELATED_RISK })
    }

    // --- 7.5章: 海外番号からの大量着信 ---

    @Test
    fun `three foreign incoming calls within 10 minutes trigger a burst finding`() {
        val now = Instant.now()
        val events = (1..3).map {
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(it * 60L), phoneNumber = "+1415555267$it")
        }
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.any { it.type == EventType.CALL_BURST_FOREIGN })
    }

    @Test
    fun `two foreign incoming calls within 10 minutes do not trigger a burst finding`() {
        val now = Instant.now()
        val events = (1..2).map {
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(it * 60L), phoneNumber = "+1415555267$it")
        }
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.none { it.type == EventType.CALL_BURST_FOREIGN })
    }

    @Test
    fun `foreign calls outside the correlation window are ignored`() {
        val now = Instant.now()
        val events = (1..3).map {
            event(EventType.CALL_INCOMING, RiskLevel.WARNING, now.minusSeconds(40 * 60L), phoneNumber = "+1415555267$it")
        }
        val findings = RiskEngine.evaluateCorrelation(events, now)
        assertTrue(findings.isEmpty())
    }

    private fun event(
        type: EventType,
        riskLevel: RiskLevel,
        timestamp: Instant,
        phoneNumber: String? = "+14155552671",
        packageName: String? = null,
        messageBody: String? = null,
    ) = Event(
        eventId = "evt-${timestamp.toEpochMilli()}-${type.name}",
        deviceId = "device-1",
        type = type,
        riskLevel = riskLevel,
        title = "title",
        detail = "detail",
        timestamp = timestamp.toString(),
        metadata = EventMetadata(phoneNumber = phoneNumber, packageName = packageName, messageBody = messageBody),
    )
}
