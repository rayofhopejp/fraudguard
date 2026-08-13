package com.fraudguard.monitor.call

import android.content.Context
import android.net.Uri
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.content.getSystemService

/**
 * requirements.md 4.3章[v2]: 発信。ホーム画面とACTION_DIAL経由の画面の両方から使う。
 *
 * 権限が無いときに黙って終わると「押したのに何も起きない」に見えるため、必ず理由を伝える。
 */
fun placeCall(context: Context, number: String): Boolean {
    val telecomManager = context.getSystemService<TelecomManager>() ?: return false
    return try {
        telecomManager.placeCall(Uri.fromParts("tel", number, null), null)
        true
    } catch (e: SecurityException) {
        // CALL_PHONE権限は34.5章の権限リクエスト画面から許可できる。
        Toast.makeText(context, "発信するには電話の権限を許可してください", Toast.LENGTH_LONG).show()
        false
    }
}
