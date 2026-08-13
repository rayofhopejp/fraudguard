package com.fraudguard.monitor.ui.dialer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudguard.monitor.call.CallHistoryEntry
import com.fraudguard.monitor.call.ContactEntry
import com.fraudguard.monitor.call.directionLabel
import com.fraudguard.monitor.call.formatCallTimestamp

/**
 * requirements.md 4.3章[v2]: デフォルト電話アプリとしての「電話」画面。
 *
 * 標準の電話アプリを置き換える以上、キーパッドだけでは日常の電話に足りない。
 * かけ直し(履歴)と、家族へかける(連絡先)を同じ画面から使えるようにする。
 * 監視のためのアプリが、その端末の電話を不便にしてはならない。
 */
@Composable
fun PhoneScreen(
    initialNumber: String,
    history: List<CallHistoryEntry>,
    contacts: List<ContactEntry>,
    hasCallLogPermission: Boolean,
    hasContactsPermission: Boolean,
    onCall: (String) -> Unit,
) {
    // 番号付きで起動された場合(ACTION_DIAL)はキーパッドから始める。
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                TABS.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label, fontSize = 18.sp) },
                    )
                }
            }

            when (selectedTab) {
                0 -> DialerScreen(initialNumber = initialNumber, onCall = onCall)
                1 -> CallHistoryList(history, hasCallLogPermission, onCall)
                else -> ContactList(contacts, hasContactsPermission, onCall)
            }
        }
    }
}

private val TABS = listOf("キーパッド", "履歴", "連絡先")

@Composable
private fun CallHistoryList(
    history: List<CallHistoryEntry>,
    hasPermission: Boolean,
    onCall: (String) -> Unit,
) {
    if (!hasPermission) {
        EmptyState("通話履歴を表示するには、通話履歴の権限を許可してください。")
        return
    }
    if (history.isEmpty()) {
        EmptyState("通話履歴はありません。")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(history) { entry ->
            // 番号が無い(非通知)履歴はかけ直せないため、押せないようにする。
            val number = entry.phoneNumber
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (number != null) Modifier.clickable { onCall(number) } else Modifier)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(text = entry.label, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "${directionLabel(entry.direction)} ・ ${formatCallTimestamp(entry.timestampMillis)}",
                    fontSize = 16.sp,
                    color = if (entry.direction == CallHistoryEntry.Direction.MISSED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ContactList(contacts: List<ContactEntry>, hasPermission: Boolean, onCall: (String) -> Unit) {
    if (!hasPermission) {
        EmptyState("連絡先を表示するには、連絡先の権限を許可してください。")
        return
    }
    if (contacts.isEmpty()) {
        EmptyState("連絡先はありません。")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(contacts) { contact ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCall(contact.phoneNumber) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(text = contact.name, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = contact.phoneNumber,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Text(text = message, fontSize = 18.sp, modifier = Modifier.padding(24.dp))
}
