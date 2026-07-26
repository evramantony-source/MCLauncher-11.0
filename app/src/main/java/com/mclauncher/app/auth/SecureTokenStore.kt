package com.mclauncher.app.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mclauncher.model.AuthSession
import kotlinx.serialization.json.Json

class SecureTokenStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val preferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mclauncher-auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        context.getSharedPreferences("mclauncher-auth-fallback", Context.MODE_PRIVATE)
    }

    fun save(accountId: String, session: AuthSession) {
        preferences.edit().putString(accountId, json.encodeToString(AuthSession.serializer(), session)).apply()
    }

    fun load(accountId: String): AuthSession? = runCatching {
        preferences.getString(accountId, null)?.let { json.decodeFromString(AuthSession.serializer(), it) }
    }.getOrNull()

    fun remove(accountId: String) {
        preferences.edit().remove(accountId).apply()
    }
}
