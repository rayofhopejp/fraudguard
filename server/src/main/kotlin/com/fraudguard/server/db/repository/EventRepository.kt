package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.Acknowledgements
import com.fraudguard.server.db.tables.Events
import com.fraudguard.server.domain.model.CreateEventRequest
import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

private val json = Json { ignoreUnknownKeys = true }

/** requirements.md 22章, 24章: イベントの永続化。eventIdの冪等性はDB側のON CONFLICT DO NOTHINGで担保する。 */
object EventRepository {
    /**
     * @return 実際に新規挿入されたら true、eventIdの重複でスキップされたら false。
     *         requirements.md 30章: 同じイベントを家族へ大量重複通知しないため、呼び出し側は
     *         false(=Monitorの再送等による重複)の場合は通知を送らないこと。
     */
    suspend fun insertIfAbsent(request: CreateEventRequest, deviceId: String): Boolean = dbQuery {
        val timestamp = parseInstantOrNow(request.timestamp)
        val statement = Events.insertIgnore {
            it[id] = request.eventId
            it[Events.deviceId] = deviceId
            it[type] = request.type.name
            it[riskLevel] = request.riskLevel.name
            it[title] = request.title
            it[detail] = request.detail
            it[Events.timestamp] = timestamp
            it[metadataJson] = json.encodeToString(EventMetadata.serializer(), request.metadata)
            it[acknowledged] = false
            it[createdAt] = Instant.now()
        }
        statement.insertedCount > 0
    }

    suspend fun listForDevice(deviceId: String): List<Event> = dbQuery {
        Events
            .select { Events.deviceId eq deviceId }
            .orderBy(Events.timestamp, SortOrder.DESC)
            .map { it.toEvent() }
    }

    /**
     * requirements.md 30章: 「同じイベントを家族へ大量重複通知しない」ための重複判定。
     * RiskEngineの相関イベント(CORRELATED_RISK等)を、直近で既に出していないか確認する用途。
     */
    suspend fun existsRecentOfType(deviceId: String, type: EventType, sinceMinutesAgo: Long): Boolean = dbQuery {
        val threshold = Instant.now().minusSeconds(sinceMinutesAgo * 60)
        Events
            .select { (Events.deviceId eq deviceId) and (Events.type eq type.name) and (Events.timestamp greater threshold) }
            .empty()
            .not()
    }

    suspend fun find(eventId: String): Event? = dbQuery {
        Events.select { Events.id eq eventId }.singleOrNull()?.toEvent()
    }

    /** requirements.md 18章, 19章: 確認済みにする(誰が確認したかは呼び出し側でAcknowledgements repositoryを併用)。 */
    suspend fun markAcknowledged(eventId: String, familyUserId: String): Boolean = dbQuery {
        val updated = Events.update({ Events.id eq eventId }) {
            it[acknowledged] = true
        }
        if (updated > 0) {
            Acknowledgements.insertIgnore {
                it[id] = UUID.randomUUID().toString()
                it[Acknowledgements.eventId] = eventId
                it[Acknowledgements.familyUserId] = familyUserId
                it[acknowledgedAt] = Instant.now()
            }
        }
        updated > 0
    }

    private fun parseInstantOrNow(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            Instant.now()
        }

    private fun ResultRow.toEvent(): Event {
        val metadata = try {
            json.decodeFromString(EventMetadata.serializer(), this[Events.metadataJson])
        } catch (e: Exception) {
            EventMetadata()
        }
        return Event(
            eventId = this[Events.id],
            deviceId = this[Events.deviceId],
            type = EventType.valueOf(this[Events.type]),
            riskLevel = RiskLevel.valueOf(this[Events.riskLevel]),
            title = this[Events.title],
            detail = this[Events.detail],
            timestamp = this[Events.timestamp].toString(),
            metadata = metadata,
            acknowledged = this[Events.acknowledged],
            createdAt = this[Events.createdAt].toString(),
        )
    }
}
