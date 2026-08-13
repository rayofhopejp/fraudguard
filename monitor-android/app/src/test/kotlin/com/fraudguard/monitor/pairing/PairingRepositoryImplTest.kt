package com.fraudguard.monitor.pairing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fraudguard.monitor.data.remote.ApiClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PairingRepositoryImplの実際のペアリング交換フロー(HTTP + 永続化)を検証する。
 * 暗号化ストレージ自体(EncryptedSharedPreferences)はAndroidX側の実装として信頼し、
 * ここではプレーンなSharedPreferencesを注入して、ペアリングのロジック/永続化契約を検証する
 * (RobolectricはAndroid Keystoreを提供しないため)。
 *
 * @Config(application = Application::class) で FraudGuardApplication.onCreate() の
 * 副作用(Room/WorkManager初期化等)を避け、このクラス単体の振る舞いに集中する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PairingRepositoryImplTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newRepository(): PairingRepositoryImpl {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PairingRepositoryImpl(
            context = context,
            apiServiceFactory = { ApiClient.create(baseUrl = server.url("/").toString()) { null } },
            prefsProvider = { it.getSharedPreferences("test_pairing_prefs", Context.MODE_PRIVATE) },
        )
    }

    @Test
    fun `successful pairing stores credentials and reflects isPaired`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("""{"deviceId":"device-1","apiKey":"api-key-123","serverPublicKey":"cGFvbGljX2tleQ=="}"""),
        )

        val repository = newRepository()
        val outcome = repository.pair("ABC123")

        assertTrue(outcome is PairingOutcome.Success)
        assertEquals("device-1", outcome.deviceId)
        assertTrue(repository.isPaired())
        assertEquals("device-1", repository.getDeviceId())
        assertEquals("api-key-123", repository.getApiKey())
        assertEquals("cGFvbGljX2tleQ==", repository.getServerPublicKey())

        val recordedRequest = server.takeRequest()
        assertEquals("/devices/pairing", recordedRequest.path)
        assertTrue(recordedRequest.body.readUtf8().contains("ABC123"), "リクエストボディにペアリングコードが含まれること")
    }

    @Test
    fun `rejected pairing code does not store credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_or_expired_code"}"""))

        val repository = newRepository()
        val outcome = repository.pair("EXPIRED")

        assertTrue(outcome is PairingOutcome.Failure)
        assertFalse(repository.isPaired())
        assertNull(repository.getDeviceId())
        assertNull(repository.getApiKey())
    }

    @Test
    fun `network failure is reported without crashing`() = runTest {
        server.shutdown() // 接続不能な状態を作る

        val repository = newRepository()
        val outcome = repository.pair("ABC123")

        assertTrue(outcome is PairingOutcome.Failure)
        assertFalse(repository.isPaired())
    }
}
