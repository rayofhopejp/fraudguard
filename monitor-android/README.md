# FraudGuard Monitor

監視対象Androidアプリ。Kotlin + Jetpack Compose。Android 10 (API 29) 以降が対象(要件定義3章)。

要件の詳細は [`../docs/requirements.md`](../docs/requirements.md) を参照。

## セットアップ

前提: Android Studio(推奨。Gradle wrapperを自動生成してくれる)、または JDK 17 + Gradle。

```bash
# Android Studio以外でビルドする場合、初回のみwrapperを生成
gradle wrapper --gradle-version 8.7
```

**ビルド前に推奨する手動作業:**

1. Firebase Consoleでプロジェクトを作成し、`google-services.json` を `app/` 直下に配置する(要件定義16章のFCM Push用)。未配置でもビルド自体は通る(`app/build.gradle.kts` でファイルが存在する場合のみ `com.google.gms.google-services` プラグインを適用する構成にしている)が、その場合Firebase Pushは実機で動作しない
2. `data/remote/ApiClient.kt` の `BASE_URL` を実際のサーバーエンドポイントに差し替える

## 権限方式の段階分け(要件定義4.3章[v2])— ROLE_DIALER化まで実装済み

`call/CallMonitorService.kt` は `READ_PHONE_STATE` のみで着信/発信/通話時間を取得する軽量監視(ROLE_DIALER不要)。これとは別に、ROLE_DIALER(デフォルト電話アプリ)化に必要な一式も実装済み:

- **`ui/dashboard/DashboardScreen.kt`**: `RoleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)` を呼ぶボタンと、現在の付与状態表示
- **`call/DialerActivity.kt`**: `ACTION_DIAL` ハンドラ(AOSPのDIALERロール定義が必須とする要件)。ダイヤルパッド + `TelecomManager.placeCall()` で実際に発信できる
- **`call/InCallActivity.kt`** + **`ui/incall/InCallScreen.kt`**: デフォルト電話アプリ化するとシステム標準の着信/通話中UIが出なくなるため、代替として自前で表示する画面(応答/拒否/終話)
- **`call/FraudGuardInCallService.kt`**: `onCallAdded`/`onCallRemoved` で`Call`を追跡し、`InCallActivity`を起動。`RemoteCommandExecutor`が参照する「現在ACTIVEな通話ID」もここが正となる

これにより`command/RemoteCommandExecutor`の実行系統(署名検証→期限切れチェック→二重実行防止→`FraudGuardInCallService.instance?.disconnect()`)が実際に機能する状態になっている。

**実機で確認が必要な理由:** この環境にはAndroidエミュレータ/実機が無く(`/dev/kvm`無し)、RoleManagerでの実際のロール付与・着信のシステム連携・画面遷移は実機でしか検証できない。コンパイル・マニフェストのマージ検証・単体テストは通っているが、「実際に電話が使える状態を維持できているか」は必ず実機で確認すること。

## 現状

### ペアリング(`pairing/`, 要件定義34章)— 実装済み

`PairingRepositoryImpl` がペアリングコードの引き換え(`POST /devices/pairing`)と、結果(deviceId/APIキー/サーバー公開鍵)の永続化を行う。APIキーは平文保存せず `EncryptedSharedPreferences`(`androidx.security.crypto`)を使用。`PairingScreen`/`MainActivity` から実際に呼び出される導線も配線済みで、成功するとダッシュボード画面へ遷移する。

これにより以下も実際にサーバーへ接続するようになった:

- `heartbeat/HeartbeatWorker`: 通知アクセス許可・ROLE_DIALER保持状態を含めて`POST /devices/{deviceId}/heartbeat`を送信
- `push/FraudGuardMessagingService.onNewToken`: `POST /devices/{deviceId}/fcm-token`でFCMトークンを登録

`pairing/PairingRepositoryImplTest`(Robolectric + MockWebServerで実際のHTTP往復を検証)で動作確認済み。暗号化ストレージ自体(EncryptedSharedPreferences)はAndroidX側の実装として信頼し、テストではプレーンなSharedPreferencesを注入してペアリングのロジック/永続化契約を検証している(RobolectricはAndroid Keystoreを提供しないため)。

