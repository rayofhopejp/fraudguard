# デプロイ手順

`server/` と `family-web/` をAWS Lightsailへ、`monitor-android/` を実機へサイドロードするための手順書。

このドキュメントは**実際に構築した手順**を記録したもので、途中で踏んだ落とし穴もそのまま残してある。特に「ローカルでは絶対に再現せず、デプロイして初めて壊れる」類のものが複数あったため、該当箇所に理由を書いている。

---

## 0. 現在の構成

| 項目 | 値 |
|---|---|
| APIサーバー | `https://<APIドメイン>` |
| 家族向けWeb | `https://<家族Webドメイン>` |
| Lightsail | `fraudguard-server` / ap-northeast-1a / Ubuntu 24.04 / `small_3_0`(2GB, 月$12) |
| 静的IP | `<静的IP>` |
| DNS | Route 53(`<ドメイン>` のホストゾーン) |
| コンテナ | `app`(Ktor) / `db`(PostgreSQL 16) / `web`(Next.js) / `caddy` |
| Cognito | User Pool `<UserPoolId>` / クライアント `<ClientId>` |
| バックアップ | Lightsail自動スナップショット(日次 19:00 UTC)+ `pg_dump`(日次 18:30 UTC, 7世代) |

**実機で検証済み:** ペアリング、着信/発信の検知、通話時間の警告、SMS監視、アプリインストール検知、アプリ初回起動検知、LINE通話の検知・通話時間・遠隔切断、通常電話の遠隔切断(Family WebおよびSlack経由)、Slack通知、ハートビート、サーバー再起動からの自動復帰(36秒)、端末再起動からの復帰。

**未実施:** FCM(Firebase未設定。遠隔コマンドは通話中5秒ポーリングで配信されるため実用上は動作するが、通話していない時間帯は最長15分の遅延)。

秘密情報はすべてサーバー上の `/etc/fraudguard/`(パーミッション600)に置き、リポジトリにもこのドキュメントにも含めない。

- `command-signing-private.pem` — 遠隔コマンド署名用Ed25519秘密鍵(要件8.1章)
- `slack-webhook-url` — Slack Incoming Webhook(URL自体が認証情報)
- `cognito-client-secret` — Cognitoアプリクライアントシークレット
- `nextauth-secret` — NextAuthのセッション暗号鍵
- `db-password` — PostgreSQLのパスワード(サーバー上で生成)

---

## Part A: サーバー(AWS Lightsail)

以下はすべてAWS CLIで実行できる。`aws login` 済みで、リージョンが `ap-northeast-1` に設定されていること。

### A1. SSH鍵とインスタンス

鍵はAWSに作らせず**手元で生成して公開鍵だけを登録する**。`create-key-pair` は秘密鍵をAPIレスポンスとして返すため、ログや端末履歴に残る。

```bash
ssh-keygen -t ed25519 -f ~/.ssh/fraudguard-lightsail -N "" -C "fraudguard-lightsail"
aws lightsail import-key-pair --key-pair-name fraudguard-key \
  --public-key-base64 "$(cat ~/.ssh/fraudguard-lightsail.pub)"

aws lightsail create-instances \
  --instance-names fraudguard-server \
  --availability-zone ap-northeast-1a \
  --blueprint-id ubuntu_24_04 \
  --bundle-id small_3_0 \
  --key-pair-name fraudguard-key
```

`--public-key-base64` という名前だが、渡すのは**公開鍵ファイルの中身そのまま**。base64で再エンコードすると `InvalidInputException` になる。

プランは `small_3_0`(2GB, 月$12)。1GBだとPostgreSQL・Ktor・Next.js・Caddyの同居が窮屈で、ビルドも別マシンで行う前提になる。

### A2. 静的IPとファイアウォール

```bash
aws lightsail allocate-static-ip --static-ip-name fraudguard-ip
aws lightsail attach-static-ip --static-ip-name fraudguard-ip --instance-name fraudguard-server
aws lightsail put-instance-public-ports --instance-name fraudguard-server --port-infos \
  '[{"fromPort":22,"toPort":22,"protocol":"tcp"},{"fromPort":80,"toPort":80,"protocol":"tcp"},{"fromPort":443,"toPort":443,"protocol":"tcp"}]'
```

