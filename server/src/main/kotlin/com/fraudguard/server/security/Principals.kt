package com.fraudguard.server.security

import io.ktor.server.auth.Principal

/** requirements.md 25章: 監視端末ごとの認証。device_pairings.api_key_hash の照合結果として生成される。 */
data class DevicePrincipal(val deviceId: String) : Principal

/** requirements.md 25章: 家族ユーザー認証。CognitoのsubをキーにfamiliesUsersへ解決した結果。 */
data class FamilyUserPrincipal(val familyUserId: String, val email: String) : Principal
