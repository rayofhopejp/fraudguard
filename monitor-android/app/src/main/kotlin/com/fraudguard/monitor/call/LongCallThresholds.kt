package com.fraudguard.monitor.call

/**
 * requirements.md 7.4章: 通話が続いた場合に家族へ知らせる経過時間。
 *
 * 1分・3分・5分・10分・15分で知らせ、以降は15分おきに知らせ続ける。
 * 説得が長引くほど危険度が上がるため、一度警告して終わりにはしない。
 * 通話が終わるまで続くので、この列は意図的に無限列にしてある
 * (呼び出し側は通話終了時にジョブごと止める)。
 *
 * LINE等のアプリ内通話では1分の通知を出さない。相手を電話番号で識別できず
 * (10.3章)、家族との日常的な短い通話まで拾ってしまうため。
 * 通常の電話は未登録番号かどうかをサーバーが判定できるので、1分から知らせる。
 */
private val INITIAL_THRESHOLDS_SECONDS = listOf(60L, 180L, 300L, 600L, 900L)

private const val REPEAT_INTERVAL_SECONDS = 900L

/**
 * requirements.md 7.1章: 海外番号からの着信を「取ってしまった」場合の早期通知。
 *
 * 海外番号からの着信は、取った時点で既に危険度が高い。1分待つ理由がない。
 * 家族が数十秒以内に気づいて本人へ連絡できるかどうかが、被害の有無を分けうる。
 */
private val FOREIGN_EARLY_THRESHOLDS_SECONDS = listOf(3L, 30L)

/**
 * 海外番号らしいか。サーバー側(libphonenumber)ほど厳密ではないが、
 * 端末側は「早く知らせるかどうか」を決めるだけなので、この判定で足りる。
 * 最終的なリスク判定はサーバーが行う。
 */
internal fun isLikelyForeignNumber(phoneNumber: String?): Boolean {
    val digits = phoneNumber?.filter { it.isDigit() || it == '+' } ?: return false
    return digits.startsWith("+") && !digits.startsWith("+81")
}

internal fun longCallThresholdsSeconds(
    includeFirstMinute: Boolean,
    foreign: Boolean = false,
): Sequence<Long> {
    val base = if (includeFirstMinute) INITIAL_THRESHOLDS_SECONDS else INITIAL_THRESHOLDS_SECONDS.drop(1)
    val initial = if (foreign) FOREIGN_EARLY_THRESHOLDS_SECONDS + base else base
    val repeating = generateSequence(INITIAL_THRESHOLDS_SECONDS.last() + REPEAT_INTERVAL_SECONDS) {
        it + REPEAT_INTERVAL_SECONDS
    }
    return initial.asSequence() + repeating
}
