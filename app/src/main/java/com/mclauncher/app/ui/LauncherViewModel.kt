package com.mclauncher.app.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mclauncher.app.BuildConfig
import com.mclauncher.app.MCLauncherApplication
import com.mclauncher.app.auth.MicrosoftAuthManager
import com.mclauncher.app.auth.MicrosoftDeviceCode
import com.mclauncher.app.auth.SecureTokenStore
import com.mclauncher.app.engine.ComponentCatalog
import com.mclauncher.app.engine.ComponentPackage
import com.mclauncher.app.engine.ComponentPackageType
import com.mclauncher.app.engine.EngineEnvironmentState
import com.mclauncher.app.engine.EnginePackManager
import com.mclauncher.app.engine.NativeEngineCoordinator
import com.mclauncher.app.engine.NativeLaunchBridge
import com.mclauncher.app.engine.PackageCatalogManager
import com.mclauncher.minecraft.CurseForgeMod
import com.mclauncher.minecraft.CurseForgeRepository
import com.mclauncher.minecraft.InstalledContent
import com.mclauncher.minecraft.LaunchPlanBuilder
import com.mclauncher.minecraft.LoaderInstaller
import com.mclauncher.minecraft.LoaderVersionChoice
import com.mclauncher.minecraft.ModrinthProject
import com.mclauncher.minecraft.ModrinthRepository
import com.mclauncher.minecraft.MojangVersionRepository
import com.mclauncher.minecraft.MojangVersionSummary
import com.mclauncher.minecraft.VanillaInstaller
import com.mclauncher.model.AccountType
import com.mclauncher.model.ContentSource
import com.mclauncher.model.ContentType
import com.mclauncher.model.InstallProgress
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.LauncherSnapshot
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import com.mclauncher.model.OfflineAccountFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

sealed interface LauncherEvent {
    data class OpenGame(val launchPlan: File, val closeLauncher: Boolean) : LauncherEvent
    data class OpenUrl(val url: String) : LauncherEvent
    data class ShareFile(val file: File, val mimeType: String = "text/plain") : LauncherEvent
    data class OpenFile(val file: File, val mimeType: String) : LauncherEvent
}

