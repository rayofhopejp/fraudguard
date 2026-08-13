# FraudGuard Server

REST API・認証・イベント保存・家族通知・遠隔コマンド管理を担うバックエンド。Kotlin + Ktor + Exposed + PostgreSQL。

要件の詳細は [`../docs/requirements.md`](../docs/requirements.md) を参照。特に以下の章が本コンポーネントに直結する:

- 20.2章: DBスキーマ(`src/main/resources/db/migration/V1__init.sql`, `V2__pairing_codes.sql`, `V3__device_fcm_token.sql` に対応)
- 23章: REST API一覧(`src/main/kotlin/.../routes/`)
- 8.1章, 25章: 遠隔コマンド署名・認証(`security/CommandSigner.kt`, `plugins/Security.kt`)
- 2.2章: 家族への通知(`notify/SlackNotifier.kt`)
- 7章, 14章: RiskEngine(`domain/risk/RiskEngine.kt`)
- 35章: 監視継続性・ハートビート・死活監視(`routes/HeartbeatRoutes.kt`, `db/repository/HeartbeatWatchdog.kt`)
- 36章: AWSインフラ構成

## セットアップ

前提: JDK 17, Docker(ローカルPostgres用), Gradle(wrapper未生成のため一度だけ必要)。

```bash
# 1. Gradle wrapperを生成(初回のみ。IntelliJ IDEAで開いた場合は自動生成されるため不要)
gradle wrapper --gradle-version 8.7

# 2. ローカルPostgreSQLを起動
docker run --name fraudguard-db -e POSTGRES_DB=fraudguard \
  -e POSTGRES_USER=fraudguard -e POSTGRES_PASSWORD=fraudguard \
  -p 5432:5432 -d postgres:16

# 3. 環境変数を設定
cp .env.example .env  # 値を編集

# 4. 起動
./gradlew run
```

`GET http://localhost:8080/health` が `{"status":"ok"}` を返せば起動成功。

## 現状

DB永続化・認証(device-auth/family-auth)・ペアリングフロー・遠隔コマンドのEd25519署名/検証・ホワイトリスト/ブラックリストCRUD・イベントの冪等性・RiskEngine(7章・14章)・Slack通知(2.2章)・遠隔コマンドのFCM即時送信(8章)・死活監視(35章)は実装済みで、`IntegrationSmokeTest`(下記)で実際のPostgreSQLに対して動作確認済み。

### RiskEngine(`domain/risk/RiskEngine.kt`, `db/repository/RiskEvaluationService.kt`)

- 単一イベント判定(7.1〜7.4, 7.6章): 着信/発信ごとに国内/海外・ホワイトリスト/ブラックリスト・番号形式に応じてリスクレベルを判定し、Monitor側の申告値をサーバー側で上書きする(信頼の起点はサーバーのホワイトリストDB)
- 相関判定(14.1〜14.3章): 直近30分のイベント履歴から「通話→遠隔操作アプリ導入→直後起動」「通話→メッセージングアプリ誘導」「通話中の詐欺SMS」を検出し、`CORRELATED_RISK`イベントを生成する(30章: 同一パターンの重複生成は抑制)
- 大量着信判定(7.5章): 直近10分以内に海外番号から3件以上の着信で`CALL_BURST_FOREIGN`イベントを生成する
- `RiskEngine`本体はDB非依存の純粋関数(`RiskEngineTest.kt`でユニットテスト済み)。DB照会(ホワイトリスト確認・履歴取得)は`RiskEvaluationService`が担う

### 家族への通知(`notify/`)

- requirements.md 2.2章の方針(重要な通知はSlackへ)に沿い、Firebaseプロジェクトが無くても使える**Slack Incoming Webhook**を一次的な通知手段として実装。`SLACK_WEBHOOK_URL`環境変数(未設定なら`NoopFamilyNotifier`で何もしない、起動は継続)
- `RiskEvaluationService`がWARNING以上の新規イベント(Monitor再送等の重複は除外)でのみ通知する(30章: アラート疲れ対策)

### 遠隔コマンドのFCM即時送信(`push/`, 要件定義8章[v2])

- Monitorアプリが `POST /devices/{deviceId}/fcm-token` で自身のFCMトークンを登録する(`monitored_devices.fcm_token`)
- `POST /devices/{deviceId}/commands` でコマンドを発行すると、`FirebaseFcmClient` が高優先度data messageで即時送信を試み、成功時のみ`delivered=true`にする
- サービスアカウント未設定・不正・送信失敗時は `NoopFcmClient` にフォールバックし、コマンド自体は失敗させない(8.1章[v2]の`GET /devices/{deviceId}/commands/pending`ポーリングが引き続き機能する)
- `fcm.serviceAccountPath`(`FCM_SERVICE_ACCOUNT_PATH`環境変数)未設定でもサーバーは正常起動する(`FcmClientProviderTest`, `IntegrationSmokeTest`で確認済み)

### 死活監視(`db/repository/DeviceHealthService.kt`, `db/repository/HeartbeatWatchdog.kt`, 要件定義35章[v2])

詐欺犯が被害者に監視アプリの無効化を指示するケース(35.1章)に対応する仕組み。二つの経路で`device_health`イベント(WARNING)を生成し、Slack通知する:

1. **即時検知**(`routes/HeartbeatRoutes.kt`): ハートビートで`notificationListenerEnabled=false`を受信した時点で即座に通知。`roleDialerHeld=false`は対象外(ROLE_DIALERは任意機能のため、未取得自体は異常ではない)
2. **タイムアウト検知**(`HeartbeatWatchdog`): `Application`のライフサイクルに紐づくバックグラウンドコルーチンが15分ごとに全端末の最終ハートビートを確認し、既定4時間(`HeartbeatWatchdog.DEFAULT_TIMEOUT_MINUTES`)を超えて途絶していれば通知する。アプリが完全にアンインストールされた場合、これが唯一の検知手段となる(35.4章)

どちらも`DeviceHealthService`内で6時間の抑制期間(30章: 重複通知の抑制)を共有する。DB未接続等でウォッチドッグの1回のチェックが失敗しても、ジョブ自体は停止せず次の周期で再試行する。`IntegrationSmokeTest`で即時検知・タイムアウト検知・両方の重複抑制を検証済み。

残っている主な `TODO`:

- 35.3章の閾値(4時間, 15分間隔)を将来的に設定可能にする(30章の方針。現状はコード内定数)

## 統合テスト

`src/test/kotlin/.../IntegrationSmokeTest.kt` は実際のPostgreSQLに対してペアリング〜ホワイトリスト〜イベント〜遠隔コマンド署名検証〜ハートビート〜FCMトークン登録〜死活監視(即時検知・タイムアウト検知・重複抑制)までを一通り検証する。`TEST_DATABASE_URL`/`TEST_COMMAND_SIGNING_KEY` が未設定の場合はスキップされる(通常の`./gradlew test`はDB不要)。`SlackNotifierTest`はローカルの偽Webhookサーバーで、`FcmClientProviderTest`はサービスアカウント未設定時のフォールバックを検証する(いずれもDB/外部サービス不要)。

```bash
docker run -d -e POSTGRES_DB=fraudguard -e POSTGRES_USER=fraudguard -e POSTGRES_PASSWORD=fraudguard -p 5432:5432 postgres:16
openssl genpkey -algorithm ed25519 -out /tmp/test-key.pem
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/fraudguard TEST_COMMAND_SIGNING_KEY=/tmp/test-key.pem ./gradlew test
```

## デプロイ

`Dockerfile` はLightsailインスタンス上でのコンテナ実行を想定(要件定義36.2章)。
