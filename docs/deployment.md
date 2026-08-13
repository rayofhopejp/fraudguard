# デプロイ手順

このドキュメントは、`server/`をAWS Lightsailへ、`monitor-android/`を実機へサイドロードするための手順書。`family-web/`のデプロイも含む。

## 0. 前提として知っておくこと(現状の実装レベル)

サーバーのDB永続化・認証(device-auth/family-auth)・ペアリングフロー・遠隔コマンドのEd25519署名/検証・ホワイトリスト/ブラックリストCRUD・イベントの冪等性・RiskEngine(7章・14章)・Slack通知(2.2章)・遠隔コマンドのFCM即時送信(8章)・死活監視(35章)は実装済みで、実際のPostgreSQLに対する統合テストで動作確認済み([`server/README.md`](../server/README.md)参照)。今回の手順で得られるのは:

- サーバーがHTTPSで公開され、実際にイベント登録・ホワイトリスト管理・ペアリング・リスク判定・Slack通知・遠隔コマンドのFCM即時配信・死活監視が機能する状態
- Cognito認証・Firebase・DBスキーマの「配線」が全て繋がった状態
- Androidアプリが実機にインストールされ、サーバーと通信できる状態

Android側の`pairing.PairingRepository`(ペアリング・FCMトークン登録・ハートビート送信)、遠隔コマンドの受信〜署名検証〜二重実行防止〜結果報告(`command/RemoteCommandExecutor`, `push/FraudGuardMessagingService.onMessageReceived`)、ROLE_DIALER化一式(`call/DialerActivity`, `call/InCallActivity`, `call/FraudGuardInCallService`, ダッシュボードからのロール取得ボタン)、およびペアリング成功後の権限リクエストフロー(`ui/onboarding/PermissionRequestScreen.kt`)も実装済み。コンパイル・単体テストは通っているが、**この環境にはAndroidエミュレータ/実機が無いため、実際のロール付与・着信/発信・権限ダイアログの実地動作は未検証**。B4でサイドロードした実機で必ず確認すること。

ただし以下はまだ未実装:

- `DialerActivity`/`InCallActivity`は最小限のUI(ダイヤルパッド、応答/拒否/終話のみ)。連絡先・通話履歴等は無い

これらは今回のインフラ構築後に着手するとよい。

必要なアカウント: AWSアカウント、独自ドメイン(推奨。無くてもIPアドレスのみで動かせるがTLS証明書取得に難あり)、Slackワークスペース(家族への通知用、Incoming Webhookを使う)、Firebaseアカウント(遠隔コマンドのFCM配信用、無料)。

---

## Part A: サーバー(AWS Lightsail)

### A1. Lightsailインスタンス作成

AWSコンソール → Lightsail → 「インスタンスの作成」

- リージョン: `ap-northeast-1`(東京)
- OS: **Ubuntu 22.04 LTS**
- プラン: 最小構成(1GB RAM / 1vCPU, 月$5)で十分。要件定義36章の試算通り
- インスタンス名: `fraudguard-server`

作成後、「ネットワーキング」タブで**静的IPを作成してアタッチ**(無料、インスタンスにアタッチしている限り課金されない)。

ファイアウォール(同タブ)で以下を開放:
- SSH (22) — デフォルトで開いている
- HTTP (80), HTTPS (443) — 追加する
- カスタムTCP 8080 は開けない(アプリはCaddy経由でのみ公開し、直接晒さない)

### A2. インスタンス初期設定

SSH接続(Lightsailコンソールのブラウザターミナルで十分)後:

```bash
sudo apt-get update && sudo apt-get install -y docker.io git
sudo usermod -aG docker $USER
# 一度ログアウト/再SSHしてグループ反映
```

### A3. Cognito User Pool作成(家族ユーザー認証)

AWSコンソール → Cognito → 「ユーザープールを作成」