### 遠隔コマンド受信(`command/`, `push/`, 要件定義8章)— 検証ロジックは実装済み

`FraudGuardMessagingService.onMessageReceived` がFCM data messageを`RemoteCommandDto`へ組み立て、`RemoteCommandExecutor`が以下を全て満たさない限り実行しない(8.1章):

- 署名がサーバー公開鍵(ペアリング時に取得)で検証できること(不正なBase64でもクラッシュせず拒否)
- `expiresAt`を過ぎていないこと
- `callId`が`FraudGuardInCallService`が追跡している現在ACTIVEな通話と一致すること(ROLE_DIALER未取得時は常にactiveCallId=nullとなり不一致で拒否される)
- `commandId`が未使用(`UsedCommandDao`による二重実行防止。実行を試みる前に使用済みとして記録)であること

検証を通過すると`FraudGuardInCallService.instance?.disconnect(callId)`を呼び出す。実行結果(成功/拒否理由)は`POST /devices/{deviceId}/commands/{commandId}/report`でサーバーへ報告する。`command/RemoteCommandExecutorTest`(実際のEd25519署名生成・検証を含む7ケース)でロジックを検証済み。

### 権限リクエストフロー(`permission/`, `ui/onboarding/PermissionRequestScreen.kt`, 要件定義34.5章)— 実装済み

`MainActivity`はペアリング成功後、ダッシュボードへ進む前に権限リクエスト画面を挟む(`OnboardingStep.PAIRING → PERMISSIONS → DASHBOARD`)。

- **ランタイム権限**(`READ_PHONE_STATE`, `READ_CALL_LOG`, `RECEIVE_SMS`, `READ_SMS`, API 33+では`POST_NOTIFICATIONS`)を`RequiredPermissions.missingRuntimePermissions()`で判定し、`ActivityResultContracts.RequestMultiplePermissions()`で一括リクエストする
- **通知アクセス**(`NotificationListenerService`)はランタイムダイアログの対象外のため、設定画面(`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`)へ誘導し、画面復帰(`ON_RESUME`)のたびに`NotificationManagerCompat.getEnabledListenerPackages()`で許可状態を再チェックする
- 権限が揃っていなくても「あとで設定する」でダッシュボードへ進める(端末の実利用者が高齢者本人の場合、その場で全て理解・許可できるとは限らないため強制しない設計)

`permission/RequiredPermissionsTest`(Robolectricの`ShadowApplication`で許可/未許可状態をシミュレート、4ケース)で許可状態判定ロジックを検証済み。

### まだ `TODO` の項目

- 各Receiver/Serviceでの実際のイベント生成・`EventRepository`(未実装)への保存
- `risk/RiskEngine`(端末側)の判定ロジック本体。サーバー側`RiskEngine`(7章・14章)は実装済みなので、端末側はオフライン時(24章)の簡易判定用途に絞ってよい
- `data/remote/ApiClient.kt`の`BASE_URL`を実際のサーバーエンドポイントに差し替える(現状はプレースホルダ)
- `DialerActivity`/`InCallActivity`は最小限のUI(ダイヤルパッド、応答/拒否/終話ボタンのみ)。連絡先・通話履歴等、実際の電話アプリとしての使い勝手を高めるなら追加実装が要る
- 実機での動作確認(ROLE_DIALER付与、着信/発信の実地テスト、権限リクエストダイアログの実際の見え方)。この環境にはエミュレータが無く未検証

実装順序は [`../docs/requirements.md`](../docs/requirements.md) 29章のPhase 1に対応させるのが望ましい: ~~ペアリング~~(完了) → 通話監視(`call/`) → ホワイトリスト同期 → ~~ハートビート~~(完了)→ ~~遠隔切断の検証ロジック~~(完了)→ ~~ROLE_DIALER化~~(完了、実機検証待ち)→ ~~権限リクエストフロー~~(完了)。
