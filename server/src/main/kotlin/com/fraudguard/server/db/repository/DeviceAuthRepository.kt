package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.DevicePairings
import com.fraudguard.server.security.DevicePrincipal
import com.fraudguard.server.security.sha256Hex
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select

/** requirements.md 25章, 34章: 監視端末のAPIキー認証。ハッシュ照合のみ行い、平文キーは保存しない。 */
object DeviceAuthRepository {
    suspend fun validate(apiKey: String): DevicePrincipal? = dbQuery {
        val hash = sha256Hex(apiKey)
        DevicePairings
            .select { (DevicePairings.apiKeyHash eq hash) and (DevicePairings.revokedAt.isNull()) }
            .singleOrNull()
            ?.let { DevicePrincipal(deviceId = it[DevicePairings.deviceId]) }
    }
}
