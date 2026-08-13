package com.fraudguard.server.db.repository

import com.fraudguard.server.domain.model.CreateEventRequest
import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.domain.risk.AppRiskCategory
import com.fraudguard.server.domain.risk.CallDirection
import com.fraudguard.server.domain.risk.PhoneNumberClassifier
import com.fraudguard.server.domain.risk.RiskAssessment
import com.fraudguard.server.domain.risk.RiskEngine
import com.fraudguard.server.domain.risk.WhitelistStatus
import com.fraudguard.server.notify.FamilyNotifierProvider
import java.time.Instant
import java.util.UUID

/**
 * requirements.md 33章: RiskEngine(DB非依存の純粋ロジック)とDB層を繋ぐオーケストレーション。
 * イベント受信時に単一イベント判定(7章)でリスクレベルを検証・上書きし、
 * 直近イベント履歴から相関判定(14章)・大量着信判定(7.5章)を行って必要なら追加イベントを生成する。
 * 新規に発生したWARNING以上のイベントのみ、Slack等の家族通知(2.2章)を送る。
 */
object RiskEvaluationService {

    // requirements.md 30章: 同じ相関イベントを短時間に繰り返し出さないための抑制期間。
    private const val CORRELATED_EVENT_DEDUPE_MINUTES = 30L

    suspend fun ingestEvent(request: CreateEventRequest, deviceId: String) {
        val status = resolveWhitelistStatus(deviceId, request.metadata.phoneNumber)
        // requirements.md 10.3章: 家族が「家族の通話」とマークした通話は、以降その通話に関する
        // イベント(通話時間の警告など)を通知しない。電話番号を持たないアプリ内通話に対する、
        // ホワイトリストの代わりの抑制手段。
        val assessment = if (isMarkedFamilyCall(deviceId, request.metadata.callId)) {
            RiskEngine.markedFamilyCallAssessment()
        } else {
            classifySingleEvent(request, status)
        }
        // requirements.md 17章: 家族には「なぜ警告なのか」(判定理由)が要る。サーバー側で
        // リスクレベルを判定し直した場合、その理由をmetadata.reasonへ載せる。
        // detailは端末側が入れた具体的な情報(アプリ名など)を残す方が有用なので上書きしない。
        val finalRequest = if (assessment != null) {
            request.copy(
                riskLevel = assessment.level,
                metadata = request.metadata.copy(reason = assessment.reason),
            )
        } else {
            request
        }

        val inserted = EventRepository.insertIfAbsent(finalRequest, deviceId)
        if (inserted) {
            notifyIfNoteworthy(deviceId, finalRequest)
        }

        evaluateCorrelationAndPersist(deviceId)
    }

    private suspend fun evaluateCorrelationAndPersist(deviceId: String) {
        val recentEvents = EventRepository.listForDevice(deviceId)
        val findings = RiskEngine.evaluateCorrelation(recentEvents)

        for (finding in findings) {
            if (EventRepository.existsRecentOfType(deviceId, finding.type, CORRELATED_EVENT_DEDUPE_MINUTES)) continue

            val correlatedRequest = CreateEventRequest(
                eventId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                type = finding.type,
                riskLevel = finding.riskLevel,
                title = finding.title,
                detail = finding.detail,
                timestamp = Instant.now().toString(),
                metadata = finding.metadata,
            )
            val inserted = EventRepository.insertIfAbsent(correlatedRequest, deviceId)
            if (inserted) {
                notifyIfNoteworthy(deviceId, correlatedRequest)
            }
        }
    }

    /** requirements.md 2.2章, 30章: WARNING以上のみ通知し、アラート疲れ(30章)を避ける。 */
    private suspend fun notifyIfNoteworthy(deviceId: String, request: CreateEventRequest) {
        if (request.riskLevel.ordinal < RiskLevel.WARNING.ordinal) return

        val deviceName = DeviceRepository.findName(deviceId) ?: deviceId
        val event = Event(
            eventId = request.eventId,
            deviceId = deviceId,
            type = request.type,
            riskLevel = request.riskLevel,
            title = request.title,
            detail = request.detail,
            timestamp = request.timestamp,
            metadata = request.metadata,
        )
        FamilyNotifierProvider.get().notify(event, deviceName)
    }

    private suspend fun isMarkedFamilyCall(deviceId: String, callId: String?): Boolean =
        callId != null && MarkedCallRepository.isMarked(deviceId, callId)

    private fun classifySingleEvent(request: CreateEventRequest, status: WhitelistStatus): RiskAssessment? =
        when (request.type) {
            // requirements.md 10.3章: sourceAppがあればLINE等のアプリ内通話。電話番号を持たないため
            // 番号による判定はできず、RiskEngine側でアプリ内通話として別扱いになる。
            EventType.CALL_INCOMING ->
                RiskEngine.evaluateCall(
                    CallDirection.INCOMING,
                    PhoneNumberClassifier.classify(request.metadata.phoneNumber),
                    status,
                    request.metadata.sourceApp,
                )
            EventType.CALL_OUTGOING ->
                RiskEngine.evaluateCall(
                    CallDirection.OUTGOING,
                    PhoneNumberClassifier.classify(request.metadata.phoneNumber),
                    status,
                    request.metadata.sourceApp,
                )
            EventType.CALL_LONG_DURATION ->
                request.metadata.durationSeconds?.let {
                    RiskEngine.evaluateCallDuration(it, status, request.metadata.sourceApp)
                }
            EventType.APP_REMOTE_CONTROL_INSTALLED ->
                RiskEngine.evaluateAppInstall(AppRiskCategory.REMOTE_CONTROL)
            EventType.APP_MESSAGING_INSTALLED ->
                RiskEngine.evaluateAppInstall(AppRiskCategory.MESSAGING)
            EventType.APP_INSTALLED ->
                RiskEngine.evaluateAppInstall(AppRiskCategory.NORMAL)
            EventType.SMS_RECEIVED ->
                RiskEngine.evaluateSms(request.metadata.messageBody)
            // NOTIFICATION_OBSERVED, APP_LAUNCHED_AFTER_INSTALL, CALL_BURST_FOREIGN, CORRELATED_RISK,
            // DEVICE_HEALTH は単一イベント判定の対象外(Monitor側の判定を尊重するか、相関判定側で扱う)。
            else -> null
        }

    private suspend fun resolveWhitelistStatus(deviceId: String, phoneNumber: String?): WhitelistStatus {
        if (phoneNumber == null) return WhitelistStatus(isWhitelisted = false, isBlacklisted = false)

        val classification = PhoneNumberClassifier.classify(phoneNumber)
        val e164 = (classification as? PhoneNumberClassifier.Classification.Valid)?.e164 ?: phoneNumber

        return WhitelistStatus(
            isWhitelisted = WhitelistRepository.isWhitelisted(deviceId, e164),
            isBlacklisted = BlacklistRepository.isBlacklisted(deviceId, e164),
        )
    }
}
