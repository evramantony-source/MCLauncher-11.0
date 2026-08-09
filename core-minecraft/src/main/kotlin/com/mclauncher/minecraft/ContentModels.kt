package com.mclauncher.minecraft

import com.mclauncher.model.ContentType
import com.mclauncher.model.ContentSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ModrinthSearchResponse(
    val hits: List<ModrinthProject> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    val total_hits: Int = 0
)

@Serializable
data class ModrinthProject(
    val project_id: String,
    val project_type: String,
    val slug: String,
    val author: String,
    val title: String,
    val description: String = "",
    val categories: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    val downloads: Long = 0,
    val follows: Long = 0,
    val icon_url: String? = null,
    val date_modified: String? = null,
    val latest_version: String? = null,
    val license: String? = null,
    val client_side: String? = null,
    val server_side: String? = null
)

@Serializable
data class ModrinthVersion(
    val id: String,
    val project_id: String,
    val author_id: String? = null,
    val featured: Boolean = false,
    val name: String,
    val version_number: String,
    val changelog: String? = null,
    val dependencies: List<ModrinthDependency> = emptyList(),
    val game_versions: List<String> = emptyList(),
    val version_type: String = "release",
    val loaders: List<String> = emptyList(),
    val files: List<ModrinthFile> = emptyList(),
    val date_published: String? = null,
    val downloads: Long = 0
)

@Serializable
data class ModrinthDependency(
    val version_id: String? = null,
    val project_id: String? = null,
    val file_name: String? = null,
    val dependency_type: String
)

@Serializable
data class ModrinthFile(
    val hashes: ModrinthHashes,
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long = 0,
    val file_type: String? = null
)

@Serializable
data class ModrinthHashes(
    val sha1: String? = null,
    val sha512: String? = null
)

@Serializable
data class InstalledContent(
    val projectId: String,
    val versionId: String,
    val contentType: ContentType,
    val fileName: String,
    val title: String,
    val iconUrl: String? = null,
    val versionNumber: String,
    val loader: String? = null,
    val gameVersion: String,
    val sha1: String? = null,
    val sha512: String? = null,
    val downloadUrls: List<String> = emptyList(),
    val fileSize: Long = 0,
    val clientEnvironment: String? = null,
    val serverEnvironment: String? = null,
    val source: ContentSource? = null,
    val installedAtEpochMs: Long = System.currentTimeMillis(),
    val enabled: Boolean = true
)

@Serializable
data class ContentIndex(
    val items: List<InstalledContent> = emptyList()
)

@Serializable
data class CurseForgeSearchResponse(
    val data: List<CurseForgeMod> = emptyList(),
    val pagination: CurseForgePagination? = null
)

@Serializable
data class CurseForgePagination(
    val index: Int = 0,
    val pageSize: Int = 0,
    val resultCount: Int = 0,
    val totalCount: Long = 0
)

@Serializable
data class CurseForgeMod(
    val id: Int,
    val gameId: Int? = null,
    val classId: Int? = null,
    val name: String,
    val slug: String,
    val summary: String = "",
    val downloadCount: Double = 0.0,
    val logo: CurseForgeLogo? = null,
    val latestFiles: List<CurseForgeFile> = emptyList()
)

@Serializable
data class CurseForgeLogo(
    val thumbnailUrl: String? = null,
    val url: String? = null
)

@Serializable
data class CurseForgeFile(
    val id: Int,
    val modId: Int,
    val displayName: String,
    val fileName: String,
    val fileDate: String? = null,
    val fileLength: Long = 0,
    val downloadUrl: String? = null,
    val releaseType: Int = 1,
    val gameVersions: List<String> = emptyList(),
    val dependencies: List<CurseForgeDependency> = emptyList(),
    val hashes: List<CurseForgeHash> = emptyList()
)

@Serializable
data class CurseForgeDependency(
    val modId: Int,
    val relationType: Int
)

@Serializable
data class CurseForgeHash(
    val value: String,
    val algo: Int
)
