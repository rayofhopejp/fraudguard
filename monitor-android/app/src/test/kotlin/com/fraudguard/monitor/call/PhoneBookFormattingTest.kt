package com.fraudguard.monitor.call

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals

/** requirements.md 4.3章[v2]: 通話履歴の表示。日付の出し方と、かけ直せない履歴の扱い。 */
class PhoneBookFormattingTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { set(year, month - 1, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }
            .timeInMillis

    /** 今日の通話は時刻だけでよい。日付まで出すと一覧が読みにくくなる。 */
    @Test
    fun `a call from today shows only the time`() {
        val now = at(2026, 8, 14, 15, 30)
        assertEquals("09:05", formatCallTimestamp(at(2026, 8, 14, 9, 5), now))
    }

    @Test
    fun `an older call shows the date as well`() {
        val now = at(2026, 8, 14, 15, 30)
        assertEquals("8月13日 22:47", formatCallTimestamp(at(2026, 8, 13, 22, 47), now))
    }

    @Test
    fun `directions are labelled in Japanese`() {
        assertEquals("着信", directionLabel(CallHistoryEntry.Direction.INCOMING))
        assertEquals("発信", directionLabel(CallHistoryEntry.Direction.OUTGOING))
        assertEquals("不在着信", directionLabel(CallHistoryEntry.Direction.MISSED))
        assertEquals("通話", directionLabel(CallHistoryEntry.Direction.OTHER))
    }

    /** 非通知の履歴は番号が無くても一覧に出す。押せないことは画面側で担保する。 */
    @Test
    fun `an entry without a number still has something to show`() {
        val entry = CallHistoryEntry(null, null, CallHistoryEntry.Direction.MISSED, 0)
        assertEquals("非通知", entry.label)
    }

    @Test
    fun `a named entry prefers the name over the number`() {
        val entry = CallHistoryEntry("09012345678", "娘", CallHistoryEntry.Direction.INCOMING, 0)
        assertEquals("娘", entry.label)
    }
}
