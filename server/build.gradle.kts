import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("io.ktor.plugin") version "2.3.12"
    application
}

group = "com.fraudguard.server"
version = "0.1.0"

application {
    mainClass.set("com.fraudguard.server.ApplicationKt")
}

repositories {
    mavenCentral()
}

// requirements.md 36章: Lightsail上ではfat JARとして動かす。
// Ktorのfat JAR(Shadow)は既定でMETA-INF/servicesを「マージせず上書き」するため、
// 同じサービスファイルを持つ依存が複数あると後勝ちで消える。
// Flyway 10はプラグイン方式で、flyway-coreが21件、flyway-database-postgresqlが2件を
// 同じファイル名で登録しており、上書きによってcoreの21件が丸ごと失われていた。
// その結果、Flywayは正しい名前のマイグレーションを「命名規則に合わない」として読み飛ばし、
// **スキーマが空のままサーバーが正常起動する**という状態になっていた(デプロイして初めて発覚。
// ./gradlew run はリソースをディレクトリから読むため再現しない)。
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    mergeServiceFiles()
}

val ktorVersion = "2.3.12"
val exposedVersion = "0.49.0"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktorVersion")
    implementation("com.auth0:jwks-rsa:0.22.1")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    // requirements.md 23章: Family Webは別ドメインからブラウザ経由でAPIを呼ぶためCORSが要る。
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-request-validation:$ktorVersion")

    // Ktor client (outbound calls: FCM)
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // DB
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.15.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.15.0")

    // Command signing (Ed25519, see requirements 8.1)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // requirements.md 5章: 電話番号の正規化・国際判定(RiskEngine, 7章)
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.40")

    // requirements.md 8章[v2]: 遠隔コマンドのFCM即時送信
    implementation("com.google.firebase:firebase-admin:9.3.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Test
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.test {
    useJUnitPlatform()
}

// ローカル実機テスト用: Cognitoを経由せずDBへ直接テストデータを作成する開発ツール(本番では未使用)。
tasks.register<JavaExec>("seedTestDevice") {
    group = "application"
    description = "Seed a local family user + device + pairing code directly into the DB (dev only)"
    mainClass.set("com.fraudguard.server.tools.SeedTestDeviceKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("sendTestNotification") {
    group = "application"
    description = "Send a sample CRITICAL alert to the configured Slack webhook (dev only)"
    mainClass.set("com.fraudguard.server.tools.SendTestNotificationKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.register<JavaExec>("issueDisconnectCommand") {
    group = "application"
    description = "Issue a signed DISCONNECT_CALL command for the device's latest call (dev only)"
    mainClass.set("com.fraudguard.server.tools.IssueDisconnectCommandKt")
    classpath = sourceSets["main"].runtimeClasspath
}
