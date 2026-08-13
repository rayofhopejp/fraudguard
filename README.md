# FraudGuard

Android端末上での特殊詐欺・投資詐欺・遠隔操作詐欺の兆候を監視し、家族へリアルタイム通知するシステム。

同意済みの家族端末へのサイドロード運用を前提とする(Google Play一般配布は対象外)。

> **見守りたいご家族の端末に設置する方へ** — 手順は [**導入手順書（らくらくフォン向け・ITの知識不要）**](./docs/install-rakuraku.md) をご覧ください。同意の取り方から、うまくいかないときの対処、やめ方まで書いてあります。

詳細な要件は [`docs/requirements.md`](./docs/requirements.md)、サーバーの構築手順は [`docs/deployment.md`](./docs/deployment.md) を参照。

## 構成

モノレポ構成。3コンポーネント + ドキュメント。

```
fraudguard/
├── docs/             要件定義・設計ドキュメント
├── server/           バックエンドAPI (Kotlin + Ktor + PostgreSQL)
├── monitor-android/  監視対象Androidアプリ (Kotlin + Jetpack Compose)
└── family-web/       家族向けWebアプリ (Next.js + TypeScript)
```

| コンポーネント | 役割 | スタック |
|---|---|---|
| `server/` | REST API、認証、イベント保存、FCM配信、遠隔コマンド管理 | Kotlin, Ktor, Exposed, PostgreSQL |
| `monitor-android/` | 通話/SMS/通知/アプリインストール監視、イベント送信、遠隔切断実行 | Kotlin, Jetpack Compose, Room, WorkManager |
| `family-web/` | 警告確認、ホワイトリスト管理、遠隔切断操作 | Next.js, TypeScript, React |

インフラはAWS(Lightsail + Cognito + S3)を想定。詳細は要件定義の36章を参照。

## 開発の始め方

各ディレクトリのREADMEを参照:

- [`server/README.md`](./server/README.md)
- [`monitor-android/README.md`](./monitor-android/README.md)
- [`family-web/README.md`](./family-web/README.md)

## 開発フェーズ

要件定義29章に準拠。

- **Phase 1**: Monitorアプリ基盤・通常電話監視・ホワイトリスト・遠隔切断・監視継続性(ハートビート)
- **Phase 2**: SMS/通知/アプリインストール監視・リスク相関判定
- **Phase 3**: 通話録音・文字起こし・詐欺文脈判定

## 前提

対象端末の所有者・利用者が監視・通知・SMS内容共有等に明示的に同意していることを前提とする。一般公開・無断監視用途は対象外([詳細](./docs/requirements.md#26-プライバシー前提))。
