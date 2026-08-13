package com.fraudguard.monitor.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.fraudguard.monitor.ui.dialer.PhoneRoute
import com.fraudguard.monitor.ui.dialer.PhoneTab
import com.fraudguard.monitor.ui.theme.FraudGuardMonitorTheme

/**
 * requirements.md 4.3章[v2]: ROLE_DIALER取得の必須要件(ACTION_DIALハンドラ)を満たす発信画面。
 * アプリのホームからも同じ画面を開くため、中身はPhoneRouteに共通化してある。
 */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ACTION_DIAL / VIEW(tel:)で番号付きに起動された場合、その番号を初期表示する。
        val initialNumber = intent?.data?.schemeSpecificPart.orEmpty()

        setContent {
            FraudGuardMonitorTheme {
                PhoneRoute(
                    initialNumber = initialNumber,
                    // 番号を渡されて起動されたときだけキーパッドから始める。
                    initialTab = if (initialNumber.isNotEmpty()) PhoneTab.KEYPAD else PhoneTab.CONTACTS,
                )
            }
        }
    }
}