data class LauncherUiState(
    val loading: Boolean = true,
    val snapshot: LauncherSnapshot = LauncherSnapshot(),
    val versions: List<MojangVersionSummary> = emptyList(),
    val latestRelease: String? = null,
    val latestSnapshot: String? = null,
    val installProgress: InstallProgress? = null,
    val activeInstallVersion: String? = null,
    val engineEnvironment: EngineEnvironmentState? = null,
    val engineOperation: String? = null,
    val contentResults: List<ModrinthProject> = emptyList(),
    val curseForgeResults: List<CurseForgeMod> = emptyList(),
    val contentSource: ContentSource = ContentSource.MODRINTH,
    val contentType: ContentType = ContentType.MOD,
    val contentLoading: Boolean = false,
    val installedContent: List<InstalledContent> = emptyList(),
    val selectedContentInstanceId: String? = null,
    val loaderChoices: List<LoaderVersionChoice> = emptyList(),
    val loaderLoading: Boolean = false,
    val microsoftDeviceCode: MicrosoftDeviceCode? = null,
    val microsoftStatus: String? = null,
    val componentCatalog: ComponentCatalog? = null,
    val componentLoading: Boolean = false,
    val message: String? = null
) {
    val selectedAccount get() = snapshot.accounts.firstOrNull { it.id == snapshot.selectedAccountId }
    val orderedInstances: List<MinecraftInstance>
        get() = snapshot.instances.sortedWith(
            compareByDescending<MinecraftInstance> { it.favorite }
                .thenByDescending { it.lastPlayedAtEpochMs ?: it.createdAtEpochMs }
        )
}

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MCLauncherApplication
    private val store = app.launcherStore
    private val layout = app.minecraftLayout
    private val versionRepository = MojangVersionRepository(layout)
    private val installer = VanillaInstaller(layout)
    private val loaderInstaller = LoaderInstaller(layout)
    private val launchPlanBuilder = LaunchPlanBuilder(layout)
    private val enginePackManager = EnginePackManager(app, layout)
    private val modrinth = ModrinthRepository(layout)
    private val curseForge = CurseForgeRepository(layout)
    private val tokenStore = SecureTokenStore(app)
    private val microsoftAuth = MicrosoftAuthManager(tokenStore)
    private val packageCatalogManager = PackageCatalogManager(app)
    private val nativeEngineCoordinator = NativeEngineCoordinator(app)

    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    private val eventChannel = Channel<LauncherEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var lastPlayRequestAt = 0L

    init {
        viewModelScope.launch {
            val snapshot = store.load()
            _state.update { it.copy(snapshot = snapshot, loading = false) }
            refreshEngineEnvironment()
            refreshVersions()
        }
    }

    fun refreshEngineEnvironment() {
        viewModelScope.launch {
            runCatching { enginePackManager.inspect() }
                .onSuccess { environment -> _state.update { it.copy(engineEnvironment = environment) } }
                .onFailure { error -> _state.update { it.copy(message = "Engine inspection failed: ${error.message}") } }
        }
    }


    fun repairBundledEngine() {
        if (_state.value.engineOperation != null) return
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Restoring the engine packaged in MCLauncher") }
            runCatching {
                enginePackManager.installBundledEngine(force = true) { progress ->
                    _state.update { it.copy(engineOperation = progress) }
                }
            }.onSuccess { environment ->
                _state.update {
                    it.copy(
                        engineOperation = null,
                        engineEnvironment = environment,
                        message = "Bundled Minecraft engine restored"
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(engineOperation = null, message = "Bundled engine restore failed: ${error.message}") }
            }
        }
    }

    fun importRuntime(uri: Uri, javaVersion: JavaVersion) {
        if (_state.value.engineOperation != null) return
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Importing Java ${javaVersion.major}") }
            runCatching { enginePackManager.importRuntime(uri, javaVersion) }
                .onSuccess {
                    _state.update { it.copy(engineOperation = null, message = "Java ${javaVersion.major} runtime installed") }
                    refreshEngineEnvironment()
                }
                .onFailure { error ->
                    _state.update { it.copy(engineOperation = null, message = "Runtime import failed: ${error.message}") }
                }
        }
    }

    fun importEnginePack(uri: Uri) {
        if (_state.value.engineOperation != null) return
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Importing Android game engine") }
            runCatching { enginePackManager.importEnginePack(uri) }
                .onSuccess {
                    _state.update { it.copy(engineOperation = null, message = "Android LWJGL engine pack installed") }
                    refreshEngineEnvironment()
                }
                .onFailure { error ->
                    _state.update { it.copy(engineOperation = null, message = "Engine import failed: ${error.message}") }
                }
        }
    }

    fun importGraphicsPack(uri: Uri) {
        if (_state.value.engineOperation != null) return
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Importing renderer or graphics driver") }
            runCatching { enginePackManager.importGraphicsPack(uri) }
                .onSuccess { pack ->
                    _state.update {
                        it.copy(engineOperation = null, message = "${pack.name} ${pack.version} installed for ${pack.architecture}")
                    }
                    refreshEngineEnvironment()
                }
                .onFailure { error ->
                    _state.update { it.copy(engineOperation = null, message = "Graphics import failed: ${error.message}") }
                }
        }
    }

    fun loadComponentCatalog() {
        val settings = _state.value.snapshot.settings
        val url = settings.componentCatalogUrl.trim().ifBlank { settings.runtimeCatalogUrl.trim() }
        if (url.isBlank()) {
            _state.update { it.copy(message = "Enter a component catalog URL in Settings") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(componentLoading = true) }
            runCatching { packageCatalogManager.load(url) }
                .onSuccess { catalog -> _state.update { it.copy(componentCatalog = catalog, componentLoading = false) } }
                .onFailure { error -> _state.update { it.copy(componentLoading = false, message = "Catalog failed: ${error.message}") } }
        }
    }

    fun installComponent(item: ComponentPackage) {
        if (_state.value.engineOperation != null) return
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Downloading ${item.name}") }
            runCatching {
                val uri = packageCatalogManager.download(item)
                when (item.type) {
                    ComponentPackageType.RUNTIME -> enginePackManager.importRuntime(
                        uri,
                        item.javaVersion ?: error("Runtime package has no Java version")
                    )
                    ComponentPackageType.ENGINE -> error("External engine replacement is disabled in standalone builds")
                    ComponentPackageType.RENDERER,
                    ComponentPackageType.DRIVER -> enginePackManager.importGraphicsPack(uri)
                }
            }.onSuccess {
                _state.update { it.copy(engineOperation = null, message = "${item.name} installed") }
                refreshEngineEnvironment()
            }.onFailure { error ->
                _state.update { it.copy(engineOperation = null, message = "Component install failed: ${error.message}") }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun reportUiError(message: String) = _state.update { it.copy(message = message) }

    fun addOfflineAccount(username: String) {
        viewModelScope.launch {
            runCatching { OfflineAccountFactory.create(username) }
                .onSuccess { account ->
                    val current = _state.value.snapshot
                    updateSnapshot(current.copy(
                        accounts = current.accounts.filterNot { it.id == account.id } + account,
                        selectedAccountId = account.id
                    ))
                    _state.update { it.copy(message = "Offline account ${account.username} added") }
                }
                .onFailure { error -> _state.update { it.copy(message = error.message ?: "Could not add account") } }
        }
    }

    fun beginMicrosoftLogin() {
        if (BuildConfig.PUBLIC_ALPHA_SIGNER) {
            _state.update { it.copy(message = "Microsoft sign-in is disabled in publicly signed alpha builds") }
            return
        }
        val clientId = _state.value.snapshot.settings.microsoftClientId.trim()
        viewModelScope.launch {
            _state.update { it.copy(microsoftStatus = "Requesting Microsoft code") }
            runCatching { microsoftAuth.beginDeviceCode(clientId) }
                .onSuccess { code ->
                    _state.update { it.copy(microsoftDeviceCode = code, microsoftStatus = code.message.ifBlank { "Enter ${code.user_code} in your browser" }) }
                    eventChannel.send(LauncherEvent.OpenUrl(code.verification_uri))
                    runCatching {
                        microsoftAuth.completeDeviceCode(clientId, code) { status ->
                            _state.update { it.copy(microsoftStatus = status) }
                        }
                    }.onSuccess { account ->
                        val current = _state.value.snapshot
                        updateSnapshot(current.copy(
                            accounts = current.accounts.filterNot { it.id == account.id } + account,
                            selectedAccountId = account.id
                        ))
                        _state.update { it.copy(microsoftDeviceCode = null, microsoftStatus = null, message = "Signed in as ${account.username}") }
                    }.onFailure { error ->
                        _state.update { it.copy(microsoftStatus = null, message = "Microsoft sign-in failed: ${error.message}") }
                    }
                }
                .onFailure { error -> _state.update { it.copy(microsoftStatus = null, message = "Microsoft sign-in failed: ${error.message}") } }
        }
    }

    fun openMicrosoftVerificationPage() {
        _state.value.microsoftDeviceCode?.let { code ->
            viewModelScope.launch { eventChannel.send(LauncherEvent.OpenUrl(code.verification_uri)) }
        }
    }

    fun selectAccount(accountId: String) {
        viewModelScope.launch {
            val current = _state.value.snapshot
            val now = System.currentTimeMillis()
            updateSnapshot(current.copy(
                accounts = current.accounts.map { if (it.id == accountId) it.copy(lastUsedAtEpochMs = now) else it },
                selectedAccountId = accountId
            ))
        }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            tokenStore.remove(accountId)
            val current = _state.value.snapshot
            val remaining = current.accounts.filterNot { it.id == accountId }
            val selected = if (current.selectedAccountId == accountId) remaining.firstOrNull()?.id else current.selectedAccountId
            updateSnapshot(current.copy(accounts = remaining, selectedAccountId = selected))
        }
    }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch {
            val current = _state.value.snapshot
            updateSnapshot(current.copy(settings = transform(current.settings)))
        }
    }

    fun refreshVersions(force: Boolean = false) {
        viewModelScope.launch {
            runCatching { versionRepository.loadManifest(forceRefresh = force) }
                .onSuccess { manifest ->
                    _state.update { it.copy(versions = manifest.versions, latestRelease = manifest.latest.release, latestSnapshot = manifest.latest.snapshot) }
                }
                .onFailure { error -> _state.update { it.copy(message = "Version list unavailable: ${error.message}") } }
        }
    }

    fun loadLoaderChoices(loader: ModLoader, gameVersion: String) {
        viewModelScope.launch {
            _state.update { it.copy(loaderLoading = true, loaderChoices = emptyList()) }
            runCatching { loaderInstaller.available(loader, gameVersion) }
                .onSuccess { choices -> _state.update { it.copy(loaderLoading = false, loaderChoices = choices) } }
                .onFailure { error -> _state.update { it.copy(loaderLoading = false, message = "Loader versions unavailable: ${error.message}") } }
        }
    }

    fun installVersion(
        version: MojangVersionSummary,
        loader: ModLoader = ModLoader.VANILLA,
        loaderVersion: String? = null
    ) {
        if (_state.value.activeInstallVersion != null) {
            _state.update { it.copy(message = "Another installation is already running") }
            return
        }
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val directoryName = "${version.id}-${loader.id}-${id.take(8)}"
            var instance = MinecraftInstance(
                id = id,
                name = if (loader == ModLoader.VANILLA) "Minecraft ${version.id}" else "${loader.displayName} ${version.id}",
                versionId = version.id,
                gameDirectoryName = directoryName,
                javaVersion = _state.value.snapshot.settings.selectedJava,
                loader = loader,
                loaderVersion = loaderVersion,
                createdAtEpochMs = System.currentTimeMillis(),
                installed = false
            )
            val before = _state.value.snapshot
            updateSnapshot(before.copy(instances = before.instances + instance))
            _state.update { it.copy(activeInstallVersion = version.id, installProgress = InstallProgress(message = "Preparing installation")) }

            runCatching {
                if (loader == ModLoader.VANILLA) {
                    installer.install(version) { progress -> _state.update { it.copy(installProgress = progress) } }
                    instance.copy(installed = true)
                } else {
                    val selectedLoaderVersion = loaderVersion ?: loaderInstaller.available(loader, version.id).firstOrNull()?.version
                        ?: error("No compatible ${loader.displayName} version was found")
                    var loaderInstance = loaderInstaller.installProfile(
                        instance.copy(loaderVersion = selectedLoaderVersion),
                        selectedLoaderVersion
                    ) { text ->
                        _state.update { it.copy(installProgress = InstallProgress(message = text)) }
                    }
                    if (!loaderInstance.installed && loader in listOf(ModLoader.FORGE, ModLoader.NEOFORGE)) {
                        val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                        val environment = enginePackManager.inspect()
                        require(environment.nativeBridgeAvailable) { "Native engine is required to run the ${loader.displayName} installer" }
                        require(environment.enginePack.installed) { "Install the Android LWJGL engine pack first" }
                        require(environment.runtimes.firstOrNull { it.version == loaderInstance.javaVersion }?.installed == true) {
                            "Install Java ${loaderInstance.javaVersion.major} for $architecture first"
                        }
                        _state.update { it.copy(installProgress = InstallProgress(message = "Running ${loader.displayName} installer")) }
                        val toolFile = loaderInstaller.toolPlanFile(loaderInstance)
                        val exitCode = nativeEngineCoordinator.runTool(toolFile, architecture)
                        val toolLog = NativeLaunchBridge.nativeDrainLogs().orEmpty()
                        File(layout.root, "logs/loader-${loader.id}-${System.currentTimeMillis()}.log").apply {
                            parentFile?.mkdirs()
                            writeText(toolLog)
                        }
                        require(exitCode == 0) { "${loader.displayName} installer exited with code $exitCode" }
                        loaderInstance = loaderInstaller.finalizeInstaller(loaderInstance, selectedLoaderVersion)
                    }
                    loaderInstance
                }
            }.onSuccess { installed ->
                instance = installed
                val current = _state.value.snapshot
                updateSnapshot(current.copy(instances = current.instances.map { if (it.id == id) installed else it }))
                _state.update {
                    it.copy(
                        activeInstallVersion = null,
                        installProgress = null,
                        message = if (installed.installed) "${installed.name} installed" else "${installed.name} installer prepared; finish it from Engine setup"
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(activeInstallVersion = null, installProgress = null, message = "Installation failed: ${error.message}") }
            }
        }
    }

    fun searchContent(
        query: String,
        contentType: ContentType,
        instanceId: String?,
        source: ContentSource = ContentSource.MODRINTH
    ) {
        val instance = _state.value.snapshot.instances.firstOrNull { it.id == instanceId }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    contentLoading = true,
                    contentSource = source,
                    contentType = contentType,
                    selectedContentInstanceId = instanceId,
                    contentResults = emptyList(),
                    curseForgeResults = emptyList()
                )
            }
            when (source) {
                ContentSource.MODRINTH -> runCatching {
                    modrinth.search(
                        query = query,
                        contentType = contentType,
                        gameVersion = instance?.let(::baseGameVersion),
                        loader = instance?.loader
                    )
                }.onSuccess { result ->
                    _state.update { it.copy(contentResults = result.hits, contentLoading = false) }
                }.onFailure { error ->
                    _state.update { it.copy(contentLoading = false, message = "Modrinth search failed: ${error.message}") }
                }
                ContentSource.CURSEFORGE -> runCatching {
                    curseForge.search(
                        apiKey = _state.value.snapshot.settings.curseForgeApiKey,
                        query = query,
                        contentType = contentType,
                        gameVersion = instance?.let(::baseGameVersion),
                        loader = instance?.loader
                    )
                }.onSuccess { result ->
                    _state.update { it.copy(curseForgeResults = result.data, contentLoading = false) }
                }.onFailure { error ->
                    _state.update { it.copy(contentLoading = false, message = "CurseForge search failed: ${error.message}") }
                }
            }
        }
    }

    fun installContent(project: ModrinthProject) {
        val state = _state.value
        val instance = state.snapshot.instances.firstOrNull { it.id == state.selectedContentInstanceId }
        if (instance == null) {
            _state.update { it.copy(message = "Select an installed instance first") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Installing ${project.title}") }
            runCatching {
                val versions = modrinth.versions(project.project_id, baseGameVersion(instance), instance.loader)
                val version = versions.firstOrNull() ?: error("No compatible file for ${instance.name}")
                modrinth.install(
                    instance = instance,
                    project = project,
                    version = version,
                    contentType = state.contentType,
                    installDependencies = state.snapshot.settings.autoInstallDependencies
                ) { text -> _state.update { it.copy(engineOperation = text) } }
            }.onSuccess {
                _state.update { it.copy(engineOperation = null, message = "${project.title} installed") }
                loadInstalledContent(instance.id)
            }.onFailure { error -> _state.update { it.copy(engineOperation = null, message = "Content install failed: ${error.message}") } }
        }
    }

    fun installCurseForgeContent(mod: CurseForgeMod) {
        val currentState = _state.value
        val instance = currentState.snapshot.instances.firstOrNull { it.id == currentState.selectedContentInstanceId }
        if (instance == null) {
            _state.update { it.copy(message = "Select an installed instance first") }
            return
        }
        val apiKey = currentState.snapshot.settings.curseForgeApiKey
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Installing ${mod.name}") }
            runCatching {
                val gameVersion = baseGameVersion(instance)
                val compatible = curseForge.files(apiKey, mod.id, gameVersion)
                val selectedFile = compatible.firstOrNull()
                    ?: mod.latestFiles.firstOrNull { gameVersion in it.gameVersions }
                    ?: error("No compatible file for ${instance.name}")
                curseForge.install(
                    apiKey = apiKey,
                    instance = instance,
                    mod = mod,
                    file = selectedFile,
                    contentType = currentState.contentType,
                    gameVersion = gameVersion,
                    installDependencies = currentState.snapshot.settings.autoInstallDependencies
                ) { text -> _state.update { it.copy(engineOperation = text) } }
            }.onSuccess {
                _state.update { it.copy(engineOperation = null, message = "${mod.name} installed") }
                loadInstalledContent(instance.id)
            }.onFailure { error ->
                _state.update { it.copy(engineOperation = null, message = "CurseForge install failed: ${error.message}") }
            }
        }
    }

    fun loadInstalledContent(instanceId: String) {
        val instance = _state.value.snapshot.instances.firstOrNull { it.id == instanceId } ?: return
        viewModelScope.launch {
            val items = runCatching { modrinth.listInstalled(instance) }.getOrDefault(emptyList())
            _state.update { it.copy(installedContent = items, selectedContentInstanceId = instanceId) }
        }
    }

    fun updateManagedContent(instanceId: String) {
        val instance = _state.value.snapshot.instances.firstOrNull { it.id == instanceId } ?: return
        viewModelScope.launch {
            _state.update { it.copy(engineOperation = "Checking content updates") }
            runCatching { updateContentForInstance(instance) }
                .onSuccess { count ->
                    _state.update {
                        it.copy(
                            engineOperation = null,
                            message = if (count == 0) "All managed content is current" else "Updated $count content item${if (count == 1) "" else "s"}"
                        )
                    }
                    loadInstalledContent(instance.id)
                }
                .onFailure { error ->
                    _state.update { it.copy(engineOperation = null, message = "Content update failed: ${error.message}") }
                }
        }
    }

    fun toggleContent(item: InstalledContent) {
        val instance = _state.value.snapshot.instances.firstOrNull { it.id == _state.value.selectedContentInstanceId } ?: return
        viewModelScope.launch {
            runCatching { modrinth.setEnabled(instance, item, !item.enabled) }
                .onSuccess { loadInstalledContent(instance.id) }
                .onFailure { error -> _state.update { it.copy(message = "Could not change content: ${error.message}") } }
        }
    }

    fun removeContent(item: InstalledContent) {
        val instance = _state.value.snapshot.instances.firstOrNull { it.id == _state.value.selectedContentInstanceId } ?: return
        viewModelScope.launch {
            runCatching { modrinth.remove(instance, item) }
                .onSuccess { loadInstalledContent(instance.id) }
                .onFailure { error -> _state.update { it.copy(message = "Could not remove content: ${error.message}") } }
        }
    }

    fun play(instanceId: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayRequestAt < 2_500L) {
            _state.update { it.copy(message = "Minecraft is already being prepared") }
            return
        }
        lastPlayRequestAt = now
        viewModelScope.launch {
            val snapshot = _state.value.snapshot
            val instance = snapshot.instances.firstOrNull { it.id == instanceId }
            if (instance == null) return@launch _state.update { it.copy(message = "Instance not found") }
            if (!instance.installed) return@launch _state.update { it.copy(message = "Finish installing this instance first") }
            val account = _state.value.selectedAccount
                ?: return@launch _state.update { it.copy(message = "Add or select an account before playing") }
            if (BuildConfig.PUBLIC_ALPHA_SIGNER && account.type == AccountType.MICROSOFT) {
                return@launch _state.update {
                    it.copy(message = "Select an offline account for this publicly signed alpha build")
                }
            }

            val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val engine = runCatching { _state.value.engineEnvironment ?: enginePackManager.inspect() }.getOrElse { error ->
                _state.update { it.copy(message = "Cannot inspect the launch engine: ${error.message}") }
                return@launch
            }
            if (engine.runtimes.firstOrNull { it.version == instance.javaVersion }?.installed != true) {
                return@launch _state.update { it.copy(message = "Bundled Java ${instance.javaVersion.major} is missing for $architecture. Rebuild or repair the bundled engine.") }
            }
            if (!engine.enginePack.installed) return@launch _state.update { it.copy(message = "The APK is missing its bundled Android LWJGL engine") }
            if (!engine.nativeBridgeAvailable) return@launch _state.update { it.copy(message = "The built-in native launch engine is unavailable") }

            _state.update { it.copy(message = "Preparing launch plan") }
            runCatching {
                if (snapshot.settings.autoUpdateContent) {
                    _state.update { it.copy(engineOperation = "Updating managed content before launch") }
                    updateContentForInstance(instance)
                    _state.update { it.copy(engineOperation = null) }
                }
                val authSession = if (account.type == AccountType.MICROSOFT) microsoftAuth.refresh(account) else null
                val plan = launchPlanBuilder.build(
                    instance = instance,
                    account = account,
                    settings = snapshot.settings,
                    authSession = authSession,
                    architecture = architecture
                )
                launchPlanBuilder.save(plan)
            }.onSuccess { file ->
                val now = System.currentTimeMillis()
                val current = _state.value.snapshot
                updateSnapshot(current.copy(instances = current.instances.map {
                    if (it.id == instance.id) it.copy(lastPlayedAtEpochMs = now) else it
                }))
                _state.update { it.copy(message = null) }
                eventChannel.send(LauncherEvent.OpenGame(file, closeLauncher = !current.settings.keepLauncherOpen))
            }.onFailure { error -> _state.update { it.copy(message = "Cannot launch: ${error.message}") } }
        }
    }

    fun deleteInstance(instanceId: String) {
        viewModelScope.launch {
            val current = _state.value.snapshot
            val instance = current.instances.firstOrNull { it.id == instanceId } ?: return@launch
            runCatching { layout.instanceGameDirectory(instance.gameDirectoryName).deleteRecursively() }
            updateSnapshot(current.copy(instances = current.instances.filterNot { it.id == instanceId }))
            _state.update { it.copy(message = "Instance removed") }
        }
    }

    fun updateInstance(instanceId: String, transform: (MinecraftInstance) -> MinecraftInstance) {
        viewModelScope.launch {
            val current = _state.value.snapshot
            updateSnapshot(current.copy(instances = current.instances.map { if (it.id == instanceId) transform(it) else it }))
        }
    }

    fun shareLatestLog() {
        val log = File(layout.root, "logs/latest-session.log")
        if (!log.isFile) return _state.update { it.copy(message = "No session log exists yet") }
        viewModelScope.launch { eventChannel.send(LauncherEvent.ShareFile(log)) }
    }

    fun shareFile(file: File, mimeType: String = "text/plain") {
        if (!file.isFile) return
        viewModelScope.launch { eventChannel.send(LauncherEvent.ShareFile(file, mimeType)) }
    }

    fun openScreenshot(file: File) {
        if (!file.isFile) return
        viewModelScope.launch { eventChannel.send(LauncherEvent.OpenFile(file, "image/*")) }
    }

    fun screenshots(instanceId: String): List<File> {
        val instance = _state.value.snapshot.instances.firstOrNull { it.id == instanceId } ?: return emptyList()
        return File(layout.instanceGameDirectory(instance.gameDirectoryName), "screenshots")
            .listFiles().orEmpty().filter { it.isFile }.sortedByDescending(File::lastModified)
    }

    fun sessionLogs(): List<File> {
        val rootLogs = File(layout.root, "logs").listFiles().orEmpty().filter(File::isFile)
        val instanceLogs = _state.value.snapshot.instances.flatMap { instance ->
            val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName)
            listOf(File(gameDir, "logs"), File(gameDir, "crash-reports"))
                .flatMap { it.listFiles().orEmpty().filter(File::isFile) }
        }
        return (rootLogs + instanceLogs).distinctBy(File::getAbsolutePath).sortedByDescending(File::lastModified)
    }

    private suspend fun updateContentForInstance(instance: MinecraftInstance): Int {
        val items = modrinth.listInstalled(instance)
        var updated = 0
        val settings = _state.value.snapshot.settings
        items.forEachIndexed { index, item ->
            _state.update { it.copy(engineOperation = "Checking update ${index + 1}/${items.size}: ${item.title}") }
            if (item.projectId.startsWith("curseforge:")) {
                val apiKey = settings.curseForgeApiKey
                if (apiKey.isBlank()) return@forEachIndexed
                val latest = curseForge.updateAvailable(apiKey, instance, item) ?: return@forEachIndexed
                val modId = item.projectId.removePrefix("curseforge:").toIntOrNull() ?: return@forEachIndexed
                val mod = curseForge.mod(apiKey, modId)
                curseForge.install(
                    apiKey = apiKey,
                    instance = instance,
                    mod = mod,
                    file = latest,
                    contentType = item.contentType,
                    gameVersion = item.gameVersion,
                    installDependencies = settings.autoInstallDependencies
                ) { message -> _state.update { it.copy(engineOperation = message) } }
                updated++
            } else {
                val latest = modrinth.updateAvailable(instance, item) ?: return@forEachIndexed
                val project = modrinth.project(item.projectId)
                modrinth.install(
                    instance = instance,
                    project = project,
                    version = latest,
                    contentType = item.contentType,
                    installDependencies = settings.autoInstallDependencies
                ) { message -> _state.update { it.copy(engineOperation = message) } }
                updated++
            }
        }
        return updated
    }

    private fun baseGameVersion(instance: MinecraftInstance): String = when (instance.loader) {
        ModLoader.VANILLA -> instance.versionId
        else -> layout.versionJson(instance.versionId).takeIf(File::isFile)?.let { file ->
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonObject["inheritsFrom"]?.jsonPrimitive?.content
            }.getOrNull()
        } ?: instance.versionId.substringBefore("-${instance.loader.id}")
    }

    private suspend fun updateSnapshot(snapshot: LauncherSnapshot) {
        _state.update { it.copy(snapshot = snapshot) }
        store.save(snapshot)
    }
}
