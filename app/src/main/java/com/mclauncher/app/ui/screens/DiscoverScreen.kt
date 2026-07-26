package com.mclauncher.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mclauncher.app.ui.LauncherUiState
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.app.ui.components.PageHeader
import com.mclauncher.minecraft.CurseForgeMod
import com.mclauncher.minecraft.ModrinthProject
import com.mclauncher.minecraft.MojangVersionSummary
import com.mclauncher.model.ContentSource
import com.mclauncher.model.ContentType
import com.mclauncher.model.ModLoader

private enum class DiscoverSection(val label: String, val contentType: ContentType? = null) {
    VERSIONS("Minecraft"),
    MODS("Mods", ContentType.MOD),
    MODPACKS("Modpacks", ContentType.MODPACK),
    RESOURCE_PACKS("Resources", ContentType.RESOURCE_PACK),
    SHADERS("Shaders", ContentType.SHADER)
}

private enum class VersionFilter { RELEASES, SNAPSHOTS, ALL }

@Composable
fun DiscoverScreen(
    state: LauncherUiState,
    onRefresh: () -> Unit,
    onInstall: (MojangVersionSummary, ModLoader, String?) -> Unit,
    onLoadLoaderChoices: (ModLoader, String) -> Unit,
    onSearchContent: (String, ContentType, String?, ContentSource) -> Unit,
    onInstallContent: (ModrinthProject) -> Unit,
    onInstallCurseForgeContent: (CurseForgeMod) -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    var section by remember { mutableStateOf(DiscoverSection.VERSIONS) }
    var search by remember { mutableStateOf("") }
    var filter by remember(state.snapshot.settings.showSnapshots) {
        mutableStateOf(if (state.snapshot.settings.showSnapshots) VersionFilter.ALL else VersionFilter.RELEASES)
    }
    var selectedInstanceId by remember(state.snapshot.instances) {
        mutableStateOf(state.snapshot.instances.firstOrNull { it.installed }?.id)
    }
    var contentSource by remember { mutableStateOf(ContentSource.MODRINTH) }
    var pendingVersion by remember { mutableStateOf<MojangVersionSummary?>(null) }
    var selectedLoader by remember { mutableStateOf(ModLoader.VANILLA) }
    var selectedLoaderVersion by remember { mutableStateOf<String?>(null) }

    pendingVersion?.let { version ->
        AlertDialog(
            onDismissRequest = { pendingVersion = null },
            title = { Text("Install Minecraft ${version.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Choose the game loader. Fabric and Quilt profiles install directly; Forge and NeoForge prepare their official installer plan.")
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ModLoader.entries.forEach { loader ->
                            FilterChip(
                                selected = selectedLoader == loader,
                                onClick = {
                                    selectedLoader = loader
                                    selectedLoaderVersion = null
                                    if (loader != ModLoader.VANILLA) onLoadLoaderChoices(loader, version.id)
                                },
                                label = { Text(loader.displayName) }
                            )
                        }
                    }
                    if (selectedLoader != ModLoader.VANILLA) {
                        if (state.loaderLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        else if (state.loaderChoices.isEmpty()) Text("No compatible loader versions were returned.")
                        else LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.loaderChoices.take(30), key = { it.version }) { choice ->
                                FilterChip(
                                    selected = selectedLoaderVersion == choice.version,
                                    onClick = { selectedLoaderVersion = choice.version },
                                    label = { Text(choice.version + if (choice.stable) "" else " • beta") }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onInstall(version, selectedLoader, selectedLoaderVersion)
                        pendingVersion = null
                    },
                    enabled = selectedLoader == ModLoader.VANILLA || selectedLoaderVersion != null
                ) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { pendingVersion = null }) { Text("Cancel") } }
        )
    }

    Scaffold(snackbarHost = snackbarHost) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PageHeader(
                    title = "Discover",
                    subtitle = "Minecraft versions, Modrinth and CurseForge content",
                    action = {
                        if (section == DiscoverSection.VERSIONS) {
                            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh") }
                        }
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiscoverSection.entries.forEach { option ->
                        FilterChip(
                            selected = section == option,
                            onClick = {
                                section = option
                                search = ""
                            },
                            label = { Text(option.label) }
                        )
                    }
                }
            }

            if (section == DiscoverSection.VERSIONS) {
                minecraftVersionItems(
                    state = state,
                    search = search,
                    onSearchChange = { search = it },
                    filter = filter,
                    onFilterChange = { filter = it },
                    onInstall = {
                        selectedLoader = ModLoader.VANILLA
                        selectedLoaderVersion = null
                        pendingVersion = it
                    }
                )
            } else {
                val type = section.contentType ?: ContentType.MOD
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ContentSource.entries.forEach { source ->
                            FilterChip(
                                selected = contentSource == source,
                                onClick = {
                                    contentSource = source
                                    onSearchContent(search, type, selectedInstanceId, source)
                                },
                                label = { Text(source.displayName) }
                            )
                        }
                    }
                    if (contentSource == ContentSource.CURSEFORGE && state.snapshot.settings.curseForgeApiKey.isBlank()) {
                        Text(
                            "CurseForge requires an API key in Settings. Modrinth works without one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        label = { Text("Search ${section.label.lowercase()}") },
                        trailingIcon = {
                            IconButton(onClick = { onSearchContent(search, type, selectedInstanceId, contentSource) }) {
                                Icon(Icons.Rounded.Search, contentDescription = "Search")
                            }
                        }
                    )
                }
                item {
                    Text("Target instance", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.snapshot.instances.filter { it.installed }.forEach { instance ->
                            FilterChip(
                                selected = selectedInstanceId == instance.id,
                                onClick = {
                                    selectedInstanceId = instance.id
                                    onSearchContent(search, type, instance.id, contentSource)
                                },
                                label = { Text(instance.name) }
                            )
                        }
                    }
                }
                if (state.contentLoading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                val resultsEmpty = when (contentSource) {
                    ContentSource.MODRINTH -> state.contentResults.isEmpty()
                    ContentSource.CURSEFORGE -> state.curseForgeResults.isEmpty()
                }
                if (!state.contentLoading && resultsEmpty) {
                    item {
                        LauncherCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (search.isBlank()) "Search ${contentSource.displayName} for compatible content."
                                else "No matching content was found."
                            )
                        }
                    }
                }
                if (contentSource == ContentSource.MODRINTH) {
                    items(state.contentResults, key = { it.project_id }) { project ->
                        ContentProjectCard(project = project, onInstall = { onInstallContent(project) })
                    }
                } else {
                    items(state.curseForgeResults, key = { it.id }) { project ->
                        CurseForgeProjectCard(project = project, onInstall = { onInstallCurseForgeContent(project) })
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.minecraftVersionItems(
    state: LauncherUiState,
    search: String,
    onSearchChange: (String) -> Unit,
    filter: VersionFilter,
    onFilterChange: (VersionFilter) -> Unit,
    onInstall: (MojangVersionSummary) -> Unit
) {
    val installedVersions = state.snapshot.instances.filter { it.installed }.mapTo(mutableSetOf()) { it.versionId }
    val visibleVersions = state.versions.asSequence()
        .filter { version ->
            when (filter) {
                VersionFilter.RELEASES -> version.type == "release"
                VersionFilter.SNAPSHOTS -> version.type != "release"
                VersionFilter.ALL -> true
            }
        }
        .filter { search.isBlank() || it.id.contains(search.trim(), ignoreCase = true) }
        .take(150)
        .toList()

    item {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text("Search versions") }
        )
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VersionFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { onFilterChange(option) },
                    label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
    }
    state.installProgress?.let { progress ->
        item {
            LauncherCard(modifier = Modifier.fillMaxWidth()) {
                Text("Installing ${state.activeInstallVersion}", style = MaterialTheme.typography.titleMedium)
                Text(progress.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                Text("${progress.completedFiles} / ${progress.totalFiles} • ${progress.currentFile}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (visibleVersions.isEmpty()) {
        item { LauncherCard(modifier = Modifier.fillMaxWidth()) { Text(if (state.versions.isEmpty()) "Loading version manifest…" else "No matching versions") } }
    }
    items(visibleVersions, key = { it.id }) { version ->
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(version.id, style = MaterialTheme.typography.titleMedium)
                    Text("${version.type} • ${version.releaseTime}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (version.id in installedVersions) Text("Installed", color = MaterialTheme.colorScheme.primary)
                Button(onClick = { onInstall(version) }, enabled = state.activeInstallVersion == null) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Text("Install", modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun ContentProjectCard(project: ModrinthProject, onInstall: () -> Unit) {
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.title, style = MaterialTheme.typography.titleMedium)
                Text(project.description, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text("by ${project.author} • ${project.downloads} downloads", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onInstall) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Text("Install", modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}


@Composable
private fun CurseForgeProjectCard(project: CurseForgeMod, onInstall: () -> Unit) {
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                Text(project.summary, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text("CurseForge • ${project.downloadCount.toLong()} downloads", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onInstall) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Text("Install", modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}
