package com.mclauncher.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mclauncher.app.R
import com.mclauncher.model.MinecraftInstance
import java.io.File

sealed class LauncherDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : LauncherDestination("home", "Home", Icons.Rounded.Home)
    data object Library : LauncherDestination("library", "Library", Icons.Rounded.ViewList)
    data object Discover : LauncherDestination("discover", "Browse", Icons.Rounded.Explore)
    data object Accounts : LauncherDestination("accounts", "Accounts", Icons.Rounded.AccountCircle)
    data object Settings : LauncherDestination("settings", "Settings", Icons.Rounded.Settings)

    companion object {
        val primary = listOf(Home, Library, Discover)
        val topLevel = primary + listOf(Accounts, Settings)
    }
}

/**
 * Responsive Android shell modelled after the information hierarchy of the
 * open-source Modrinth App. It deliberately keeps MCLauncher's original name,
 * logo and Android-native implementation; Modrinth branding assets are not
 * licensed for derivative launchers.
 */
@Composable
fun LauncherShell(
    currentRoute: String?,
    quickInstances: List<MinecraftInstance>,
    activeAccountName: String?,
    onNavigate: (LauncherDestination) -> Unit,
    onOpenInstance: (String) -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                WideLauncherSidebar(
                    currentRoute = currentRoute,
                    quickInstances = quickInstances,
                    activeAccountName = activeAccountName,
                    onNavigate = onNavigate,
                    onOpenInstance = onOpenInstance
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    content()
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        LauncherDestination.topLevel.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = { onNavigate(destination) },
                                icon = { Icon(destination.icon, contentDescription = destination.label) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun WideLauncherSidebar(
    currentRoute: String?,
    quickInstances: List<MinecraftInstance>,
    activeAccountName: String?,
    onNavigate: (LauncherDestination) -> Unit,
    onOpenInstance: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .width(252.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.mclauncher_logo),
                    contentDescription = "MCLauncher logo",
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Column {
                    Text(
                        text = "MCLauncher",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Java Edition for Android",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            LauncherDestination.primary.forEach { destination ->
                SidebarDestination(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text(
                text = "QUICK INSTANCES",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (quickInstances.isEmpty()) {
                    Text(
                        text = "Install a version from Browse",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    quickInstances.take(6).forEach { instance ->
                        QuickInstanceButton(instance = instance, onClick = { onOpenInstance(instance.id) })
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
            SidebarDestination(
                destination = LauncherDestination.Accounts,
                selected = currentRoute == LauncherDestination.Accounts.route,
                supportingText = activeAccountName ?: "No account selected",
                onClick = { onNavigate(LauncherDestination.Accounts) }
            )
            SidebarDestination(
                destination = LauncherDestination.Settings,
                selected = currentRoute == LauncherDestination.Settings.route,
                onClick = { onNavigate(LauncherDestination.Settings) }
            )
        }
    }
}

@Composable
private fun SidebarDestination(
    destination: LauncherDestination,
    selected: Boolean,
    supportingText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (supportingText == null) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(21.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickInstanceButton(instance: MinecraftInstance, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val icon = instance.iconPath?.let(::File)?.takeIf { it.isFile }
        if (icon != null) {
            AsyncImage(
                model = icon,
                contentDescription = "${instance.name} icon",
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
        } else {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = instance.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = instance.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = instance.runtimeLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
    }
}
