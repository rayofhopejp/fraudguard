# FraudGuard Family

家族向けWebアプリ。Next.js(App Router) + TypeScript。監視端末からの警告確認・ホワイトリスト管理・遠隔通話切断を行う。

要件の詳細は [`../docs/requirements.md`](../docs/requirements.md) を参照。

## セットアップ

```bash
npm install
cp .env.example .env.local  # 値を編集(Cognito, Firebase, API URL)
npm run dev
```

http://localhost:3000 で起動。

## 構成

```
src/
├── app/
│   ├── login/                              ログイン(Cognito Hosted UI)
│   ├── devices/                            端末一覧
│   ├── devices/[deviceId]/                 警告履歴(イベント一覧)
│   ├── devices/[deviceId]/events/[eventId] 警告詳細・操作(確認/切断/WL追加)
│   └── devices/[deviceId]/whitelist/       ホワイトリスト管理
├── components/                             RiskBadge, EventCard, DeviceStatusBadge
└── lib/
    ├── api.ts                              サーバーREST APIクライアント
    ├── auth.ts                             NextAuth設定(Cognito Provider)
    └── types.ts                            server/domain/model と対になる型
```

## 現状(スキャフォールディング段階)

- 認証セッションからのアクセストークン受け渡しが未接続(`api.ts` の呼び出し箇所に `TODO` あり)。NextAuthのセッション型拡張(`accessToken`)を追加し、各ページ/コンポーネントに配線する必要がある
- Web Push(FCM)の登録・受信は未実装。`firebase` パッケージは未使用のため`package.json`からは一旦外している(実装時、その時点で脆弱性の無い最新版を`npm install firebase`で追加すること。10.x系は`@firebase/*`配下が古い`undici`に依存し`npm audit`で複数件検出されたため見送った)。`.env.example` のFirebase設定項目はそのまま利用する想定
- イベント詳細取得専用API(`GET /devices/{deviceId}/events/{eventId}`)がサーバー側に無く、一覧から`find`している暫定実装
- ホワイトリスト/ブラックリストのボタンの一部は未接続(`EventActions.tsx` 参照)

## 通知の内容最小化について

requirements.md 16.3章[v2]の方針により、Push通知(ロック画面等)には電話番号やSMS本文などのセンシティブ情報を含めない。詳細はこのアプリ内(認証後)でのみ表示する。
