plugins {
    // JDK 17ツールチェーンをローカルに持たない環境でも自動ダウンロードできるようにする。
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "fraudguard-server"
