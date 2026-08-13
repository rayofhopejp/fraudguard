package com.fraudguard.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.fraudguard.monitor.ui.dashboard.DashboardScreen
import com.fraudguard.monitor.ui.home.HomeScreen
import com.fraudguard.monitor.ui.onboarding.PairingScreen
import com.fraudguard.monitor.ui.onboarding.PermissionRequestScreen
import com.fraudguard.monitor.ui.theme.FraudGuardMonitorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OnboardingStep { PAIRING, PERMISSIONS, HOME }

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
                    mutableStateOf(if (pairingRepository.isPaired()) OnboardingStep.HOME else OnboardingStep.PAIRING)
                }
                // requirements.md 4.3章[v2]: この端末ではこのアプリが電話アプリそのものなので、
                // ホームは電話画面にする。監視状態は必要なときに開ければよい。
                var showMonitoringStatus by remember { mutableStateOf(false) }

                when (step) {
                    OnboardingStep.PAIRING ->
                        PairingScreen(pairingRepository = pairingRepository, onPaired = { step = OnboardingStep.PERMISSIONS })
                    OnboardingStep.PERMISSIONS ->
                        PermissionRequestScreen(onDone = { step = OnboardingStep.HOME })
                    OnboardingStep.HOME ->
                        if (showMonitoringStatus) {
                            DashboardScreen(onBack = { showMonitoringStatus = false })
                        } else {
                            HomeScreen(onOpenMonitoringStatus = { showMonitoringStatus = true })
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // requirements.md 11章: 新規アプリの走査。Application.onCreate はプロセス生成時にしか走らず、
        // 既に起動中のアプリを前面に戻しただけでは再走査されないため、画面表示のたびにも走らせる
        // (実機テストで、アプリを開き直しても新規インストールが検知されない問題として発覚)。
        val app = application as FraudGuardApplication
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.appInstallScanner.scan() }
                // requirements.md 13章: インストール済みアプリの初回起動検知もあわせて行う。
                runCatching { app.appLaunchDetector.checkLaunches() }
            }
        }
    }
}
