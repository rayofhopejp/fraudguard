package com.fraudguard.server.domain.risk

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * requirements.md 5章: 電話番号の正規化(E.164)と国内/海外・異常形式の判定。
 * monitor-android/.../call/PhoneNumberClassifier.kt とロジックを揃えている
 * (端末側キャッシュが古い場合でも、サーバー側で正となる判定をやり直せるようにするため)。
 */
object PhoneNumberClassifier {
    private val util = PhoneNumberUtil.getInstance()
    private const val DEFAULT_REGION = "JP"

    sealed class Classification {
        data class Valid(val e164: String, val isDomestic: Boolean) : Classification()
        data object Invalid : Classification() // requirements.md 5.3章: 不正形式番号
    }

    fun classify(rawNumber: String?): Classification {
        if (rawNumber.isNullOrBlank()) return Classification.Invalid
        return try {
            val parsed = util.parse(rawNumber, DEFAULT_REGION)
            if (!util.isValidNumber(parsed)) {
                Classification.Invalid
            } else {
                val e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                Classification.Valid(e164 = e164, isDomestic = parsed.countryCode == 81)
            }
        } catch (e: NumberParseException) {
            Classification.Invalid
        }
    }
}