8080は開けない。アプリはCaddy経由でのみ公開する(要件25章: HTTPS必須)。この「アプリのポートを外部に晒さない」構成は、後述の `XForwardedHeaders` を信頼できる根拠にもなっている。

### A3. DNS(Route 53)

```bash
aws route53 change-resource-record-sets --hosted-zone-id <ゾーンID> --change-batch '{
  "Changes": [
    {"Action":"UPSERT","ResourceRecordSet":{"Name":"<APIドメイン>","Type":"A","TTL":300,
      "ResourceRecords":[{"Value":"<静的IP>"}]}},
    {"Action":"UPSERT","ResourceRecordSet":{"Name":"<家族Webドメイン>","Type":"A","TTL":300,
      "ResourceRecords":[{"Value":"<静的IP>"}]}}
  ]}'
```

**ドメインは最初に決めること。** `PUBLIC_BASE_URL`(Slackのマークリンク)、`NEXT_PUBLIC_API_BASE_URL`(Family Webのバンドルに焼き込まれる)、Cognitoのコールバック、AndroidのAPKに埋め込むURL——すべてこれに依存し、後から変えると全部を作り直すことになる。

### A4. Cognito User Pool

```bash
POOL=$(aws cognito-idp create-user-pool --pool-name fraudguard-family \
  --auto-verified-attributes email --username-attributes email \
  --policies '{"PasswordPolicy":{"MinimumLength":12,"RequireUppercase":true,"RequireLowercase":true,"RequireNumbers":true,"RequireSymbols":false}}' \
  --query "UserPool.Id" --output text)

aws cognito-idp create-user-pool-domain --domain <ホストUIドメイン接頭辞> --user-pool-id "$POOL"

aws cognito-idp create-user-pool-client --user-pool-id "$POOL" \
  --client-name fraudguard-family-web --generate-secret \
  --allowed-o-auth-flows code --allowed-o-auth-scopes openid email profile \
  --allowed-o-auth-flows-user-pool-client --supported-identity-providers COGNITO \
  --callback-urls "https://<家族Webドメイン>/api/auth/callback/cognito" \
  --logout-urls "https://<家族Webドメイン>"
```

**コールバックURLの末尾は `cognito`。** NextAuthのCognitoProviderのプロバイダIDが `cognito` であるため。サーバー側のKtor認証レルム名(`family-auth`)とは無関係で、ここを取り違えると `redirect_mismatch` になる。実際のIDは `https://<家族Webドメイン>/api/auth/providers` を叩けば確認できる。

家族ユーザーの作成:

```bash
aws cognito-idp admin-create-user --user-pool-id "$POOL" \
  --username <メールアドレス> \
  --user-attributes Name=email,Value=<メールアドレス> Name=email_verified,Value=true \
  --message-action SUPPRESS
```

パスワードは本人に設定してもらう(会話・ログに残さない):

```bash
read -rsp "password: " P && aws cognito-idp admin-set-user-password \
  --user-pool-id "$POOL" --username <メールアドレス> --password "$P" --permanent && unset P
```

MFAの有効化を推奨する。このアカウントは通話履歴とSMS本文を閲覧でき、通話を遠隔で切断できる(要件25章)。

### A5. Slack Incoming Webhook

