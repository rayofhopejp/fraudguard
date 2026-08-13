package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.Blacklist
import com.fraudguard.server.db.tables.Whitelist
import com.fraudguard.server.domain.model.WhitelistEntry
import java.time.Instant
import kotlinx.serialization.Serializable
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** requirements.md 6章: ホワイトリスト。サーバー側を正とし、監視端末へ同期される。 */
object WhitelistRepository {
    suspend fun list(deviceId: String): List<WhitelistEntry> = dbQuery {
        Whitelist
            .select { Whitelist.deviceId eq deviceId }
            .map { it.toWhitelistEntry() }
    }

    suspend fun add(deviceId: String, phoneNumber: String, displayName: String, note: String?, createdBy: String): WhitelistEntry = dbQuery {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        Whitelist.insert {
            it[Whitelist.id] = id
            it[Whitelist.deviceId] = deviceId
            it[Whitelist.phoneNumber] = phoneNumber
            it[Whitelist.displayName] = displayName
            it[Whitelist.note] = note
            it[Whitelist.createdBy] = createdBy
            it[createdAt] = now
            it[updatedAt] = now
        }
        WhitelistEntry(
            entryId = id,
            deviceId = deviceId,
            phoneNumber = phoneNumber,
            displayName = displayName,
            enabled = true,
            note = note,
            createdBy = createdBy,
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )
    }

    /**
     * requirements.md 6.1章: 登録済みエントリの編集。
     *
     * 電話番号は変更させない。番号が変わればそれは別の相手であり、
     * 「◯◯さん」という名前だけが残ったまま中身がすり替わるのは危険。番号を直すなら消して登録し直す。
     */
    suspend fun update(
        deviceId: String,
        entryId: String,
        displayName: String,
        note: String?,
        enabled: Boolean,
    ): WhitelistEntry? = dbQuery {
        val updated = Whitelist.update({ (Whitelist.deviceId eq deviceId) and (Whitelist.id eq entryId) }) {
            it[Whitelist.displayName] = displayName
            it[Whitelist.note] = note
            it[Whitelist.enabled] = enabled
            it[updatedAt] = Instant.now()
        }
        if (updated == 0) return@dbQuery null

        Whitelist
            .select { (Whitelist.deviceId eq deviceId) and (Whitelist.id eq entryId) }
            .firstOrNull()
            ?.toWhitelistEntry()
    }

    suspend fun delete(deviceId: String, entryId: String): Boolean = dbQuery {
        // Exposed 0.49の deleteWhere は (Table.(ISqlExpressionBuilder) -> Op<Boolean>) のため、
        // update/selectのSqlExpressionBuilder受け取り方と異なり、eqの解決にはit.run{}が必要。
        Whitelist.deleteWhere { it.run { (Whitelist.id eq entryId) and (Whitelist.deviceId eq deviceId) } } > 0
    }

    /** requirements.md 7章: RiskEngineによる単一イベント判定で使う、E.164正規化済み番号での照合。 */
    suspend fun isWhitelisted(deviceId: String, e164PhoneNumber: String): Boolean = dbQuery {
        Whitelist
            .select { (Whitelist.deviceId eq deviceId) and (Whitelist.phoneNumber eq e164PhoneNumber) and (Whitelist.enabled eq true) }
            .empty()
            .not()
    }
}

private fun org.jetbrains.exposed.sql.ResultRow.toWhitelistEntry() = WhitelistEntry(
    entryId = this[Whitelist.id],
    deviceId = this[Whitelist.deviceId],
    phoneNumber = this[Whitelist.phoneNumber],
    displayName = this[Whitelist.displayName],
    enabled = this[Whitelist.enabled],
    note = this[Whitelist.note],
    createdBy = this[Whitelist.createdBy],
    createdAt = this[Whitelist.createdAt].toString(),
    updatedAt = this[Whitelist.updatedAt].toString(),
)

/** requirements.md 18章[v2]: ブラックリスト登録は着信を常にCRITICAL即時警告する効果のみ持つ(自動拒否はしない)。 */
object BlacklistRepository {
    suspend fun list(deviceId: String): List<BlacklistEntryDto> = dbQuery {
        Blacklist
            .select { Blacklist.deviceId eq deviceId }
            .map {
                BlacklistEntryDto(
                    entryId = it[Blacklist.id],
                    phoneNumber = it[Blacklist.phoneNumber],
                    reason = it[Blacklist.reason],
                    createdAt = it[Blacklist.createdAt].toString(),
                )
            }
    }

    suspend fun add(deviceId: String, phoneNumber: String, reason: String?, createdBy: String): BlacklistEntryDto = dbQuery {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        Blacklist.insert {
            it[Blacklist.id] = id
            it[Blacklist.deviceId] = deviceId
            it[Blacklist.phoneNumber] = phoneNumber
            it[Blacklist.reason] = reason
            it[Blacklist.createdBy] = createdBy
            it[createdAt] = now
        }
        BlacklistEntryDto(entryId = id, phoneNumber = phoneNumber, reason = reason, createdAt = now.toString())
    }

    /** requirements.md 18章[v2]: 登録理由の編集。番号はホワイトリストと同じ理由で変更させない。 */
    suspend fun update(deviceId: String, entryId: String, reason: String?): BlacklistEntryDto? = dbQuery {
        val updated = Blacklist.update({ (Blacklist.deviceId eq deviceId) and (Blacklist.id eq entryId) }) {
            it[Blacklist.reason] = reason
        }
        if (updated == 0) return@dbQuery null

        Blacklist
            .select { (Blacklist.deviceId eq deviceId) and (Blacklist.id eq entryId) }
            .firstOrNull()
            ?.let {
                BlacklistEntryDto(
                    entryId = it[Blacklist.id],
                    phoneNumber = it[Blacklist.phoneNumber],
                    reason = it[Blacklist.reason],
                    createdAt = it[Blacklist.createdAt].toString(),
                )
            }
    }

    suspend fun delete(deviceId: String, entryId: String): Boolean = dbQuery {
        Blacklist.deleteWhere {
            it.run { (Blacklist.deviceId eq deviceId) and (Blacklist.id eq entryId) }
        } > 0
    }

    suspend fun isBlacklisted(deviceId: String, e164PhoneNumber: String): Boolean = dbQuery {
        Blacklist
            .select { (Blacklist.deviceId eq deviceId) and (Blacklist.phoneNumber eq e164PhoneNumber) }
            .empty()
            .not()
    }
}

@Serializable
data class BlacklistEntryDto(val entryId: String, val phoneNumber: String, val reason: String?, val createdAt: String)
