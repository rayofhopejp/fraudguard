package com.fraudguard.monitor.call

import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.fraudguard.monitor.ui.dialer.PhoneScreen
import com.fraudguard.monitor.ui.theme.FraudGuardMonitorTheme

/**
 * requirements.md 4.3章[v2]: ROLE_DIALER取得の必須要件(ACTION_DIALハンドラ)を満たす発信画面。
 * デフォルト電話アプリになった場合、この画面が実質的な「電話アプリのダイヤル画面」を代替するため、
 * 要件充足のためのダミーではなく実際に発信できるようにしてある。
 */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ACTION_DIAL / VIEW(tel:)で番号付きに起動された場合、その番号を初期表示する。
        val initialNumber = intent?.data?.schemeSpecificPart.orEmpty()

        val phoneBook = PhoneBookRepository(this)

        setContent {
            FraudGuardMonitorTheme {
                // 履歴と連絡先の読み出しはContentProviderへの問い合わせなので、画面表示を待たせない。
                val history by produceState(initialValue = emptyList<CallHistoryEntry>()) {
                    value = withContext(Dispatchers.IO) { phoneBook.recentCalls() }
                }
                val contacts by produceState(initialValue = emptyList<ContactEntry>()) {
                    value = withContext(Dispatchers.IO) { phoneBook.contacts() }
                }

                PhoneScreen(
                    initialNumber = initialNumber,
                    history = history,
                    contacts = contacts,
                    hasCallLogPermission = phoneBook.hasCallLogPermission(),
                    hasContactsPermission = phoneBook.hasContactsPermission(),
                    onCall = ::placeCall,
                )
            }
        }
    }

    private fun placeCall(number: String) {
        val telecomManager = getSystemService<TelecomManager>() ?: return
        val uri = Uri.fromParts("tel", number, null)
        try {
            telecomManager.placeCall(uri, null)
        } catch (e: SecurityException) {
            // CALL_PHONE権限が未許可の場合。黙って画面を閉じると「押したのに何も起きない」に見えるため、
            // 理由を伝えて画面は残す(権限は34.5章の権限リクエスト画面から許可できる)。
            Toast.makeText(this, "発信するには電話の権限を許可してください", Toast.LENGTH_LONG).show()
            return
        }
        // 発信後はInCallActivityが前面に出るため、この画面は閉じてよい。
        finish()
    }
}
