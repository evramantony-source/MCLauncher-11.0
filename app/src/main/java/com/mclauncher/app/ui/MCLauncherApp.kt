package com.mclauncher.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mclauncher.app.GameActivity
import com.mclauncher.app.ui.components.LauncherDestination
import com.mclauncher.app.ui.components.LauncherShell
import com.mclauncher.app.ui.screens.AccountsScreen
import com.mclauncher.app.ui.screens.ControlEditorScreen
import com.mclauncher.app.ui.screens.DiscoverScreen
import com.mclauncher.app.ui.screens.HomeScreen
import com.mclauncher.app.ui.screens.InstanceDetailScreen
import com.mclauncher.app.ui.screens.LibraryScreen
import com.mclauncher.app.ui.screens.LogsScreen
import com.mclauncher.app.ui.screens.SettingsScreen
import com.mclauncher.app.ui.theme.MCLauncherTheme
import kotlinx.coroutines.flow.collect

@Composable
fun MCLauncherApp(launcherViewModel: LauncherViewModel = viewModel()) {
    val state by launcherViewModel.state.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        launcherViewModel.events.collect { event ->
            runCatching {
                when (event) {
                    is LauncherEvent.OpenGame -> {
                        context.startActivity(
                            Intent(context, GameActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra(GameActivity.EXTRA_PLAN_PATH, event.launchPlan.absolutePath)
                        )
                        if (event.closeLauncher) (context as? Activity)?.finish()
                    }
                    is LauncherEvent.OpenUrl -> {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url)))
                    }
                    is LauncherEvent.ShareFile -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType(event.mimeType)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Share ${event.file.name}"
                            )
                        )
                    }
                    is LauncherEvent.OpenFile -> {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", event.file)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, event.mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                }
            }.onFailure { launcherViewModel.reportUiError("Could not open requested action: ${it.message}") }
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        launcherViewModel.clearMessage()
    }

    MCLauncherTheme(themeMode = state.snapshot.settings.themeMode) {
        LauncherShell(
            currentRoute = currentRoute,
            quickInstances = state.orderedInstances,
            activeAccountName = state.selectedAccount?.username,
            onNavigate = { destination ->
                navController.navigate(destination.route) {
                    popUpTo(LauncherDestination.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onOpenInstance = { navController.navigate("instance/$it") }
        ) {
            NavHost(navController = navController, startDestination = LauncherDestination.Home.route) {
            composable(LauncherDestination.Home.route) {
                HomeScreen(
                    state = state,
                    onPlay = launcherViewModel::play,
                    onOpenLibrary = { navController.navigate(LauncherDestination.Library.route) },
                    onOpenDiscover = { navController.navigate(LauncherDestination.Discover.route) },
                    onOpenAccounts = { navController.navigate(LauncherDestination.Accounts.route) },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable(LauncherDestination.Library.route) {
                LibraryScreen(
                    state = state,
                    onPlay = launcherViewModel::play,
                    onOpenInstance = { navController.navigate("instance/$it") },
                    onDiscover = { navController.navigate(LauncherDestination.Discover.route) },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable(LauncherDestination.Discover.route) {
                DiscoverScreen(
                    state = state,
                    onRefresh = { launcherViewModel.refreshVersions(force = true) },
                    onInstall = launcherViewModel::installVersion,
                    onLoadLoaderChoices = launcherViewModel::loadLoaderChoices,
                    onSearchContent = launcherViewModel::searchContent,
                    onSelectContentInstance = launcherViewModel::selectContentInstance,
                    onInstallContent = launcherViewModel::installContent,
                    onInstallCurseForgeContent = launcherViewModel::installCurseForgeContent,
                    onShowModrinthVersions = launcherViewModel::showModrinthVersions,
                    onShowCurseForgeVersions = launcherViewModel::showCurseForgeVersions,
                    onInstallContentVersion = launcherViewModel::installContentVersion,
                    onInstallCurseForgeVersion = launcherViewModel::installCurseForgeVersion,
                    onDismissContentVersions = launcherViewModel::dismissContentVersions,
                    onOpenSettings = { navController.navigate(LauncherDestination.Settings.route) },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable(LauncherDestination.Accounts.route) {
                AccountsScreen(
                    state = state,
                    onAddAccount = launcherViewModel::addOfflineAccount,
                    onMicrosoftLogin = launcherViewModel::beginMicrosoftLogin,
                    onOpenMicrosoftPage = launcherViewModel::openMicrosoftVerificationPage,
                    onSelectAccount = launcherViewModel::selectAccount,
                    onRemoveAccount = launcherViewModel::removeAccount,
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable(LauncherDestination.Settings.route) {
                SettingsScreen(
                    state = state,
                    onUpdateSettings = launcherViewModel::updateSettings,
                    onImportRuntime = launcherViewModel::importRuntime,
                    onRepairBundledEngine = launcherViewModel::repairBundledEngine,
                    onImportGraphicsPack = launcherViewModel::importGraphicsPack,
                    onRefreshEngine = launcherViewModel::refreshEngineEnvironment,
                    onLoadCatalog = launcherViewModel::loadComponentCatalog,
                    onInstallComponent = launcherViewModel::installComponent,
                    onOpenControls = { navController.navigate("controls") },
                    onOpenLogs = { navController.navigate("logs") },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable("controls") {
                ControlEditorScreen(
                    settings = state.snapshot.settings,
                    onBack = { navController.popBackStack() },
                    onUpdateSettings = launcherViewModel::updateSettings,
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable("logs") {
                LogsScreen(
                    logs = launcherViewModel.sessionLogs(),
                    onBack = { navController.popBackStack() },
                    onShare = { launcherViewModel.shareFile(it) },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            composable(
                route = "instance/{instanceId}",
                arguments = listOf(navArgument("instanceId") { type = NavType.StringType })
            ) { entry ->
                val instanceId = entry.arguments?.getString("instanceId").orEmpty()
                InstanceDetailScreen(
                    instanceId = instanceId,
                    state = state,
                    screenshots = launcherViewModel.screenshots(instanceId),
                    onBack = { navController.popBackStack() },
                    onPlay = launcherViewModel::play,
                    onDelete = {
                        launcherViewModel.deleteInstance(it)
                        navController.popBackStack()
                    },
                    onUpdateInstance = launcherViewModel::updateInstance,
                    onLoadContent = launcherViewModel::loadInstalledContent,
                    onUpdateContent = launcherViewModel::updateManagedContent,
                    onToggleContent = launcherViewModel::toggleContent,
                    onRemoveContent = launcherViewModel::removeContent,
                    onOpenScreenshot = launcherViewModel::openScreenshot,
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                )
            }
            }
        }
    }
}
