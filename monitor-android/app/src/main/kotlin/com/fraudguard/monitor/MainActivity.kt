package com.fraudguard.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fraudguard.monitor.ui.dashboard.DashboardScreen
import com.fraudguard.monitor.ui.onboarding.PairingScreen
import com.fraudguard.monitor.ui.onboarding.PermissionRequestScreen
import com.fraudguard.monitor.ui.theme.FraudGuardMonitorTheme

private enum class OnboardingStep { PAIRING, PERMISSIONS, DASHBOARD }

/**
 * requirements.md 34章[v2]: 未ペアリングならペアリング画面 → 権限リクエスト画面(34.5章)→
 * ペアリング済み・権限確認済みなら監視状態のダッシュボードを表示する。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pairingRepository = (application as FraudGuardApplication).pairingRepository

        setContent {
            FraudGuardMonitorTheme {
                var step by remember {
                    mutableStateOf(if (pairingRepository.isPaired()) OnboardingStep.DASHBOARD else OnboardingStep.PAIRING)
                }

                when (step) {
                    OnboardingStep.PAIRING ->
                        PairingScreen(pairingRepository = pairingRepository, onPaired = { step = OnboardingStep.PERMISSIONS })
                    OnboardingStep.PERMISSIONS ->
                        PermissionRequestScreen(onDone = { step = OnboardingStep.DASHBOARD })
                    OnboardingStep.DASHBOARD ->
                        DashboardScreen()
                }
            }
        }
    }
}
