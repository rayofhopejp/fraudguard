package com.fraudguard.monitor.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * requirements.md 4.3章[v2], 7章: 着信画面が「誰からか」「登録された相手か」をどう出すか。
 * 電話を取る本人が、その場で相手を判断できるかどうかがかかっている部分。
 */
class CallerIdentityTest {

    private fun identity(
        phoneNumber: String? = "09012345678",
        displayName: String? = null,
        isWhitelisted: Boolean = false,
        source: CallerIdentity.Source = CallerIdentity.Source.UNKNOWN,
    ) = CallerIdentity(phoneNumber, displayName, isWhitelisted, source)

    @Test
    fun `a known contact is shown by name with the number underneath`() {
        val id = identity(displayName = "娘", source = CallerIdentity.Source.CONTACT)
        assertEquals("娘", id.primaryLabel)
        assertEquals("09012345678", id.secondaryLabel)
    }

    /** 名前が引けないときに番号を2回出しても意味がない。 */
    @Test
    fun `an unknown number is shown once, as the number`() {
        val id = identity()
        assertEquals("09012345678", id.primaryLabel)
        assertNull(id.secondaryLabel)
    }

    @Test
    fun `a withheld number says so instead of showing an empty name`() {
        val id = identity(phoneNumber = null)
        assertEquals("非通知", id.primaryLabel)
        assertEquals("番号が通知されていません", id.trustLabel)
        assertFalse(id.isTrusted)
    }

    @Test
    fun `a whitelisted number is presented as trusted`() {
        val id = identity(displayName = "娘", isWhitelisted = true, source = CallerIdentity.Source.WHITELIST)
        assertTrue(id.isTrusted)
        assertEquals("登録済みの相手です", id.trustLabel)
    }

    /**
     * 連絡先にあることと、6章のホワイトリストに登録されていることは別。
     * 詐欺犯の番号が何かの拍子に連絡先へ入っている可能性があるため、
     * 連絡先にあるだけで「登録済み」と見せてはいけない。
     */
    @Test
    fun `being in contacts is not the same as being whitelisted`() {
        val id = identity(displayName = "不明な相手", source = CallerIdentity.Source.CONTACT)
        assertFalse(id.isTrusted)
        assertEquals("連絡先にありますが、未登録の番号です", id.trustLabel)
    }

    @Test
    fun `an unknown caller is called out explicitly`() {
        assertEquals("登録されていない番号です", identity().trustLabel)
    }

    // --- ホワイトリスト照合のための番号正規化 ---

    @Test
    fun `domestic numbers are normalised to E164 so the whitelist matches`() {
        assertEquals("+819012345678", toE164Japan("09012345678"))
        assertEquals("+819012345678", toE164Japan("090-1234-5678"))
        assertEquals("+819012345678", toE164Japan("+819012345678"))
    }
}