[Incoming Webhooks](https://api.slack.com/messaging/webhooks) を有効にしたAppを作り、通知先チャンネルのWebhook URLを発行する。URL自体が認証情報なので、サーバーの `/etc/fraudguard/slack-webhook-url` に直接置き、コマンドライン引数やログに出さない。

### A6. 署名鍵の生成

```bash
ssh -i ~/.ssh/fraudguard-lightsail ubuntu@<静的IP>
sudo mkdir -p /etc/fraudguard && sudo chown ubuntu:ubuntu /etc/fraudguard && chmod 700 /etc/fraudguard
openssl genpkey -algorithm ed25519 -out /etc/fraudguard/command-signing-private.pem
chmod 600 /etc/fraudguard/command-signing-private.pem
```

### A7. サーバーのビルドと配置

**fat JARは手元でビルドしてから転送する。** 2GBのインスタンス上でGradleを回すとPostgreSQLとKtorを圧迫する。

```bash
cd server && ./gradlew buildFatJar
scp -i ~/.ssh/fraudguard-lightsail build/libs/fraudguard-server-all.jar ubuntu@<静的IP>:~/fraudguard/app.jar
scp -i ~/.ssh/fraudguard-lightsail secrets/slack-webhook-url ubuntu@<静的IP>:/etc/fraudguard/
```

> **重要:** `server/build.gradle.kts` の `mergeServiceFiles()` を消さないこと。
> Ktorのfat JAR(Shadow)は既定で `META-INF/services` を**マージせず上書き**する。Flyway 10はプラグイン方式で、`flyway-core` が21件、`flyway-database-postgresql` が2件を同じサービスファイル名で登録しており、上書きによってcoreの21件が丸ごと失われる。その結果Flywayは正しい名前のマイグレーションを「命名規則に合わない」と判断して読み飛ばし、**スキーマが空のままサーバーが正常起動する**(`/health` は200を返し、エラーログも出ない)。`./gradlew run` はリソースをディレクトリから読むためローカルでは絶対に再現しない。
> 保険として `DatabaseFactory` は `validateMigrationNaming` を有効にし、マイグレーションが1件も見つからなければ起動を止める。この確認は「見つかった数」を見ており、「適用した数」ではない(適用済みのDBでは0件適用が正常)。

サーバー上のファイル構成:

```
~/fraudguard/
  app.jar        # 転送したfat JAR
  Dockerfile     # JREにapp.jarを載せるだけの薄いもの
  compose.yaml
  Caddyfile
  .env           # 600。秘密情報は /etc/fraudguard から読んで生成する
```

`Dockerfile`:

```dockerfile
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`Caddyfile`:

```
<APIドメイン> {
	reverse_proxy app:8080
}

<家族Webドメイン> {
	reverse_proxy web:3000
}
```

`compose.yaml` の要点(全文はサーバー上を参照):

- `db` は `postgres:16`、`pgdata` ボリュームで永続化、`pg_isready` のヘルスチェック付き
- `app` は上記Dockerfileをビルドし、`/etc/fraudguard` を読み取り専用でマウント
- `caddy` は 80/443 を公開し、証明書を `caddydata` ボリュームに保存(**再起動時の再取得を避ける。Let's Encryptのレート制限に当たる**)
- 全サービスに `restart: unless-stopped`

`app` に渡す環境変数:

| 変数 | 用途 |
|---|---|
| `DATABASE_URL` / `DATABASE_USER` / `DATABASE_PASSWORD` | PostgreSQL接続 |
| `COMMAND_SIGNING_PRIVATE_KEY_PATH` | `/etc/fraudguard/command-signing-private.pem` |
| `SLACK_WEBHOOK_URL` | 家族通知(要件2.2章) |
| `PUBLIC_BASE_URL` | Slack通知に載せる「家族の通話としてマーク」リンクの生成(要件10.3章) |
| `FAMILY_WEB_ORIGIN` | CORSで許可するオリジン。**未設定だとFamily Webからブラウザ経由でAPIを呼べない** |
| `COGNITO_ISSUER` / `COGNITO_AUDIENCE` | family-authのJWT検証 |

秘密情報は `.env` に集約し、`compose.yaml` からは `${...}` で参照する(コマンドラインやシェル履歴に出さない):

```bash
{
  echo "DB_PASSWORD=$(cat /etc/fraudguard/db-password)"
  echo "SLACK_WEBHOOK_URL=$(cat /etc/fraudguard/slack-webhook-url)"
  echo "COGNITO_CLIENT_SECRET=$(cat /etc/fraudguard/cognito-client-secret)"
  echo "NEXTAUTH_SECRET=$(cat /etc/fraudguard/nextauth-secret)"
} > ~/fraudguard/.env
chmod 600 ~/fraudguard/.env
```

起動:

```bash
sudo docker compose up -d --build
```

> **JARを更新したら必ず `--build` を付けること。** `scp` でホスト側の `app.jar` を置き換えても、`docker compose exec` や実行中のコンテナが使うのは**イメージに焼き込まれた古いJAR**。「直したはずのものが動かない」で時間を溶かす。

### A8. 動作確認

```bash
curl https://<APIドメイン>/health   # => {"status":"ok"}
curl -s https://<家族Webドメイン>/api/auth/providers
```

サーバーログでマイグレーションとCORSを確認する:

```bash
sudo docker compose logs app | grep -E "Successfully applied|up to date|CORS enabled"
```

### A9. バックアップ

**インスタンス全体の自動スナップショット**(日次、7日保持)。DBだけでなく `/etc/fraudguard` の鍵類・compose設定・Caddyの証明書まで含まれる。

```bash
aws lightsail enable-add-on --resource-name fraudguard-server \
  --add-on-request 'addOnType=AutoSnapshot,autoSnapshotAddOnRequest={snapshotTimeOfDay=19:00}'
```

**PostgreSQLの論理バックアップ**(日次 18:30 UTC、7世代)。`/usr/local/bin/fraudguard-backup.sh` を `fraudguard-backup.timer` が実行する。スナップショットの30分前に取ることで、当日のダンプがスナップショットにも含まれる。

2層にしているのは用途が違うため。スナップショットは「インスタンスごと戻す」もので、「データだけ戻す」「昨日の状態を別DBに展開して中身を見る」には使えない。逆に論理ダンプだけでは鍵も設定も戻らない。

復元の確認(**取ったことのないバックアップは信用できない**):

```bash
cd ~/fraudguard
sudo docker compose exec -T db psql -U fraudguard -d postgres -c "CREATE DATABASE restoretest;"
sudo gzip -dc $(ls -t /var/backups/fraudguard/*.sql.gz | head -1) \
  | sudo docker compose exec -T db psql -U fraudguard -d restoretest
# 本番と件数を照合したうえで
sudo docker compose exec -T db psql -U fraudguard -d postgres -c "DROP DATABASE restoretest;"
```

S3への転送は入れていない。LightsailはEC2と違いIAMインスタンスプロファイルが使えず、長期有効なアクセスキーをサーバー上に置くことになる。通話履歴とSMS本文を持つサーバーで、現状の規模では割に合わないと判断した。アカウント単位の事故やリージョン障害まで備えるなら、別アカウントのS3への複製を検討する。

---

## Part B: 家族向けWeb(family-web/)

同じインスタンスにコンテナとして同居させる。追加費用がなく、DNSもTLSも既存の仕組みに乗る。

イメージは**手元でビルドして転送する**(2GB上で `npm run build` を回すとPostgreSQLとKtorを圧迫する):

```bash
cd family-web
docker build --build-arg NEXT_PUBLIC_API_BASE_URL=https://<APIドメイン> \
  -t fraudguard-family-web:latest .
docker save fraudguard-family-web:latest | gzip -1 \
  | ssh -i ~/.ssh/fraudguard-lightsail ubuntu@<静的IP> "gunzip | sudo docker load"
ssh -i ~/.ssh/fraudguard-lightsail ubuntu@<静的IP> \
  "cd ~/fraudguard && sudo docker compose up -d --force-recreate web"
```

> **`NEXT_PUBLIC_API_BASE_URL` はビルド引数として渡すこと。** `NEXT_PUBLIC_*` はNext.jsが**ビルド時にクライアントのJavaScriptへ直接埋め込む**変数で、実行時の環境変数では届かない。未指定だと空文字で焼き込まれ、ブラウザはAPIサーバーではなく**自分自身への相対パス**を叩き、Next.jsが404を返す。「家族が押した遠隔切断がAPIに一度も届かない」という形で表面化した。Dockerfileはこの引数が無ければビルドを失敗させる。
> 焼き込まれたか確認するには: `docker run --rm --entrypoint sh <image> -c "grep -rhoE 'https://[a-z0-9.-]+' .next/static | sort -u"`

`web` に渡す環境変数: `NEXTAUTH_URL` / `NEXTAUTH_SECRET` / `COGNITO_ISSUER` / `COGNITO_CLIENT_ID` / `COGNITO_CLIENT_SECRET` / `NEXT_PUBLIC_API_BASE_URL`。

### ブラウザからAPIを呼ぶための設定

Family WebとAPIはドメインが別なので、`EventActions` のようなクライアントコンポーネントからの呼び出しには**CORSが必要**。サーバー側は `FAMILY_WEB_ORIGIN` で許可するオリジンを1つだけ指定する。ワイルドカードは使わない——このAPIは通話履歴とSMS本文を扱い、進行中の通話を切れる。

あわせて **`XForwardedHeaders` が必須**。Caddyの背後ではKtorは自分のオリジンを `http://` と認識するため、ブラウザが送る `Origin: https://...` と食い違い、**同一オリジンのフォーム送信すら403になる**(Slackの「家族の通話としてマーク」の確認画面がこれで弾かれた)。アプリのポートを外部に公開していないので、このヘッダは信頼できる。

確認:

```bash
# 許可オリジンからのプリフライトは200
curl -si -X OPTIONS https://<APIドメイン>/devices/<id>/commands \
  -H "Origin: https://<家族Webドメイン>" -H "Access-Control-Request-Method: POST" | head -5
# 無関係なオリジンは403
curl -so /dev/null -w "%{http_code}\n" -X OPTIONS https://<APIドメイン>/devices/<id>/commands \
  -H "Origin: https://evil.example.com" -H "Access-Control-Request-Method: POST"
```

### 端末の所有者

シードツール(`SeedTestDeviceKt`)が作る端末は、ダミーの家族ユーザーが所有している。Cognitoでログインすると**別の家族ユーザーが新規作成される**ため、そのままでは端末一覧が空になる。ログイン後に所有者を付け替える:

```sql
UPDATE monitored_devices SET owner_family_user_id='<あなたのfamily_user_id>' WHERE id='<deviceId>';
UPDATE device_members  SET family_user_id='<あなたのfamily_user_id>' WHERE device_id='<deviceId>';
```

---

## Part C: Android(monitor-android/、サイドロード)

### C1. サーバーURLの反映

`ApiClient.kt` を書き換えるのではなく、ビルド時に差し替える(`app/build.gradle.kts` が `BuildConfig.API_BASE_URL` を生成する)。**Retrofitの制約でURLは `/` で終えること。**

`monitor-android/local.properties`(gitignore済み)に書くのが確実:

```properties
fraudguard.apiBaseUrl=https://<APIドメイン>/
```

コマンドラインでも渡せる: `./gradlew assembleDebug -Pfraudguard.apiBaseUrl=https://.../`

> 指定を忘れるとプレースホルダの `https://api.fraudguard.example.com/` が焼き込まれ、**イベントは端末内に溜まり続けたまま何のエラーも出ない**。再ビルドのたびに指定を忘れないよう `local.properties` に書いておくこと。

### C2. Firebase(任意、未実施)

`google-services.json` を `monitor-android/app/` に置くとFCMが有効になる。未配置でもビルドは通り、遠隔コマンドはポーリングで配信される(通話中は5秒間隔、通話外は最長15分)。

### C3. ビルドとインストール

```bash
cd monitor-android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

配布用にはリリース署名を固定しておく(鍵が変わると上書きインストールできない)。`keytool` で作成した keystore のパスとパスワードは環境変数から読み、リポジトリに入れない。

### C4. ペアリング

サーバー側でコードを発行する:

```bash
ssh -i ~/.ssh/fraudguard-lightsail ubuntu@<静的IP> \
  "cd ~/fraudguard && sudo docker compose exec -T app java -cp app.jar com.fraudguard.server.tools.SeedTestDeviceKt '監視端末'"
```

表示された8文字のコードをアプリのペアリング画面に入力する(有効期限15分)。

**サーバーを移設した場合はペアリングをやり直す。** deviceIdとAPIキーは旧サーバーのDBにあるため、新しいサーバーからは無効。権限やデフォルト電話アプリの設定まで失わずにペアリングだけ消すには:

```bash
adb shell run-as com.fraudguard.monitor rm shared_prefs/fraudguard_pairing.xml
adb shell am force-stop com.fraudguard.monitor
```

`pm clear` は権限もロールも全部消えるので使わない。

### C5. 権限の付与

ペアリング完了後、権限リクエスト画面が出る(`ui/onboarding/PermissionRequestScreen.kt`)。

- 通話・SMS等のランタイム権限 — 画面内のボタンから一括リクエスト
- **通知へのアクセス** — 設定画面へ誘導。LINE等のアプリ内通話の検知・通話時間・遠隔切断はすべてこれに依存する(要件10.3章)
- **使用状況へのアクセス** — アプリの初回起動検知に使う(要件13章)。`adb shell appops set` では**不十分**で、設定画面から正式に許可する必要がある

いずれも「あとで設定する」で先に進める。未許可でも他の監視は動く。

### C6. ROLE_DIALER(デフォルト電話アプリ)

通常電話の遠隔切断に必要。ダッシュボードのボタンから付与する。

**家族の端末に導入する前に、設定 → アプリ → 標準のアプリ → 電話アプリ から元に戻せることを必ず確認すること。**

---

## 運用メモ

### 再起動からの復帰(要件30章・35章)

実測済み。サーバーは再起動コマンドから**36秒**でAPIが応答を再開し、4コンテナすべてが自動復帰、データも保全され、Caddyは証明書を再取得せずボリュームから再利用した。端末側も再起動後にクラッシュせず、ペアリング情報・デフォルト電話アプリ・通知アクセス・使用状況アクセス・WorkManagerのジョブがすべて維持され、30分周期のハートビートも継続した。

なお**ワイヤレスデバッグは端末再起動で切断され、ポート番号も変わる**。再起動を挟む検証をするならUSB接続のほうが確実。

### イベント再送(要件24章)

`EventSyncWorker` は `ExistingPeriodicWorkPolicy.UPDATE` で登録し、起動のたびに追い付き送信も1回投げる。`KEEP` だと、WorkManagerのDB上はENQUEUEDのままJobSchedulerへの登録だけが失われた状態(強制停止・再インストール・OEMの省電力管理で実際に起きる)から**永久に復帰できない**。この状態では家族に何のエラーも見えないまま、イベントだけが端末内に溜まり続ける。

### 通知の疎通確認

実機操作なしでSlack通知を確認できる:

```bash
# SMS警告のサンプル
sudo docker compose exec -T app java -cp app.jar com.fraudguard.server.tools.SendTestNotificationKt
# 「家族の通話としてマーク」リンク付きのアプリ内通話イベント(実在するdeviceIdを渡すこと)
sudo docker compose exec -T app java -cp app.jar com.fraudguard.server.tools.SendTestNotificationKt <deviceId>
```

---

## 残っている作業

1. **FCM** — Firebaseプロジェクトを作り、サービスアカウントJSONを `/etc/fraudguard/fcm-service-account.json` へ、`google-services.json` を `monitor-android/app/` へ配置する。現状は遠隔コマンドが通話外で最長15分遅れる
2. **通知監視の本体** — `FraudGuardNotificationListenerService` は通話中通知(CallStyle)は処理するが、それ以外の通知から詐欺兆候を推定する部分(要件10.3章のconfidence付き `NOTIFICATION_OBSERVED`)は未実装
3. **端末側RiskEngine**(要件24章、オフライン判定)
4. `DialerActivity` / `InCallActivity` の使い勝手(連絡先・通話履歴)。現状は必要最小限
5. 死活監視の閾値(4時間・15分間隔)を設定可能にする(現状はコード内定数)
6. 呼出中(RINGING)の通話も遠隔切断の対象にするかの仕様判断
