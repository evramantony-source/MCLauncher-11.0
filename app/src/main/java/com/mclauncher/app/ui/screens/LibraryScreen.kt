package com.mclauncher.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mclauncher.app.ui.LauncherUiState
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.app.ui.components.InstallProgressCard
import com.mclauncher.app.ui.components.PageHeader
import com.mclauncher.app.ui.components.runtimeLabel
import java.io.File

@Composable
fun LibraryScreen(
    state: LauncherUiState,
    onPlay: (String) -> Unit,
    onOpenInstance: (String) -> Unit,
    onDiscover: () -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    Scaffold(snackbarHost = snackbarHost) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PageHeader(
                    title = "Library",
                    subtitle = "Separate game directories and settings for every instance",
                    action = {
                        FilledTonalButton(onClick = onDiscover) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Text("Add", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                )
            }

            state.engineOperation?.let { operation ->
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Text(operation, style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                    }
                }
            }

            state.installProgress?.let { progress ->
                item {
                    InstallProgressCard(
                        progress = progress,
                        title = "Installing ${state.activeInstallVersion.orEmpty()}",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (state.orderedInstances.isEmpty()) {
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Text("No instances yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Install a release or snapshot from Discover. The launcher will create a separate game directory for it.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Button(onClick = onDiscover) { Text("Browse Minecraft versions") }
                    }
                }
            }

            items(state.orderedInstances, key = { it.id }) { instance ->
                LauncherCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenInstance(instance.id) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        instance.iconPath?.let { iconPath ->
                            AsyncImage(
                                model = File(iconPath),
                                contentDescription = "${instance.name} icon",
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp))
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text((if (instance.favorite) "★ " else "") + instance.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${instance.runtimeLabel(includeJava = true)} • ${if (instance.installed) "Installed" else "Incomplete"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Button(
                            onClick = { onPlay(instance.id) },
                            enabled = instance.installed && state.engineOperation == null
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Text("Play", modifier = Modifier.padding(start = 4.dp))
                        }
                        IconButton(onClick = { onOpenInstance(instance.id) }) {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = "Instance details")
                        }
                    }
                }
            }
        }
    }
}
