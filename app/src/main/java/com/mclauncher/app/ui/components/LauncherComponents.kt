package com.mclauncher.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mclauncher.model.InstallProgress
import java.util.Locale

@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action?.invoke()
    }
}

@Composable
fun LauncherCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) { content() }
    }
}

@Composable
fun InstallProgressCard(
    progress: InstallProgress,
    title: String,
    modifier: Modifier = Modifier
) {
    LauncherCard(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            progress.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        val determinate = progress.totalFiles > 0 || (progress.totalBytes ?: 0L) > 0L
        if (determinate) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val totalBytes = progress.totalBytes
        val details = buildList {
            if (progress.totalFiles > 0) add("${progress.completedFiles} / ${progress.totalFiles} files")
            if (totalBytes != null && totalBytes > 0L) {
                add("${formatBytes(progress.downloadedBytes)} / ${formatBytes(totalBytes)}")
            } else if (progress.downloadedBytes > 0L) {
                add(formatBytes(progress.downloadedBytes))
            }
            if (progress.currentFile.isNotBlank()) add(progress.currentFile)
        }
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
