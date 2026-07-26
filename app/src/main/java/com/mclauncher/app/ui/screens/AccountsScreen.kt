package com.mclauncher.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mclauncher.app.ui.LauncherUiState
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.app.ui.components.PageHeader
import com.mclauncher.model.AccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
fun AccountsScreen(
    state: LauncherUiState,
    onAddAccount: (String) -> Unit,
    onMicrosoftLogin: () -> Unit,
    onOpenMicrosoftPage: () -> Unit,
    onSelectAccount: (String) -> Unit,
    onRemoveAccount: (String) -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Add offline account") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Offline profiles work for single-player and offline-mode servers. They do not unlock paid online services.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.take(16) },
                        label = { Text("Minecraft username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAddAccount(username)
                        username = ""
                        showDialog = false
                    },
                    enabled = username.isNotBlank()
                ) { Text("Add account") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }

    Scaffold(snackbarHost = snackbarHost) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PageHeader(
                    title = "Accounts",
                    subtitle = "Offline profiles and Microsoft-owned Minecraft accounts",
                    action = {
                        FilledTonalButton(onClick = { showDialog = true }) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Text("Offline", modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                )
            }

            item {
                LauncherCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Language, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Microsoft account", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (state.snapshot.settings.microsoftClientId.isBlank()) {
                                    "Add your Microsoft application client ID in Settings first."
                                } else {
                                    "Uses Microsoft's device-code flow and stores the resulting session encrypted on this device."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = onMicrosoftLogin,
                            enabled = state.snapshot.settings.microsoftClientId.isNotBlank() && state.microsoftStatus == null
                        ) { Text("Sign in") }
                    }
                    state.microsoftDeviceCode?.let { code ->
                        Text(
                            "Code: ${code.user_code}",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(state.microsoftStatus.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onOpenMicrosoftPage) { Text("Open Microsoft sign-in page") }
                    }
                }
            }

            if (state.snapshot.accounts.isEmpty()) {
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Person, contentDescription = null)
                        Text("No account selected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
                        Text(
                            "Add an offline profile or sign in with a Microsoft account before launching Minecraft.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }

            items(state.snapshot.accounts, key = { it.id }) { account ->
                val selected = account.id == state.snapshot.selectedAccountId
                LauncherCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AccountAvatar(
                            skinUrl = account.skinUrl,
                            selected = selected,
                            username = account.username
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.username, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (account.type == AccountType.MICROSOFT) "Microsoft • ${account.profileId}" else "Offline • ${account.id}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!selected) FilledTonalButton(onClick = { onSelectAccount(account.id) }) { Text("Use") }
                        else Text("Active", color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { onRemoveAccount(account.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove account")
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun AccountAvatar(skinUrl: String?, selected: Boolean, username: String) {
    val head by produceState<Bitmap?>(initialValue = null, skinUrl) {
        value = if (skinUrl.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                URL(skinUrl).openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "MCLauncher/11.0")
                }.getInputStream().use { BitmapFactory.decodeStream(it) }?.let { skin ->
                    val unit = (skin.width / 64).coerceAtLeast(1)
                    val size = 8 * unit
                    if (skin.width >= 16 * unit && skin.height >= 16 * unit) {
                        Bitmap.createBitmap(skin, 8 * unit, 8 * unit, size, size)
                    } else skin
                }
            }.getOrNull()
        }
    }
    if (head != null) {
        Image(
            bitmap = head!!.asImageBitmap(),
            contentDescription = "$username skin",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.size(42.dp)
        )
    } else {
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Person,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp)
        )
    }
}
