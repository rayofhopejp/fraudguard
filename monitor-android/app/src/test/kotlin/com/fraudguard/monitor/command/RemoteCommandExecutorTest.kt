package com.fraudguard.monitor.command

import com.fraudguard.monitor.data.local.dao.UsedCommandDao
import com.fraudguard.monitor.data.local.entity.UsedCommandEntity
import com.fraudguard.monitor.data.remote.RemoteCommandDto
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * requirements.md 8.1章のコマンド検証ロジックを、実際のEd25519署名(server/security/CommandSigner.ktと
 * 同じ方式)を使って検証する。Android Keystore等は不要なため、Robolectric無しのプレーンJUnitで完結する。
 */
class RemoteCommandExecutorTest {

    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKeyBase64 = Base64.getEncoder().encodeToString(privateKey.generatePublicKey().encoded)

    private fun sign(payload: ByteArray): String {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(payload, 0, payload.size)
        return Base64.getEncoder().encodeToString(signer.generateSignature())
    }

    private fun buildCommand(
        commandId: String = "cmd-1",
        callId: String = "call-1",
        issuedAt: Instant = Instant.now(),
        expiresAt: Instant = Instant.now().plusSeconds(120),
        signatureOverride: String? = null,
    ): RemoteCommandDto {
        val nonce = "nonce-1"
        val payload = canonicalCommandPayload(
            commandId = commandId,
            deviceId = "device-1",
            callId = callId,
            type = "DISCONNECT_CALL",
            issuedAt = issuedAt.toString(),
            expiresAt = expiresAt.toString(),
            nonce = nonce,
        )
        val signature = signatureOverride ?: sign(payload)
        return RemoteCommandDto(
            commandId = commandId,
            deviceId = "device-1",
            callId = callId,
            type = "DISCONNECT_CALL",
            issuedAt = issuedAt.toString(),
            expiresAt = expiresAt.toString(),
            nonce = nonce,
            signature = signature,
        )
    }

    private class FakeUsedCommandDao : UsedCommandDao {
        private val used = mutableSetOf<String>()
        override suspend fun insertIfAbsent(entity: UsedCommandEntity): Long {
            check(used.add(entity.commandId)) { "duplicate insert for ${entity.commandId}" }
            return 1
        }

        override suspend fun exists(commandId: String): Boolean = commandId in used
    }

    @Test
    fun `valid command for the active call is executed and marked used`() = runTest {
        val dao = FakeUsedCommandDao()
        val executor = RemoteCommandExecutor(CommandSignatureVerifier(publicKeyBase64), dao, disconnectAction = { true })
        val command = buildCommand()

        val result = executor.execute(command, currentActiveCallId = command.callId)

        assertTrue(result is ExecutionResult.Executed)
        assertTrue(dao.exists(command.commandId))
    }

    @Test
    fun `syntactically valid but wrong signature is rejected`() = runTest {
        val dao = FakeUsedCommandDao()
        val executor = RemoteCommandExecutor(CommandSignatureVerifier(publicKeyBase64), dao, disconnectAction = { true })
        // 有効なBase64だが、このpayload/鍵に対しては正しくない署名(64バイトのゼロ)。
        val wrongSignature = Base64.getEncoder().encodeToString(ByteArray(64))
        val command = buildCommand(signatureOverride = wrongSignature)

        val result = executor.execute(command, currentActiveCallId = command.callId)

        assertEquals(ExecutionResult.Rejected("invalid_signature"), result)
    }

    @Test
    fun `malformed non-base64 signature is rejected instead of crashing`() = runTest {
        val dao = FakeUsedCommandDao()
        val executor = RemoteCommandExecutor(CommandSignatureVerifier(publicKeyBase64), dao, disconnectAction = { true })
        val command = buildCommand(signatureOverride = "not-valid-base64!!")

        val result = executor.execute(command, currentActiveCallId = command.callId)

        assertEquals(ExecutionResult.Rejected("invalid_signature"), result)
    }

    @Test
    fun `expired command is rejected`() = runTest {
        val dao = FakeUsedCommandDao()
        val executor = RemoteCommandExecutor(CommandSignatureVerifier(publicKeyBase64), dao, disconnectAction = { true })
        val command = buildCommand(
            issuedAt = Instant.now().minusSeconds(300),
            expiresAt = Instant.now().minusSeconds(60),
        )

        val result = executor.execute(command, currentActiveCallId = command.callId)

        assertEquals(ExecutionResult.Rejected("expired"), result)
    }

    @Test
    fun `command for a different call than the active one is rejected`() = runTest {
        val dao = FakeUsedCommandDao()
        val executor = RemoteCommandExecutor(CommandSignatureVerifier(publicKeyBase64), dao, disconnectAction = { true })
        val command = buildCommand(callId = "call-1")

        val result = executor.execute(command, currentActiveCallId = "call-2")

        assertEquals(ExecutionResult.Rejected("call_not_active_or_mismatched"), result)
    }

    @Test
    fun `replaying the same command is rejected and does not disconnect twice`() = runTest {
        val dao = FakeUsedCommandDao()
        var disconnectCallCount = 0
        val executor = RemoteCommandExecutor(
            CommandSignatureVerifier(publicKeyBase64),
            dao,
            disconnectAction = { disconnectCallCount++; true },
        )
        val command = buildCommand()

        val first = executor.execute(command, currentActiveCallId = command.callId)
        val second = executor.execute(command, currentActiveCallId = command.callId)

        assertTrue(first is ExecutionResult.Executed)
        assertEquals(ExecutionResult.Rejected("duplicate_command"), second)
        assertEquals(1, disconnectCallCount)
    }

    @Test
    fun `unavailable InCallService is reported as a distinct rejection reason`() = runTest {
        val dao = FakeUsedCommandDao()
        val executor = RemoteCommandExecutor(CommandSignatureVerifier(publicKeyBase64), dao, disconnectAction = { false })
        val command = buildCommand()

        val result = executor.execute(command, currentActiveCallId = command.callId)

        assertEquals(ExecutionResult.Rejected("incall_service_unavailable"), result)
    }
}
