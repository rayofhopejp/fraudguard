package com.fraudguard.monitor.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.fraudguard.monitor.data.local.dao.WhitelistDao

/**
 * requirements.md 4.3章[v2], 6章: 電話番号から「相手が誰か」を解決する。
 *
 * 連絡先(端末)とホワイトリスト(サーバーを正としたローカルキャッシュ)の両方を見る。
 * 連絡先の権限が無くても、ホワイトリストだけで動く。着信画面は電話が鳴っている最中に出るため、
 * どちらかが引けなくても画面が出ないという事態にはしない。
 */
class CallerIdentityResolver(
    private val context: Context,
    private val whitelistDao: WhitelistDao,
) {
    suspend fun resolve(phoneNumber: String?): CallerIdentity {
        if (phoneNumber.isNullOrBlank()) {
            return CallerIdentity(null, null, isWhitelisted = false, source = CallerIdentity.Source.UNKNOWN)
        }

        // ホワイトリストはE.164で保持している。端末が渡す番号は国内表記のこともあるため両方試す。
        val whitelisted = runCatching {
            whitelistDao.findByNumber(phoneNumber) ?: whitelistDao.findByNumber(toE164Japan(phoneNumber))
        }.getOrNull()

        val contactName = lookupContactName(phoneNumber)

        return CallerIdentity(
            phoneNumber = phoneNumber,
            // 連絡先の名前を優先する。利用者が普段その端末で見ている呼び方に合わせるため。
            displayName = contactName ?: whitelisted?.displayName,
            isWhitelisted = whitelisted != null,
            source = when {
                contactName != null -> CallerIdentity.Source.CONTACT
                whitelisted != null -> CallerIdentity.Source.WHITELIST
                else -> CallerIdentity.Source.UNKNOWN
            },
        )
    }

    private fun lookupContactName(phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        // PhoneLookupは表記ゆれ(ハイフン・国番号)を吸収して照合してくれる。
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}

/**
 * 国内表記(0始まり)をE.164へ寄せる。サーバー側のlibphonenumberほど厳密ではないが、
 * ホワイトリスト照合の取りこぼしを減らすための最小限の正規化。
 */
internal fun toE164Japan(phoneNumber: String): String {
    val digits = phoneNumber.filter { it.isDigit() || it == '+' }
    return when {
        digits.startsWith("+") -> digits
        digits.startsWith("0") -> "+81" + digits.drop(1)
        else -> digits
    }
}
