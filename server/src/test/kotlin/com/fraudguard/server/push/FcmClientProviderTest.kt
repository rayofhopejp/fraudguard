package com.fraudguard.server.push

import com.fraudguard.server.domain.model.CommandType
import com.fraudguard.server.domain.model.RemoteCommand
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/** requirements.md 8章[v2]: サービスアカウント未設定/不正でもクラッシュせず、常に失敗を返すNoopへ落ちること。 */
class FcmClientProviderTest {

    @Test
    fun `falls back to a noop client when the service account path is invalid`() = runBlocking {
        FcmClientProvider.init("/nonexistent/path/service-account.json")

        val command = RemoteCommand(
            commandId = "cmd-1",
            deviceId = "device-1",
            callId = "call-1",
            type = CommandType.DISCONNECT_CALL,
            issuedAt = "2026-08-13T12:00:00Z",
            expiresAt = "2026-08-13T12:02:00Z",
            nonce = "nonce",
            signature = "sig",
            issuedByFamilyUserId = "family-1",
        )

        val result = FcmClientProvider.get().sendCommandDataMessage("some-fcm-token", command)
        assertFalse(result, "無効な設定では送信を試みず/失敗として扱われること")
    }
}
