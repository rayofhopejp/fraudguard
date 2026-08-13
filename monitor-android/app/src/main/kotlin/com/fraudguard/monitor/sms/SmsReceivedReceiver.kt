package com.fraudguard.monitor.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * requirements.md 9章: SMS受信監視。送信元電話番号・本文・受信時刻を取得しサーバーへ送信する。
 * 危険語の判定(9.1章)と通話との相関(14.3章)はサーバー側のRiskEngineが行うため、
 * ここでは本文をそのまま送り、リスクレベルは控えめな既定値で報告する。
 *
 * requirements.md 25章: 本文・送信元番号をログ出力しないこと。
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // 長文SMSは複数パートに分割されて届く。パートごとにイベント化すると家族への通知が重複し、
        // 危険語がパート境界をまたぐと検知漏れにもなるため、送信元ごとに本文を連結してから1件として扱う。
        val bySender = messages
            .filter { it.originatingAddress != null }
            .groupBy { it.originatingAddress!! }

        val app = context.applicationContext as? FraudGuardApplication ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for ((sender, parts) in bySender) {
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    app.eventReporter.report(
                        type = EventType.SMS_RECEIVED,
                        riskLevel = RiskLevel.NOTICE,
                        title = "SMSを受信しました",
                        detail = "監視端末がSMSを受信しました。",
                        metadata = EventMetadata(
                            phoneNumber = sender,
                            messageBody = body,
                        ),
                    )
                }
            } finally {
                // goAsync()の完了通知。これを呼ばないとプロセスがANR扱いされる。
                pendingResult.finish()
            }
        }
    }
}
