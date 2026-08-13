// build.gradle.kts内では `java` がGradleのjava拡張を指してしまい java.util.* が解決できないため、
// 明示的にimportする。
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") apply false
    id("org.jetbrains.kotlin.plugin.serialization")
}

// google-services.json はFirebaseプロジェクトごとの秘密情報のためコミットしない(.gitignore対象)。
// 未配置の開発環境(このスキャフォールディング直後や他の開発者の初回チェックアウト時)でも
// ビルド自体は通るよう、ファイルが存在する場合のみプラグインを適用する。
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

/** local.properties(gitignore済み)から値を読む。実機テスト用のURLをコミットせずに済ませるため。 */
fun localProperty(key: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)
}

android {
    namespace = "com.fraudguard.monitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fraudguard.monitor"
        // requirements.md 3章: Android 10 (API 29) 以降を基本対象とする。
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // requirements.md 23章: サーバーのエンドポイント。ローカル実機テストでは一時的なトンネルURL等を
        // 使うため、ソースを書き換えずにビルド時へ差し替えられるようにする。
        //   ./gradlew assembleDebug -Pfraudguard.apiBaseUrl=https://xxxx.trycloudflare.com/
        // または local.properties(gitignore済み)に fraudguard.apiBaseUrl=... を書く。
        // Retrofitの制約でURLは "/" で終える必要がある。
        val apiBaseUrl = (project.findProperty("fraudguard.apiBaseUrl") as String?)
            ?: localProperty("fraudguard.apiBaseUrl")
            ?: "https://api.fraudguard.example.com/"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    /**
     * 配布用の署名。鍵とパスワードはリポジトリに入れない(いずれも .gitignore 済み)。
     *
     * この鍵が漏れると、同じ署名の偽アプリを作れてしまう。監視対象の端末で
     * 通話・SMS・通知アクセスを持つアプリなので、影響が特に大きい。
     *
     * 鍵が変わると同一アプリとして上書きインストールできなくなるため、
     * 一度作ったら使い続けること(作り直すと家族の端末で入れ直しになる)。
     */
    signingConfigs {
        create("release") {
            val storePath = localProperty("fraudguard.keystorePath")
                ?: System.getenv("FRAUDGUARD_KEYSTORE_PATH")
            val store = storePath?.let { file(it) }
            if (store != null && store.exists()) {
                storeFile = store
                storePassword = localProperty("fraudguard.keystorePassword")
                    ?: System.getenv("FRAUDGUARD_KEYSTORE_PASSWORD")
                keyAlias = localProperty("fraudguard.keyAlias") ?: "fraudguard"
                keyPassword = localProperty("fraudguard.keyPassword")
                    ?: System.getenv("FRAUDGUARD_KEY_PASSWORD")
                    ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 鍵が用意されていない環境では署名なしでビルドする(CI等でビルドだけ確認したい場合)。
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val roomVersion = "2.6.1"
val workVersion = "2.9.0"
val composeBom = "2024.06.00"

dependencies {
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")

    // requirements.md 20.1章: 監視端末ローカルDB (Room)
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // requirements.md 24章: WorkManagerでの再送・ハートビート
    implementation("androidx.work:work-runtime-ktx:$workVersion")

    // requirements.md 5章: 電話番号の正規化・国際判定
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.40")

    // requirements.md 16章: FCM Push受信
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // requirements.md 23章: サーバーREST API呼び出し
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // requirements.md 8.1章: 遠隔コマンド署名検証 (Ed25519)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // requirements.md 25章, 34章: APIキー等のセキュアストレージ(平文保存しない)。
    // 安定版1.0.0はMasterKey Builder APIを持たない(旧MasterKeysユーティリティのみ)ため、
    // Google推奨の現行API(MasterKey.Builder)を使うには1.1.0系が必要。
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
