package com.fraudguard.monitor.call

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * requirements.md 5章: 電話番号の正規化(E.164)と国内/海外・異常形式の判定。
 * デフォルトリージョンは "JP" とし、09012345678 のような国内表記もE.164へ正規化する。
 */
class PhoneNumberClassifier(private val defaultRegion: String = "JP") {

    private val util = PhoneNumberUtil.getInstance()

    sealed class Classification {
        data class Valid(val e164: String, val isDomestic: Boolean) : Classification()
        data object Invalid : Classification() // requirements.md 5.3章: 不正形式番号
    }

    fun classify(rawNumber: String): Classification {
        return try {
            val parsed = util.parse(rawNumber, defaultRegion)
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