- サインインオプション: メールアドレス
- MFA: 「オプション」以上を推奨(遠隔切断・SMS内容閲覧権限を持つため、要件定義25章参照)
- アプリクライアント作成時に「クライアントシークレットを生成」をON(Next.jsのnext-authはconfidential clientとして使う)
- ホストされたUIを有効化し、コールバックURLに `https://family.example.com/api/auth/callback/cognito`(実際のドメインに置き換え)を登録

控えておく値:
- User Pool ID → issuerに使う: `https://cognito-idp.ap-northeast-1.amazonaws.com/<UserPoolId>`
- アプリクライアントID / シークレット

### A4. Slack Incoming Webhook作成(要件定義2.2章、家族通知用)

1. Slackワークスペースの管理画面 → [Incoming Webhooks](https://api.slack.com/messaging/webhooks) を有効化したAppを作成(または既存のAppに追加)
2. 通知を流したいチャンネルを選んでWebhook URLを発行(`https://hooks.slack.com/services/...`)
3. このURLを`SLACK_WEBHOOK_URL`としてA6のサーバー起動時に渡す(未設定でもサーバーは起動するが通知は送られない)

### A4a. Firebaseプロジェクト作成(要件定義8章[v2]、遠隔コマンドのFCM配信用)

[Firebaseコンソール](https://console.firebase.google.com/)で新規プロジェクトを作成する。

1. プロジェクト設定 → サービスアカウント → 「新しい秘密鍵の生成」→ JSONをダウンロードし、サーバーインスタンスの`/etc/fraudguard/fcm-service-account.json`に配置(A6の`FCM_SERVICE_ACCOUNT_PATH`で参照)
2. プロジェクト設定 → 全般 → 「アプリを追加」→ Android
   - パッケージ名: `com.fraudguard.monitor`(`monitor-android/app/build.gradle.kts`の`applicationId`と一致させる)
   - `google-services.json` をダウンロード(Part B1で使う)

未設定でもサーバーは正常起動し、遠隔切断コマンドはpendingポーリング経由(8.1章[v2])で配信される(即時性は落ちる)。

### A5. 遠隔コマンド署名鍵の生成(要件定義8.1章)

BouncyCastle実装(`server/security/CommandSigner.kt`, `server/security/CommandKeys.kt`)に合わせたEd25519鍵ペアを、サーバーインスタンス上で生成し、外に出さない:

```bash
openssl genpkey -algorithm ed25519 -out /etc/fraudguard/command-signing-private.pem
sudo mkdir -p /etc/fraudguard
sudo mv command-signing-private.pem /etc/fraudguard/
sudo chmod 600 /etc/fraudguard/command-signing-private.pem
```

このPEMは`server/security/CommandKeys.kt`が`application.conf`の`commandSigning.privateKeyPath`経由で読み込み、遠隔コマンドの署名・ペアリング時の公開鍵配布に使う(実装・統合テスト済み)。

### A6. サーバーのビルド・デプロイ

インスタンス上でリポジトリを取得してビルド(このリポジトリをGitHub等にpushしておくか、`scp`で転送):

```bash
git clone <あなたのリポジトリURL> fraudguard
cd fraudguard/server
docker build -t fraudguard-server .
```

PostgreSQLをコンテナで起動(要件定義36.2章: 自前ホストでコスト削減):

```bash
docker network create fraudguard-net

docker run -d --name fraudguard-db --network fraudguard-net \
  -e POSTGRES_DB=fraudguard -e POSTGRES_USER=fraudguard -e POSTGRES_PASSWORD='<強いパスワードに変更>' \
  -v fraudguard-db-data:/var/lib/postgresql/data \
  --restart unless-stopped \
  postgres:16
```

アプリ本体を起動:

```bash
docker run -d --name fraudguard-server --network fraudguard-net \
  -p 127.0.0.1:8080:8080 \
  -e DATABASE_URL='jdbc:postgresql://fraudguard-db:5432/fraudguard' \
  -e DATABASE_USER='fraudguard' \
  -e DATABASE_PASSWORD='<A6で設定した値>' \
  -e COGNITO_ISSUER='https://cognito-idp.ap-northeast-1.amazonaws.com/<UserPoolId>' \
  -e COGNITO_AUDIENCE='<アプリクライアントID>' \
  -e SLACK_WEBHOOK_URL='<A4で発行したWebhook URL>' \
  -e PUBLIC_BASE_URL='https://<A7で設定するドメイン>' \
  -v /etc/fraudguard:/etc/fraudguard:ro \
  --restart unless-stopped \
  fraudguard-server
```

`PUBLIC_BASE_URL`は、Slack通知に載せる「家族の通話としてマーク」リンク(要件定義10.3章)の生成に使う、外部から到達できるこのサーバーのURL。A7で設定するドメインと一致させること。未設定でも通知は送られるが、リンクは載らない。

`FCM_SERVICE_ACCOUNT_PATH`のデフォルト値(`/etc/fraudguard/fcm-service-account.json`)は`-v /etc/fraudguard:/etc/fraudguard:ro`で既にマウント済みのパスと一致するため、A4aでそのファイル名のまま配置していれば追加の`-e`は不要。

`-p 127.0.0.1:8080:8080` としてローカルにのみbindし、外部公開はA7のCaddy経由に限定する(要件定義25章: HTTPS/TLS必須)。

### A7. リバースプロキシ + 自動TLS(Caddy)

ドメインの Aレコード を Lightsailの静的IPへ向けておくこと。

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy
```

`/etc/caddy/Caddyfile`:

```
api.example.com {
    reverse_proxy localhost:8080
}
```

```bash
sudo systemctl reload caddy
```

Let's Encrypt証明書は初回アクセス時に自動取得される。

### A8. 動作確認

```bash
curl https://api.example.com/health
# => {"status":"ok"}
```

---

## Part B: Android(monitor-android/、サイドロード)

### B1. Firebase設定の反映(要件定義8章[v2]、遠隔コマンドのFCM即時配信用)

A4aでダウンロードした `google-services.json` を `monitor-android/app/google-services.json` に配置する(`.gitignore`済みなので手元のみ)。未配置でもビルドは通る(`app/build.gradle.kts`参照。ただしFCMトークンが取得できず、遠隔切断はpendingポーリング頼りになる)。

`pairing.PairingRepository`は実装済みのため、ペアリング完了後は`FraudGuardMessagingService.onNewToken`が実際にサーバーへFCMトークンを登録する(`POST /devices/{deviceId}/fcm-token`)。`onMessageReceived`でのコマンド受信〜検証〜結果報告、およびROLE_DIALER化(B4a参照)も実装済みのため、実機でロールを付与すれば遠隔切断コマンドが実際に通話を切断できる状態になっている(未検証、下記「次にやること」参照)。

### B2. サーバーURLの反映

`monitor-android/app/src/main/kotlin/com/fraudguard/monitor/data/remote/ApiClient.kt`:

```kotlin
private const val BASE_URL = "https://api.example.com" // A7で設定した実ドメイン
```

### B3. リリース用署名付きビルド

サイドロード運用でも、後々アップデートを配布するなら署名鍵を固定しておく(鍵が変わると同一アプリとして上書きインストールできなくなる)。

```bash
cd monitor-android
keytool -genkey -v -keystore fraudguard-release.keystore -alias fraudguard \
  -keyalg RSA -keysize 2048 -validity 10000
# パスワード等は安全に保管し、リポジトリにはコミットしない
```

`app/build.gradle.kts` の `android {}` ブロックに署名設定を追加(値は`gradle.properties`や環境変数から読む形にし、ハードコードしない):

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(System.getenv("FRAUDGUARD_KEYSTORE_PATH") ?: "fraudguard-release.keystore")
        storePassword = System.getenv("FRAUDGUARD_KEYSTORE_PASSWORD")
        keyAlias = "fraudguard"
        keyPassword = System.getenv("FRAUDGUARD_KEY_PASSWORD")
    }
}
buildTypes {
    release {
        signingConfig = signingConfigs.getByName("release")
        // ...
    }
}
```

ビルド:

```bash
export FRAUDGUARD_KEYSTORE_PATH=/path/to/fraudguard-release.keystore
export FRAUDGUARD_KEYSTORE_PASSWORD='...'
export FRAUDGUARD_KEY_PASSWORD='...'
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

### B4. 実機への配布(サイドロード)

対象端末(家族の同意済み端末)側で:

1. 設定 → セキュリティ → 「提供元不明のアプリ」を許可(Android 8+はアプリ単位、例: ファイルマネージャアプリに許可)
2. `app-release.apk` を端末に転送(USB、または信頼できる方法でファイル共有)し、タップしてインストール

もしくはPCから直接:

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

### B5. インストール後の権限付与(要件定義4.3章, 34.5章)

ペアリング完了後、アプリが自動的に権限リクエスト画面を表示する(`ui/onboarding/PermissionRequestScreen.kt`):

- 電話の状態・通話履歴・SMS等のランタイム権限は画面内の「許可する」ボタンから一括リクエストされる
- 通知へのアクセス(NotificationListenerService)はランタイムダイアログの対象外のため、画面の「通知アクセスの設定を開く」ボタンから設定画面(アプリ → 特別なアクセス → 通知アクセス)へ遷移し、手動でFraudGuard Monitorを有効化する

いずれも「あとで設定する」で先に進めるため、権限が未許可のままダッシュボードに到達すること自体は正常(監視の一部機能が動かないだけ)。

### B5a. ROLE_DIALER(デフォルト電話アプリ)の付与(要件定義8章)

遠隔通話切断を使うにはこの手順が必要。ダッシュボード画面の「デフォルトの電話アプリに設定」ボタンから`RoleManager`のロール要求ダイアログが出るので許可する(設定 → アプリ → 標準のアプリ → 電話アプリ、から手動で切り替えることも可能)。

**必ず確認すること(実機未検証のため):**

1. 付与後もダイヤルパッドから発信できること(`DialerActivity`)
2. 着信時に自前の着信画面が表示され、応答/拒否ができること(`InCallActivity`)
3. 通話中に家族アプリから切断コマンドを送り、実際に切断されること
4. 何らかの理由で不具合が出た場合、設定からデフォルト電話アプリを元(端末標準の電話アプリ)に戻せること(切り戻し手段を必ず確認してから家族の端末に導入する)

---

## Part C: 家族向けWebアプリ(family-web/)

要件定義36章の方針に合わせ、同じLightsailインスタンスにもう1コンテナとして同居させるのが最もコストが増えない。

### C1. デプロイ

サーバーと同じインスタンス上で:

```bash
cd ~/fraudguard/family-web
docker build -t fraudguard-family -f Dockerfile .  # Dockerfileは後述の通り新規追加が必要
docker run -d --name fraudguard-family --network fraudguard-net \
  -p 127.0.0.1:3000:3000 \
  -e NEXTAUTH_URL='https://family.example.com' \
  -e NEXTAUTH_SECRET='<openssl rand -base64 32 で生成>' \
  -e COGNITO_CLIENT_ID='<A3のアプリクライアントID>' \
  -e COGNITO_CLIENT_SECRET='<A3のシークレット>' \
  -e COGNITO_ISSUER='https://cognito-idp.ap-northeast-1.amazonaws.com/<UserPoolId>' \
  -e NEXT_PUBLIC_API_BASE_URL='https://api.example.com' \
  --restart unless-stopped \
  fraudguard-family
```

**注意:** `family-web/`にはまだ`Dockerfile`が無い。Next.jsの[standalone output](https://nextjs.org/docs/app/api-reference/config/next-config-js/output)を使う一般的な構成で追加が必要(次のステップとして着手可能)。

`Caddyfile`に追記:

```
family.example.com {
    reverse_proxy localhost:3000
}
```

### C2. 代替案

自分でコンテナ運用したくない場合、Next.jsアプリ単体なら[Vercel](https://vercel.com/)の無料枠に置く方が圧倒的に楽(Next.js標準のホスティング先で、環境変数もダッシュボードから設定可能)。その場合サーバー(`api.example.com`)だけAWSに残し、`NEXT_PUBLIC_API_BASE_URL`でCORS設定(現状サーバー側にCORS設定が無いため、Vercelから叩く場合は`server/plugins`に`CORS`プラグインの追加が必要)。要件定義はAWS一本化を推奨しているが、コスト差はほぼ無いので好みで選んで良い。

---

## 次にやること

1. ~~`server/routes/`のTODO実装~~ — 完了(イベント永続化・ホワイトリスト/ブラックリストCRUD・ペアリング・遠隔コマンド署名は実装・統合テスト済み)
2. ~~`family-web/`用`Dockerfile`の追加~~ — 完了(C1参照)
3. ~~`command/CommandSigner`のPEMローダー実装~~ — 完了(`security/CommandKeys.kt`)
4. ~~`domain/risk/RiskEngine.kt` の実装~~ — 完了(7章・14章の警告ルール・相関判定、単体・統合テスト済み)
5. ~~家族への通知~~ — 完了。Firebase不要のSlack Incoming Webhookで実装(`notify/SlackNotifier.kt`, 要件定義2.2章)
6. ~~8章: 遠隔コマンドのFCM即時送信~~ — サーバー側は完了(`push/FcmClient.kt`, `push/FcmClientProvider.kt`, 統合テスト済み)
7. ~~Android側: `pairing.PairingRepository`の実装~~ — 完了(EncryptedSharedPreferences、Robolectric+MockWebServerでテスト済み)。ペアリング・FCMトークン登録・ハートビート送信が実際にサーバーへ接続される
8. ~~Android側: `FraudGuardMessagingService.onMessageReceived`でdata messageを`command.RemoteCommandExecutor`へ渡す配線~~ — 完了。署名検証・期限切れ・二重実行防止・実行結果報告まで実装、7ケースの単体テスト済み(`command/RemoteCommandExecutorTest`)
9. ~~Android側: ROLE_DIALER化(`call/FraudGuardInCallService`の有効化)~~ — 実装完了。`call/DialerActivity`(ACTION_DIALハンドラ+発信)、`call/InCallActivity`(応答/拒否/終話)、`ui/dashboard/DashboardScreen`のロール取得ボタンを追加。**この環境にはAndroidエミュレータが無いため実機での動作確認が未了**(B5a参照) — 次に着手するなら最優先でここを検証すること
10. ~~35.3章の死活監視ジョブ~~ — 完了。`db/repository/HeartbeatWatchdog.kt`が15分ごとに全端末の最終ハートビートを確認し、4時間途絶でSlack通知。通知アクセス失効の即時検知(`routes/HeartbeatRoutes.kt`)と合わせて実装、いずれも重複抑制込みで統合テスト済み
11. ~~Android側の権限リクエストフロー実装~~ — 完了。`ui/onboarding/PermissionRequestScreen.kt`がランタイム権限の一括リクエストと通知アクセス設定画面への誘導を行う(`permission/RequiredPermissionsTest`で4ケース検証済み)
12. `DialerActivity`/`InCallActivity`の使い勝手向上(連絡先、通話履歴)。現状は必要最小限の機能のみ
13. 35.3章の閾値(4時間, 15分間隔)を将来的に設定可能にする(現状はコード内定数)
14. **実機での動作確認一式**(この環境にはエミュレータが無く未検証): ROLE_DIALER付与・着信/発信・遠隔切断・権限リクエストダイアログの実際の表示と挙動

AWSの実際の作業(Lightsail作成、Cognito作成等)はコンソール操作かつ課金が発生するため、こちらで代行実行はしていない。`! aws configure`でCLI認証情報を渡してもらえれば、CLIで実行できる部分(Lightsailインスタンス作成、Route53設定等)はこちらで進めることも可能。
