package com.fraudguard.monitor.call

/**
 * requirements.md 7.4章: ホワイトリスト外の相手との通話が続いた場合に家族へ知らせる閾値。
 * 3分で最初の警告を出し、以降5分・10分でも重ねて知らせる
 * (説得が長引くほど危険度が上がるため、一度警告して終わりにしない)。
 *
 * 通常の電話(FraudGuardInCallService)とLINE等のアプリ内通話(AppCallRegistry)で同じ基準を使う。
 * 実際に警告とするかの判定はサーバー側RiskEngineが行う(ホワイトリスト判定を持つのはサーバー)。
 */
internal val LONG_CALL_THRESHOLDS_SECONDS = listOf(180L, 300L, 600L)
