package com.mclauncher.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mclauncher.app.engine.CrashAnalyzer
import com.mclauncher.app.ui.components.LauncherCard
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LogsScreen(
    logs: List<File>,
    onBack: () -> Unit,
    onShare: (File) -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    var selected by remember(logs) { mutableStateOf(logs.firstOrNull()) }
    val preview = selected?.let { file ->
        runCatching { file.readLines().takeLast(250).joinToString("\n") }.getOrElse { "Could not read ${file.name}: ${it.message}" }
    }.orEmpty()
    val diagnosis = CrashAnalyzer.analyze(selected)

    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text("Logs and crashes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null)
                        Text("No launcher or game logs exist yet.", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            items(logs, key = { it.absolutePath }) { file ->
                LauncherCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${DateFormat.getDateTimeInstance().format(Date(file.lastModified()))} • ${file.length() / 1024} KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { selected = file }) { Text("View") }
                        IconButton(onClick = { onShare(file) }) { Icon(Icons.Rounded.Share, contentDescription = "Share") }
                    }
                }
            }
            diagnosis?.let { result ->
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.BugReport, contentDescription = null)
                            Column {
                                Text(result.title, style = MaterialTheme.typography.titleMedium)
                                Text(result.advice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            selected?.let { file ->
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Text(file.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            preview.ifBlank { "This log is empty." },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 10.dp)
                        )
                    }
                }
            }
        }
    }
}
