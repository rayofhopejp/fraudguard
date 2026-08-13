package com.fraudguard.monitor.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** requirements.md 4.3章[v2]: 通話履歴の1件。表示に必要な最小限だけを持つ。 */
data class CallHistoryEntry(
    val phoneNumber: String?,
    val displayName: String?,
    val direction: Direction,
    val timestampMillis: Long,
) {
    enum class Direction { INCOMING, OUTGOING, MISSED, OTHER }

    val label: String get() = displayName ?: phoneNumber ?: "非通知"
}

/** requirements.md 4.3章[v2]: 連絡先の1件。 */
data class ContactEntry(val name: String, val phoneNumber: String)

/**
 * requirements.md 4.3章[v2]: デフォルト電話アプリとして最低限必要な、通話履歴と連絡先の読み出し。
 *
 * 標準の電話アプリを置き換える以上、かけ直しと連絡先からの発信ができないと
 * この端末の利用者は日常的な電話に困る。監視のためのアプリが生活を不便にしてはならない。
 *
 * 権限が無い場合は空リストを返す。画面は「権限が無い」ことを表示して他のタブは使える状態を保つ。
 */
class PhoneBookRepository(private val context: Context) {

    fun hasCallLogPermission(): Boolean = hasPermission(Manifest.permission.READ_CALL_LOG)

    fun hasContactsPermission(): Boolean = hasPermission(Manifest.permission.READ_CONTACTS)

    fun recentCalls(limit: Int = 100): List<CallHistoryEntry> {
        if (!hasCallLogPermission()) return emptyList()
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $limit",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            CallHistoryEntry(
                                phoneNumber = cursor.getString(0)?.takeIf { it.isNotBlank() },
                                displayName = cursor.getString(1)?.takeIf { it.isNotBlank() },
                                direction = directionOf(cursor.getInt(2)),
                                timestampMillis = cursor.getLong(3),
                            ),
                        )
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    fun contacts(): List<ContactEntry> {
        if (!hasContactsPermission()) return emptyList()
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val seen = mutableSetOf<String>()
                buildList {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                        val number = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        // 同じ人の同じ番号が複数の登録元(SIM/アカウント)から重複して出ることがある。
                        if (seen.add("$name|${number.filter { it.isDigit() }}")) {
                            add(ContactEntry(name, number))
                        }
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun directionOf(type: Int): CallHistoryEntry.Direction = when (type) {
        CallLog.Calls.INCOMING_TYPE -> CallHistoryEntry.Direction.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallHistoryEntry.Direction.OUTGOING
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE ->
            CallHistoryEntry.Direction.MISSED
        else -> CallHistoryEntry.Direction.OTHER
    }
}

/** requirements.md 4.3章[v2]: 履歴の並びで使う、日付の読みやすい表記。 */
fun formatCallTimestamp(timestampMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    val time = "%02d:%02d".format(
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
    )
    val sameDay = calendar.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        calendar.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return time

    return "%d月%d日 %s".format(
        calendar.get(java.util.Calendar.MONTH) + 1,
        calendar.get(java.util.Calendar.DAY_OF_MONTH),
        time,
    )
}

fun directionLabel(direction: CallHistoryEntry.Direction): String = when (direction) {
    CallHistoryEntry.Direction.INCOMING -> "着信"
    CallHistoryEntry.Direction.OUTGOING -> "発信"
    CallHistoryEntry.Direction.MISSED -> "不在着信"
    CallHistoryEntry.Direction.OTHER -> "通話"
}
