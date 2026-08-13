package com.fraudguard.monitor.data.remote

import com.fraudguard.monitor.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * requirements.md 23章, 25章: HTTPS/TLS必須、監視端末ごとのAPIキー認証。
 * TODO: BASE_URLを本番/開発でビルドバリアント切り替え。
 */
object ApiClient {
    // app/build.gradle.kts の buildConfigField 経由。実機テスト時は
    // -Pfraudguard.apiBaseUrl=... または local.properties で差し替える(ソースの書き換え不要)。
    private val BASE_URL = BuildConfig.API_BASE_URL

    /** @param baseUrl テスト時にMockWebServer等へ差し替えるためのフック。通常は省略してよい。 */
    fun create(baseUrl: String = BASE_URL, apiKeyProvider: () -> String?): ApiService {
        val authInterceptor = Interceptor { chain ->
            val builder = chain.request().newBuilder()
            apiKeyProvider()?.let { builder.addHeader("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .build()

        val json = Json { ignoreUnknownKeys = true }

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(ApiService::class.java)
    }
}
