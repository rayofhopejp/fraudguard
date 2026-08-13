package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.FamilyUsers
import com.fraudguard.server.security.FamilyUserPrincipal
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

/**
 * requirements.md 25章: 家族ユーザー認証。Cognitoの `sub` をキーに family_users を解決する。
 * 初回ログイン時は自動的にレコードを作成する(Cognito側でユーザー管理を行うため、
 * サーバー側に別途サインアップ導線は設けない)。
 */
object FamilyUserRepository {
    suspend fun resolveOrCreate(cognitoSub: String, email: String): FamilyUserPrincipal = dbQuery {
        val existing = FamilyUsers
            .select { FamilyUsers.cognitoSub eq cognitoSub }
            .singleOrNull()

        if (existing != null) {
            return@dbQuery FamilyUserPrincipal(
                familyUserId = existing[FamilyUsers.id],
                email = existing[FamilyUsers.email],
            )
        }

        val newId = UUID.randomUUID().toString()
        FamilyUsers.insert {
            it[id] = newId
            it[FamilyUsers.cognitoSub] = cognitoSub
            it[displayName] = email.substringBefore("@")
            it[FamilyUsers.email] = email
            it[createdAt] = Instant.now()
        }
        FamilyUserPrincipal(familyUserId = newId, email = email)
    }
}
