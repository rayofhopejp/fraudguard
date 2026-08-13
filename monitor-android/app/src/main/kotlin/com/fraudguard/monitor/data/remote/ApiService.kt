package com.fraudguard.monitor.data.remote

import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** requirements.md 23章: サーバーREST APIのMonitor側クライアント(device-auth)。 */
interface ApiService {
    @POST("/events")
    suspend fun postEvent(@Body request: CreateEventRequest): Response<Unit>

    @POST("/devices/{deviceId}/heartbeat")
    suspend fun postHeartbeat(@Path("deviceId") deviceId: String, @Body request: HeartbeatRequest): Response<Unit>

    @GET("/devices/{deviceId}/whitelist")
    suspend fun getWhitelist(@Path("deviceId") deviceId: String): Response<List<WhitelistEntryDto>>

    @GET("/devices/{deviceId}/commands/pending")
    suspend fun getPendingCommands(@Path("deviceId") deviceId: String): Response<List<RemoteCommandDto>>

    @POST("/devices/{deviceId}/commands/{commandId}/report")
    suspend fun reportCommandResult(
        @Path("deviceId") deviceId: String,
        @Path("commandId") commandId: String,
        @Body report: CommandExecutionReportDto,
    ): Response<Unit>

    /** requirements.md 8章[v2]: 遠隔コマンドのFCM即時送信先トークンを登録・更新する。 */
    @POST("/devices/{deviceId}/fcm-token")
    suspend fun postFcmToken(@Path("deviceId") deviceId: String, @Body request: RegisterFcmTokenRequest): Response<Unit>

    /**
     * requirements.md 34章: ペアリングコードを引き換え、APIキーと署名検証用公開鍵を受け取る。
     * サーバー側は未認証エンドポイント(ペアリングコード自体が一時的な認証情報のため)。
     */
    @POST("/devices/pairing")
    suspend fun exchangePairing(@Body request: PairingExchangeRequestDto): Response<DevicePairingResultDto>
}

@Serializable
data class RegisterFcmTokenRequest(val fcmToken: String)

@Serializable
data class PairingExchangeRequestDto(val pairingCode: String)

@Serializable
data class DevicePairingResultDto(
    val deviceId: String,
    val apiKey: String,
    val serverPublicKey: String,
)

@Serializable
data class CreateEventRequest(
    val eventId: String,
    val deviceId: String,
    val type: EventType,
    val riskLevel: RiskLevel,
    val title: String,
    val detail: String,
    val timestamp: String,
    val metadata: EventMetadata = EventMetadata(),
)

@Serializable
data class HeartbeatRequest(
    val deviceId: String,
    val timestamp: String,
    val notificationListenerEnabled: Boolean,
    val roleDialerHeld: Boolean,
    val appVersion: String,
)

@Serializable
data class WhitelistEntryDto(
    val entryId: String,
    val phoneNumber: String,
    val displayName: String,
    val enabled: Boolean,
)

@Serializable
data class RemoteCommandDto(
    val commandId: String,
    val deviceId: String,
    val callId: String,
    val type: String,
    val issuedAt: String,
    val expiresAt: String,
    val nonce: String,
    val signature: String,
)

@Serializable
data class CommandExecutionReportDto(
    val commandId: String,
    val deviceId: String,
    val success: Boolean,
    val failureReason: String? = null,
    val executedAt: String,
)
