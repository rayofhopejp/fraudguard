package com.fraudguard.monitor.ui.dialer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DIALPAD_KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#"),
)

/**
 * requirements.md 4.3章[v2]: ROLE_DIALER(デフォルト電話アプリ)化に伴い最低限必要な発信画面。
 * AOSPのDIALERロール定義がACTION_DIALハンドラを要求するため、単なる要件充足ではなく
 * 実際に発信できる状態にしておく(でないと高齢ユーザーの電話が使えなくなってしまう)。
 */
@Composable
fun DialerScreen(initialNumber: String = "", onCall: (String) -> Unit) {
    var number by remember { mutableStateOf(initialNumber) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = number.ifEmpty { "番号を入力" },
                fontSize = 32.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(vertical = 32.dp),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DIALPAD_KEYS.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { key ->
                            OutlinedButton(
                                onClick = { number += key },
                                modifier = Modifier.aspectRatio(1f),
                            ) {
                                Text(text = key, fontSize = 24.sp)
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = number.isNotEmpty(),
                    onClick = { number = number.dropLast(1) },
                ) {
                    Text(text = "削除")
                }

                Button(
                    enabled = number.isNotBlank(),
                    onClick = { onCall(number) },
                ) {
                    Text(text = "発信")
                }
            }
        }
    }
}
