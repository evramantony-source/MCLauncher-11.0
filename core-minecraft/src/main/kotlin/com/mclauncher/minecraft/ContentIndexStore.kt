package com.mclauncher.minecraft

import com.mclauncher.model.MinecraftInstance
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One migration-safe source of truth for content provenance inside an instance.
 *
 * Older launcher snapshots do not contain the export metadata added in Alpha 18;
 * every new field on [InstalledContent] has a default, so those indexes continue
 * to load and can be hydrated from the provider before an export.
 */
class ContentIndexStore(
    private val layout: MinecraftLayout,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
) {
    fun read(instance: MinecraftInstance): ContentIndex =
        read(layout.instanceGameDirectory(instance.gameDirectoryName))

    fun read(gameDirectory: File): ContentIndex {
        val file = indexFile(gameDirectory)
        return if (!file.isFile) ContentIndex() else runCatching {
            json.decodeFromString<ContentIndex>(file.readText())
        }.getOrDefault(ContentIndex())
    }

    fun update(
        gameDirectory: File,
        transform: (List<InstalledContent>) -> List<InstalledContent>
    ): ContentIndex {
        val current = read(gameDirectory)
        val updated = ContentIndex(transform(current.items))
        val file = indexFile(gameDirectory)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(ContentIndex.serializer(), updated))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        return updated
    }

    fun replace(instance: MinecraftInstance, items: List<InstalledContent>): ContentIndex =
        update(layout.instanceGameDirectory(instance.gameDirectoryName)) { items }

    private fun indexFile(gameDirectory: File) =
        File(gameDirectory, ".mclauncher/content-index.json")
}
