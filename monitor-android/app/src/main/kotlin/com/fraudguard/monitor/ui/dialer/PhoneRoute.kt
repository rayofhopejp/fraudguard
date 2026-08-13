package com.fraudguard.monitor.ui.dialer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.fraudguard.monitor.call.CallHistoryEntry
import com.fraudguard.monitor.call.ContactEntry
import com.fraudguard.monitor.call.PhoneBookRepository
import com.fraudguard.monitor.call.placeCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * requirements.md 4.3章[v2]: 「電話」画面の組み立て。
 * アプリのホームからも、ACTION_DIALで番号付きに起動された場合も、同じ画面を使う。
 */
@Composable
fun PhoneRoute(initialNumber: String = "", initialTab: Int = PhoneTab.CONTACTS) {
    val context = LocalContext.current
    val phoneBook = remember { PhoneBookRepository(context) }

    // 履歴と連絡先の読み出しはContentProviderへの問い合わせなので、画面表示を待たせない。
    val history by produceState(initialValue = emptyList<CallHistoryEntry>()) {
        value = withContext(Dispatchers.IO) { phoneBook.recentCalls() }
    }
    val contacts by produceState(initialValue = emptyList<ContactEntry>()) {
        value = withContext(Dispatchers.IO) { phoneBook.contacts() }
    }

    PhoneScreen(
        initialNumber = initialNumber,
        initialTab = initialTab,
        history = history,
        contacts = contacts,
        hasCallLogPermission = phoneBook.hasCallLogPermission(),
        hasContactsPermission = phoneBook.hasContactsPermission(),
        onCall = { placeCall(context, it) },
    )
}
