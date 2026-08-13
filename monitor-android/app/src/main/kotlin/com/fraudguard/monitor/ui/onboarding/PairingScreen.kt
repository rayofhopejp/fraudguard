package com.fraudguard.monitor.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fraudguard.monitor.pairing.PairingOutcome
import com.fraudguard.monitor.pairing.PairingRepository
import kotlinx.coroutines.launch

/**
 * requirements.md 34章: ペアリングコード入力 → サーバー登録 → 同意フロー(26章)→ 権限リクエスト。
 * TODO: QRスキャン(CameraX + ML Kit等)による入力補助、同意内容の表示・記録(ペアリング成功後)。
 */
@Composable
fun PairingScreen(pairingRepository: PairingRepository, onPaired: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "FraudGuard Monitor")
            Text(text = "家族から共有されたペアリングコードを入力してください")

            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    errorMessage = null
                },
                label = { Text("ペアリングコード") },
                singleLine = true,
                enabled = !isPairing,
                modifier = Modifier.fillMaxWidth(),
            )

            errorMessage?.let {
                Text(text = "ペアリングに失敗しました($it)。コードを確認してください", color = MaterialTheme.colorScheme.error)
            }

            Button(
                enabled = code.isNotBlank() && !isPairing,
                onClick = {
                    isPairing = true
                    errorMessage = null
                    scope.launch {
                        when (val outcome = pairingRepository.pair(code.trim())) {
                            is PairingOutcome.Success -> onPaired()
                            is PairingOutcome.Failure -> errorMessage = outcome.reason
                        }
                        isPairing = false
                    }
                },
            ) {
                Text("ペアリングする")
            }

            if (isPairing) {
                CircularProgressIndicator()
            }
        }
    }
}
