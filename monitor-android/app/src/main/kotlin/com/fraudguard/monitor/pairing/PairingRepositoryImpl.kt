package com.fraudguard.monitor.pairing

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.fraudguard.monitor.data.remote.ApiClient
import com.fraudguard.monitor.data.remote.ApiService
import com.fraudguard.monitor.data.remote.PairingExchangeRequestDto
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * requirements.md 25章, 34章: ペアリングコードの引き換えと、結果(deviceId/APIキー/サーバー公開鍵)の
 * セキュアな永続化。APIキーは平文で保存しない(デフォルトはEncryptedSharedPreferences)。
 *
 * @param apiServiceFactory テスト時にMockWebServer等へ差し替えるためのフック。
 *        ペアリング交換自体は未認証エンドポイント(requirements.md 34章)なので apiKeyProvider は常にnullを返す。
 * @param prefsProvider ストレージ実装の差し替えフック。テストではAndroid Keystoreに依存しない
 *        プレーンなSharedPreferencesを渡し、暗号化そのものではなくペアリングのロジック/永続化契約を検証する。
 */
class PairingRepositoryImpl(
    context: Context,
    private val apiServiceFactory: () -> ApiService = { ApiClient.create { null } },
    prefsProvider: (Context) -> SharedPreferences = ::createEncryptedPrefs,
) : PairingRepository {

    private val prefs: SharedPreferences by lazy { prefsProvider(context.applicationContext) }

    override suspend fun pair(pairingCode: String): PairingOutcome = withContext(Dispatchers.IO) {
        try {
            val response = apiServiceFactory().exchangePairing(PairingExchangeRequestDto(pairingCode))
            val result = response.body()
            if (!response.isSuccessful || result == null) {
                return@withContext PairingOutcome.Failure("pairing_failed_${response.code()}")
            }

            prefs.edit()
                .putString(KEY_DEVICE_ID, result.deviceId)
                .putString(KEY_API_KEY, result.apiKey)
                .putString(KEY_SERVER_PUBLIC_KEY, result.serverPublicKey)
                .apply()

            PairingOutcome.Success(result.deviceId)
        } catch (e: IOException) {
            PairingOutcome.Failure("network_error")
        } catch (e: HttpException) {
            PairingOutcome.Failure("http_error_${e.code()}")
        }
    }

    override fun isPaired(): Boolean = getDeviceId() != null && getApiKey() != null

    override fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    override fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)

    override fun getServerPublicKey(): String? = prefs.getString(KEY_SERVER_PUBLIC_KEY, null)

    companion object {
        private const val PREFS_FILE_NAME = "fraudguard_pairing"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_SERVER_PUBLIC_KEY = "server_public_key"
    }
}

private fun createEncryptedPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "fraudguard_pairing",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
