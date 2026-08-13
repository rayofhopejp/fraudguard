package com.fraudguard.monitor.call

/**
 * requirements.md 4.3章[v2]: 着信/通話中画面に出す「相手が誰か」。
 *
 * 番号だけを出す画面は、この端末の利用者にとって使いにくいだけでなく危険でもある。
 * 見知らぬ番号と家族の番号が同じ見た目で並ぶと、誰からの電話かを判断する手がかりが無い。
 * 連絡先の名前と、6章のホワイトリスト登録の有無を両方出す。
 *
 * @param displayName 連絡先またはホワイトリストから引けた表示名。引けなければnull。
 * @param isWhitelisted 6章のホワイトリストに登録された番号か。
 * @param source 名前をどこから引いたか(画面に出す文言を変えるために使う)。
 */
data class CallerIdentity(
    val phoneNumber: String?,
    val displayName: String?,
    val isWhitelisted: Boolean,
    val source: Source,
) {
    enum class Source { CONTACT, WHITELIST, UNKNOWN }

    /** 画面に大きく出す文字列。名前が分かればそれを、分からなければ番号を出す。 */
    val primaryLabel: String
        get() = displayName ?: phoneNumber ?: "非通知"

    /** 名前が出ている場合に、その下へ小さく添える番号。名前が無いときは重複するので出さない。 */
    val secondaryLabel: String?
        get() = if (displayName != null) phoneNumber else null

    /**
     * requirements.md 7章: 「登録されていない番号」であることを利用者にも示す。
     * 家族への通知(サーバー側判定)とは別に、電話を取る本人がその場で気づけるようにするための表示。
     * 非通知は番号が無いので登録の有無を判断できず、その旨をそのまま出す。
     */
    val trustLabel: String
        get() = when {
            phoneNumber == null -> "番号が通知されていません"
            isWhitelisted -> "登録済みの相手です"
            source == Source.CONTACT -> "連絡先にありますが、未登録の番号です"
            else -> "登録されていない番号です"
        }

    val isTrusted: Boolean get() = isWhitelisted
}
