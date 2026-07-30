package com.mclauncher.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mclauncher.app.R
import com.mclauncher.app.engine.NativeLaunchBridge
import com.mclauncher.app.ui.LauncherUiState
import com.mclauncher.app.ui.components.LauncherCard

@Composable
fun HomeScreen(
    state: LauncherUiState,
    onPlay: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenAccounts: () -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    val featured = state.orderedInstances.firstOrNull()

    Scaffold(snackbarHost = snackbarHost) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.mclauncher_logo),
                        contentDescription = "MCLauncher logo",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("MCLauncher", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Your Java Edition library on Android",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                LauncherCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        featured?.name ?: "Build your first instance",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        featured?.let { "Vanilla ${it.versionId} • Java ${it.javaVersion.major}" }
                            ?: "Install an official Minecraft version from Discover.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (featured != null) {
                            Button(
                                onClick = { onPlay(featured.id) },
                                enabled = featured.installed && state.engineOperation == null
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Text("Play", modifier = Modifier.padding(start = 8.dp))
                            }
                            FilledTonalButton(onClick = onOpenLibrary) {
                                Icon(Icons.Rounded.Storage, contentDescription = null)
                                Text("Library", modifier = Modifier.padding(start = 8.dp))
                            }
                        } else {
                            Button(onClick = onOpenDiscover) {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Text("Discover versions", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }

            state.installProgress?.let { progress ->
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Installing ${state.activeInstallVersion}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            progress.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${progress.completedFiles} / ${progress.totalFiles} files • ${progress.currentFile}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LauncherCard(modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                        Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            state.selectedAccount?.username ?: "No offline account selected",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        FilledTonalButton(onClick = onOpenAccounts) {
                            Text(if (state.selectedAccount == null) "Add account" else "Manage")
                        }
                    }
                    LauncherCard(modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Storage, contentDescription = null)
                        Text("Engine", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            state.engineOperation
                                ?: if (NativeLaunchBridge.isAvailable) "Native bridge loaded" else "Native engine pending",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Text(
                            "${state.snapshot.instances.count { it.installed }} installed instances",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Text(
                    "MCLauncher prepares real Mojang files and launch arguments. It will only report a running game after the native Android engine confirms startup.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
