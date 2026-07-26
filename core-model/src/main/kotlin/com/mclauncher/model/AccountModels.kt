package com.mclauncher.model

import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
enum class AccountType { OFFLINE, MICROSOFT }

@Serializable
data class LauncherAccount(
    val id: String,
    val username: String,
    val type: AccountType = AccountType.OFFLINE,
    val profileId: String = id,
    val xuid: String? = null,
    val clientId: String? = null,
    val skinUrl: String? = null,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val tokenExpiresAtEpochMs: Long? = null
)

typealias OfflineAccount = LauncherAccount

@Serializable
data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long,
    val xuid: String? = null,
    val clientId: String? = null
) {
    fun isExpired(nowEpochMs: Long = System.currentTimeMillis(), skewMs: Long = 120_000L): Boolean =
        expiresAtEpochMs <= nowEpochMs + skewMs
}

object OfflineAccountFactory {
    private val validName = Regex("^[A-Za-z0-9_]{3,16}$")

    fun validateUsername(username: String): Result<String> {
        val trimmed = username.trim()
        return if (validName.matches(trimmed)) {
            Result.success(trimmed)
        } else {
            Result.failure(
                IllegalArgumentException("Username must be 3-16 characters and contain only letters, numbers, or underscores.")
            )
        }
    }

    fun offlineUuid(username: String): String {
        val bytes = "OfflinePlayer:$username".toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(bytes).toString()
    }

    fun create(username: String, nowEpochMs: Long = System.currentTimeMillis()): LauncherAccount {
        val valid = validateUsername(username).getOrThrow()
        val id = offlineUuid(valid)
        return LauncherAccount(
            id = id,
            username = valid,
            type = AccountType.OFFLINE,
            profileId = id,
            createdAtEpochMs = nowEpochMs,
            lastUsedAtEpochMs = nowEpochMs
        )
    }
}
