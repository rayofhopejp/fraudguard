package com.fraudguard.monitor.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * requirements.md 9章: SMS受信監視。送信元電話番号・本文・受信時刻を取得しサーバーへ送信する。
 * TODO: requirements.md 9.1章のキーワード判定(RiskEngine連携)、EventRepositoryへの保存。
 * TODO: 25章「不要な個人情報のログ出力禁止」— 本文をログ出力しないこと。
 */
class SmsReceivedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (message in messages) {
            val sender = message.originatingAddress ?: continue
            val body = message.messageBody ?: ""
            val receivedAt = message.timestampMillis
            // TODO: RiskEngineへ渡してキーワード判定 → EventRepository.record(...)
        }
    }
}
