package com.fraudguard.monitor.risk

/**
 * requirements.md 33章: 電話・SMS・通知・アプリインストールを最終的に集約する共通RiskEngine。
 * オフライン時(requirements.md 24章)でもホワイトリストのローカルキャッシュを使って
 * 端末単体でリスク判定できることが要件。
 *
 * TODO: 7章の警告ルール、14章の相関ルールを実装する。閾値はローカルDBの端末設定
 *       (サーバーから同期)から読み込み、コード直書きにしない(30章)。
 */
class RiskEngine {
    fun classifyIncomingCall(phoneNumber: String, isWhitelisted: Boolean): RiskAssessment {
        // TODO: PhoneNumberClassifier + ホワイトリストキャッシュを使った判定
        return RiskAssessment(RiskLevel.INFO, reason = "not_implemented")
    }
}

data class RiskAssessment(val level: RiskLevel, val reason: String)
