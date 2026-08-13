package com.fraudguard.server

import com.fraudguard.server.db.repository.DeviceMemberDto
import com.fraudguard.server.db.repository.FamilyMemberDto
import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.MonitoredDevice
import com.fraudguard.server.domain.model.RiskLevel
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * APIが返す型が実際にJSONへ変換できることを確認する。
 *
 * @Serializable の付け忘れはコンパイルを通り、実際にそのエンドポイントを呼ぶまで表面化しない。
 * しかもkotlinx.serializationの例外はIllegalArgumentExceptionを継承しており、
 * StatusPagesが400 Bad Requestへ変換するため、原因が分かりにくい形で失敗する
 * (実際に /devices/{id}/members と /family/members がこれで壊れていた)。
 */
class ApiSerializationTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `device member list serialises`() {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(DeviceMemberDto.serializer()),
            listOf(DeviceMemberDto("id", "名前", "a@example.invalid", isOwner = true)),
        )
        assertTrue(encoded.contains("isOwner"))
    }

    @Test
    fun `family member list serialises`() {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(FamilyMemberDto.serializer()),
            listOf(FamilyMemberDto("id", "名前", "a@example.invalid")),
        )
        assertTrue(encoded.contains("familyUserId"))
    }

    @Test
    fun `device list serialises`() {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(MonitoredDevice.serializer()),
            listOf(MonitoredDevice("id", "端末", "owner", "2026-08-14T00:00:00Z", null)),
        )
        assertTrue(encoded.contains("lastHeartbeatAt"))
    }

    @Test
    fun `event list serialises`() {
        val encoded = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Event.serializer()),
            listOf(
                Event(
                    eventId = "e", deviceId = "d", type = EventType.CALL_INCOMING,
                    riskLevel = RiskLevel.NOTICE, title = "着信", detail = "",
                    timestamp = "2026-08-14T00:00:00Z", metadata = EventMetadata(),
                ),
            ),
        )
        assertTrue(encoded.contains("riskLevel"))
    }
}
