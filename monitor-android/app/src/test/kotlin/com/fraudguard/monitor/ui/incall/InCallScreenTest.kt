package com.fraudguard.monitor.ui.incall

import android.telecom.Call
import kotlin.test.Test
import kotlin.test.assertEquals

/** android.telecom.Call.STATE_* の定数値そのものはAndroid SDK stub jarにコンパイルされておりJVM単体テストで参照できる。 */
class InCallScreenTest {

    @Test
    fun `known call states map to Japanese labels`() {
        assertEquals("着信中", callStateLabel(Call.STATE_RINGING))
        assertEquals("発信中", callStateLabel(Call.STATE_DIALING))
        assertEquals("通話中", callStateLabel(Call.STATE_ACTIVE))
        assertEquals("保留中", callStateLabel(Call.STATE_HOLDING))
        assertEquals("接続中", callStateLabel(Call.STATE_CONNECTING))
        assertEquals("通話終了", callStateLabel(Call.STATE_DISCONNECTED))
        assertEquals("切断中", callStateLabel(Call.STATE_DISCONNECTING))
    }

    @Test
    fun `unknown state falls back to a generic label`() {
        assertEquals("通話", callStateLabel(-1))
    }
}
