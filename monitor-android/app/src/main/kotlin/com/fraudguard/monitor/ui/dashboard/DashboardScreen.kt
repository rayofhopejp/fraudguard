package com.fraudguard.monitor.ui.dashboard

import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudguard.monitor.call.DialerActivity
import androidx.core.content.getSystemService

/**
 * requirements.md 30章: 監視中であることが端末利用者にも分かるようにする(透明性・安心材料)。
 * requirements.md 4.3章[v2], 8章: 遠隔通話切断を使うにはROLE_DIALER(デフォルト電話アプリ)取得が必要。
 * TODO: 監視状態(通知アクセス有効/無効等)の表示、直近のリスクイベント件数、ペアリング解除導線。
 */
@Composable
fun DashboardScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val roleManager = remember { context.getSystemService<RoleManager>() }
    var isDialerRoleHeld by remember { mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true) }

    val roleRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        isDialerRoleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "監視中です")

            TextButton(onClick = onBack) { Text("← 電話に戻る", fontSize = 18.sp) }
            // TODO: HeartbeatWorkerの直近送信状態、権限の有効/無効を表示

            if (isDialerRoleHeld) {
                Text(text = "デフォルトの電話アプリに設定済みです(遠隔通話切断が利用可能)")
            } else {
                Text(text = "遠隔通話切断を使うには、このアプリをデフォルトの電話アプリに設定してください")
                Button(onClick = {
                    val intent = roleManager?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    if (intent != null) roleRequestLauncher.launch(intent)
                }) {
                    Text("デフォルトの電話アプリに設定")
                }
            }
        }
    }
}
