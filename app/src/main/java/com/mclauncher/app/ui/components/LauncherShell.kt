package com.mclauncher.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

sealed class LauncherDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : LauncherDestination("home", "Home", Icons.Rounded.Home)
    data object Library : LauncherDestination("library", "Library", Icons.Rounded.ViewList)
    data object Discover : LauncherDestination("discover", "Discover", Icons.Rounded.Explore)
    data object Accounts : LauncherDestination("accounts", "Accounts", Icons.Rounded.AccountCircle)
    data object Settings : LauncherDestination("settings", "Settings", Icons.Rounded.Settings)

    companion object {
        val topLevel = listOf(Home, Library, Discover, Accounts, Settings)
    }
}

@Composable
fun LauncherShell(
    currentRoute: String?,
    onNavigate: (LauncherDestination) -> Unit,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    LauncherDestination.topLevel.forEach { destination ->
                        NavigationRailItem(
                            selected = currentRoute == destination.route,
                            onClick = { onNavigate(destination) },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
                VerticalDivider()
                Box(modifier = Modifier.fillMaxSize()) { content() }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
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
                ) { content() }
            }
        }
    }
}
