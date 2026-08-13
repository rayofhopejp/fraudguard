package com.fraudguard.monitor.call

import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.getSystemService
import com.fraudguard.monitor.ui.dialer.DialerScreen
import com.fraudguard.monitor.ui.theme.FraudGuardMonitorTheme

/**
 * requirements.md 4.3章[v2]: ROLE_DIALER取得の必須要件(ACTION_DIALハンドラ)を満たす発信画面。
 * デフォルト電話アプリになった場合、この画面が実質的な「電話アプリのダイヤル画面」を代替するため、
 * 要件充足のためのダミーではなく実際に発信できるようにしてある。
 */
class DialerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ACTION_DIAL / VIEW(tel:)で番号付きに起動された場合、その番号を初期表示する。
        val initialNumber = intent?.data?.schemeSpecificPart.orEmpty()

        setContent {
            FraudGuardMonitorTheme {
                DialerScreen(initialNumber = initialNumber, onCall = ::placeCall)
            }
        }
    }

    private fun placeCall(number: String) {
        val telecomManager = getSystemService<TelecomManager>() ?: return
        val uri = Uri.fromParts("tel", number, null)
        try {
            telecomManager.placeCall(uri, null)
        } catch (e: SecurityException) {
            // CALL_PHONE権限が未許可の場合。TODO: 権限リクエストフロー(34.5章)で事前に確保する。
        }
        finish()
    }
}
