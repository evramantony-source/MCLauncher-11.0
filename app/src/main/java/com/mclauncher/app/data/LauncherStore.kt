package com.mclauncher.app.data

import android.content.Context
import com.mclauncher.model.LauncherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class LauncherStore(context: Context) {
    private val preferences = context.getSharedPreferences("launcher-state", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    suspend fun load(): LauncherSnapshot = withContext(Dispatchers.IO) {
        val raw = preferences.getString(KEY_SNAPSHOT, null)
        if (raw.isNullOrBlank()) LauncherSnapshot()
        else runCatching { json.decodeFromString(LauncherSnapshot.serializer(), raw) }
            .getOrElse { LauncherSnapshot() }
    }

    suspend fun save(snapshot: LauncherSnapshot) = withContext(Dispatchers.IO) {
        preferences.edit()
            .putString(KEY_SNAPSHOT, json.encodeToString(LauncherSnapshot.serializer(), snapshot))
            .apply()
    }

    companion object {
        private const val KEY_SNAPSHOT = "snapshot-v1"
    }
}
