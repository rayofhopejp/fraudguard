package com.fraudguard.monitor.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** requirements.md 7.4章[v3]: 1分・3分・5分・10分・15分、以降15分おき。 */
class LongCallThresholdsTest {

    @Test
    fun `a phone call is reported from one minute`() {
        val thresholds = longCallThresholdsSeconds(includeFirstMinute = true).take(8).toList()
        assertEquals(listOf(60L, 180L, 300L, 600L, 900L, 1800L, 2700L, 3600L), thresholds)
    }

    /** アプリ内通話は相手を識別できないため、1分では知らせない(10.3章)。 */
    @Test
    fun `an app internal call skips the one minute mark but is otherwise the same`() {
        val thresholds = longCallThresholdsSeconds(includeFirstMinute = false).take(7).toList()
        assertEquals(listOf(180L, 300L, 600L, 900L, 1800L, 2700L, 3600L), thresholds)
    }

    /** 通話が続く限り知らせ続けるため、列は尽きないこと。 */
    @Test
    fun `the sequence keeps going every 15 minutes`() {
        val thresholds = longCallThresholdsSeconds(includeFirstMinute = true).take(100).toList()
        assertEquals(100, thresholds.size)
        assertTrue(thresholds.zipWithNext().all { (a, b) -> b > a }, "経過時間は単調増加であること")
        val tail = thresholds.takeLast(10)
        assertTrue(tail.zipWithNext().all { (a, b) -> b - a == 900L }, "終盤は15分間隔であること")
    }

    /** requirements.md 7.1章: 海外番号を取ってしまったら、1分を待たず3秒・30秒でも知らせる。 */
    @Test
    fun `a foreign call is reported from three seconds`() {
        val thresholds = longCallThresholdsSeconds(includeFirstMinute = true, foreign = true).take(7).toList()
        assertEquals(listOf(3L, 30L, 60L, 180L, 300L, 600L, 900L), thresholds)
    }

    @Test
    fun `a domestic call keeps the normal schedule`() {
        val thresholds = longCallThresholdsSeconds(includeFirstMinute = true, foreign = false).take(3).toList()
        assertEquals(listOf(60L, 180L, 300L), thresholds)
    }

    @Test
    fun `foreign numbers are told apart from domestic ones`() {
        assertTrue(isLikelyForeignNumber("+18599437476"))
        assertTrue(isLikelyForeignNumber("+1 859-943-7476"))
        assertFalse(isLikelyForeignNumber("+819012345678"))
        assertFalse(isLikelyForeignNumber("09012345678"))
        // 番号が取れない通話を海外扱いすると、本人がかけた電話まで最も重い警告になる。
        assertFalse(isLikelyForeignNumber(null))
        assertFalse(isLikelyForeignNumber(""))
    }
}
