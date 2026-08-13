package com.fraudguard.monitor.call

import com.fraudguard.monitor.data.EventSink
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * requirements.md 10.3章: LINE等のアプリ内通話を通知経由で追跡する部分の検証。
 * 実機では、Telecomが通話開始から数秒で通話を手放してしまうことが分かっているため、
 * 「Telecomが報告した通話を通知側が引き継ぐ」経路が正しく動くことが要になる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppCallRegistryTest {

    private val line = "jp.naver.line.android"

    private class RecordingEventReporter : EventSink {
        val reported = mutableListOf<Triple<EventType, RiskLevel, EventMetadata>>()
        fun ofType(type: EventType) = reported.filter { it.first == type }
        override suspend fun report(
            type: EventType,
            riskLevel: RiskLevel,
            title: String,
            detail: String,
            metadata: EventMetadata,
        ) {
            reported += Triple(type, riskLevel, metadata)
        }
    }

    @Test
    fun `a call detected only by notification is reported with its source app`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)
        scope.advanceUntilIdle()

        val (_, _, metadata) = reporter.ofType(EventType.CALL_INCOMING).single()
        assertEquals(line, metadata.sourceApp)
    }

    /** Telecom経由で既に報告済みの通話は、通知側で二重に報告しないこと。 */
    @Test
    fun `a call already reported via telecom is adopted instead of reported again`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        val call = registry.onOngoingCallNotification(
            line,
            System.currentTimeMillis(),
            null,
            adoptCallId = "telecom-call-id",
        )
        scope.advanceUntilIdle()

        assertEquals("telecom-call-id", call.callId)
        assertTrue(
            reporter.ofType(EventType.CALL_INCOMING).isEmpty(),
            "Telecom側が報告済みなので通知側は通話開始を報告しないこと",
        )
        assertEquals(setOf("telecom-call-id"), registry.activeCallIds())
    }

    /** 通知は通話中に何度も更新される。そのたびに新しい通話として扱わないこと。 */
    @Test
    fun `notification updates during a call do not start a second call`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)
        val startedAt = System.currentTimeMillis()

        val first = registry.onOngoingCallNotification(line, startedAt, null, adoptCallId = null)
        val second = registry.onOngoingCallNotification(line, startedAt, null, adoptCallId = null)
        scope.advanceUntilIdle()

        assertEquals(first.callId, second.callId)
        assertEquals(1, reporter.ofType(EventType.CALL_INCOMING).size)
    }

    /**
     * requirements.md 7.4章: アプリ内通話でも通話時間の警告が出ること。
     * Telecomは数秒で通話を手放すため、この経路が無いとLINE通話では閾値に一度も到達しない。
     */
    @Test
    fun `long call warnings fire for an app internal call`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)
        scope.advanceUntilIdle()

        val durations = reporter.ofType(EventType.CALL_LONG_DURATION).map { it.third.durationSeconds }
        assertEquals(listOf(180L, 300L, 600L), durations)
    }

    /** 通話が終わった後に、残っていた閾値の警告が飛ばないこと。 */
    @Test
    fun `long call warnings stop once the call ends`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)
        registry.onCallEnded(line)
        scope.advanceUntilIdle()

        assertTrue(reporter.ofType(EventType.CALL_LONG_DURATION).isEmpty())
    }

    @Test
    fun `a call is no longer active once its notification is removed`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)
        assertTrue(registry.hasActiveCall())

        registry.onCallEnded(line)
        assertFalse(registry.hasActiveCall())
        assertTrue(registry.activeCallIds().isEmpty())
    }

    /** 切断手段(CallStyleのhangUpIntent)を持たない通話は、切れたと偽らないこと。 */
    @Test
    fun `a call without a hang up intent cannot be disconnected`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        val call = registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)

        assertFalse(registry.hangUp(call.callId))
        assertFalse(registry.hangUp("unknown-call-id"))
    }

    /** 通話ごとにcallIdが変わること(前の通話のIDを名乗ると誤った通話を切ってしまう)。 */
    @Test
    fun `a later call gets its own call id`() = runTest {
        val reporter = RecordingEventReporter()
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val registry = AppCallRegistry(reporter, scope)

        val first = registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)
        registry.onCallEnded(line)
        val second = registry.onOngoingCallNotification(line, System.currentTimeMillis(), null, adoptCallId = null)
        scope.advanceUntilIdle()

        assertNotEquals(first.callId, second.callId)
    }
}
