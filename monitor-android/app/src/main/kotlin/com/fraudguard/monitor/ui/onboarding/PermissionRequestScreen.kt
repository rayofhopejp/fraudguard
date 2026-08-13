package com.fraudguard.monitor.ui.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fraudguard.monitor.permission.RequiredPermissions

/**
 * requirements.md 34.5章: ペアリング完了直後の権限リクエストフロー。
 * 通知アクセス(NotificationListenerService)はランタイムダイアログでは許可できないため設定画面へ誘導し、
 * 画面復帰(ON_RESUME)のたびに許可状態を再チェックする。
 *
 * 権限が揃っていなくても「あとで設定する」でダッシュボードへ進めるようにしている
 * (端末の実利用者が高齢者本人の場合、その場で全て理解・許可できるとは限らないため)。
 */
@Composable
fun PermissionRequestScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var missingRuntime by remember { mutableStateOf(RequiredPermissions.missingRuntimePermissions(context)) }
    var notificationAccessGranted by remember { mutableStateOf(RequiredPermissions.isNotificationAccessGranted(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        missingRuntime = RequiredPermissions.missingRuntimePermissions(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 通知アクセス設定画面から戻ってきた場合などに再チェックする。
                missingRuntime = RequiredPermissions.missingRuntimePermissions(context)
                notificationAccessGranted = RequiredPermissions.isNotificationAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "監視に必要な権限を許可してください")
            Text(
                text = "以下は詐欺の兆候を検知するために必要です。この端末の同意済み利用者の合意のもとで許可してください。",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            if (missingRuntime.isEmpty()) {
                Text(text = "✓ 通話・SMS等の権限は許可済みです")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    missingRuntime.forEach { permission ->
                        Text(text = "・${RequiredPermissions.label(permission)}")
                    }
                }
                Button(onClick = { permissionLauncher.launch(missingRuntime.toTypedArray()) }) {
                    Text("許可する")
                }
            }

            HorizontalDivider()

            if (notificationAccessGranted) {
                Text(text = "✓ 通知へのアクセスは許可済みです(LINE等の詐欺兆候検知に使用)")
            } else {
                Text(text = "通知へのアクセス(LINE/Telegram等の詐欺兆候検知に使用)は設定画面から許可が必要です")
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("通知アクセスの設定を開く")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDone,
            ) {
                Text(if (missingRuntime.isEmpty() && notificationAccessGranted) "次へ進む" else "あとで設定する")
            }
        }
    }
}
