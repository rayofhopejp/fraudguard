package com.fraudguard.monitor.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudguard.monitor.ui.dialer.PhoneRoute

/**
 * requirements.md 4.3章[v2]: アプリを開いたときの画面。
 *
 * デフォルト電話アプリになった時点で、この端末の利用者にとってこのアプリは「電話」そのもの。
 * 監視状態を最初に見せても日常の役に立たないので、ホームは電話画面にする。
 *
 * requirements.md 30章: ただし監視されていること自体は隠さない。
 * 見出しに常に表示し、そこから監視状態の画面へ入れるようにしておく。
 */
@Composable
fun HomeScreen(onOpenMonitoringStatus: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(onOpenMonitoringStatus)
        PhoneRoute()
    }
}

@Composable
private fun Row(onOpenMonitoringStatus: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "監視中です",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenMonitoringStatus) { Text("監視状態", fontSize = 14.sp) }
    }
}
