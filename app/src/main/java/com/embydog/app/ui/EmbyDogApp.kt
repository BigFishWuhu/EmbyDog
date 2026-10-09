package com.embydog.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.safeDrawingPadding
import com.embydog.app.feature.settings.ProfileScreen
import com.embydog.app.feature.settings.UpdateScreen
import com.embydog.app.feature.settings.DanmakuSettingsScreen
import com.embydog.app.feature.settings.UpdateViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import com.embydog.app.core.model.MediaItem
import com.embydog.app.core.model.Session
import com.embydog.app.feature.login.LoginScreen
import com.embydog.app.feature.login.LoginViewModel
import com.embydog.app.feature.media.DetailScreen
import com.embydog.app.feature.media.BrowseScreen
import com.embydog.app.feature.media.HomeScreen
import com.embydog.app.feature.media.LibraryScreen
import com.embydog.app.feature.player.PlayerScreen
import com.embydog.app.ui.theme.EmbyDogTheme
import com.embydog.app.core.settings.AppSettings
import com.embydog.app.feature.settings.SettingsScreen
import com.embydog.app.feature.settings.SettingsViewModel
import com.embydog.app.feature.offline.OfflineScreen

@Composable
fun EmbyDogApp(sessionViewModel: SessionViewModel = hiltViewModel(), settingsViewModel: SettingsViewModel = hiltViewModel()) {
    EmbyDogTheme {
        val rootState by sessionViewModel.state.collectAsStateWithLifecycle()
        val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
        val settingsError by settingsViewModel.error.collectAsStateWithLifecycle()
        when (val state = rootState) {
            RootState.Loading -> LoadingScreen()
            RootState.SignedOut -> SignedOutProfiles(sessionViewModel, settings?.playbackOrientation ?: com.embydog.app.core.settings.PlaybackOrientation.LANDSCAPE)
            is RootState.SignedIn -> key(state.session.serverUrl, state.session.userId, state.session.accessToken) {
                settings?.let { preferences ->
                    SignedInNavigation(state.session, preferences, settingsError, settingsViewModel, sessionViewModel)
                } ?: LoadingScreen()
            }
        }
    }
}

@Composable
private fun SignedInNavigation(session: Session, settings: AppSettings, settingsError: String?,
    settingsViewModel: SettingsViewModel, sessionViewModel: SessionViewModel) {
    val navController = rememberNavController()
    DisposableEffect(navController) {
        onDispose {
            listOf("home", "browse/search", "browse/favorites").forEach { navController.clearBackStack(it) }
            navController.popBackStack(navController.graph.id, true)
        }
    }
    val openLibrary: (MediaItem) -> Unit = { item ->
        navController.navigate("library/${item.id}/${Uri.encode(item.name)}")
    }
    val openItem: (MediaItem) -> Unit = { item ->
        navController.navigate("detail/${item.id}")
    }

    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route
    val activeTab = when (route) {
        "home" -> "home"
        "browse/search", "browse/favorites" -> route
        else -> null
    }
    val openSettings = { navController.navigate("settings") { launchSingleTop = true } }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        if (activeTab != null) NavigationBar {
            listOf(Triple("home", "首页", Icons.Default.Home), Triple("browse/search", "搜索", Icons.Default.Search),
                Triple("browse/favorites", "收藏", Icons.Default.Favorite)).forEach { (target, label, icon) ->
                NavigationBarItem(selected = activeTab == target, onClick = {
                    if (activeTab != target) navController.navigate(target) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }, icon = { Icon(icon, label) }, label = { Text(label) })
            }
        }
    }) { navigationPadding ->
    NavHost(navController = navController, startDestination = "home",
        modifier = Modifier.padding(navigationPadding).consumeWindowInsets(navigationPadding)) {
        composable("home") {
            HomeScreen(
                session = session,
                onOpenLibrary = openLibrary,
                onOpenItem = openItem,
                onSettings = openSettings,
                onProfiles = { navController.navigate("profiles") { launchSingleTop = true } },
                onOffline = { navController.navigate("offline") { launchSingleTop = true } },
            )
        }
        listOf("search", "favorites").forEach { mode ->
            composable("browse/$mode", arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = mode })) {
                BrowseScreen(session.accessToken, onOpenItem = openItem, onSettings = openSettings)
            }
        }
        composable("settings") {
            SettingsScreen(settings, settingsError, settingsViewModel::setOrientation,
                onBack = navController::navigateUp, onLogout = sessionViewModel::logout,
                onProfiles = { navController.navigate("profiles") }, onUpdate = { navController.navigate("updates") },
                onDanmaku = { navController.navigate("danmaku-settings") }, onOffline = { navController.navigate("offline") })
        }
        composable("offline") { OfflineScreen(navController::navigateUp, { navController.navigate("offline-player/$it") }) }
        composable("offline-player/{offlineId}", arguments = listOf(navArgument("offlineId") { type = NavType.StringType })) {
            PlayerScreen(onBack = navController::navigateUp, entryOrientation = settings.playbackOrientation)
        }
        composable("danmaku-settings") { DanmakuSettingsScreen(onBack = navController::navigateUp) }
        composable("profiles") {
            val profiles by sessionViewModel.profiles.collectAsStateWithLifecycle()
            val error by sessionViewModel.profileError.collectAsStateWithLifecycle()
            val busy by sessionViewModel.profileBusy.collectAsStateWithLifecycle()
            ProfileScreen(profiles, error, busy, { id ->
                if (id == profiles.activeId) navController.navigateUp() else sessionViewModel.selectProfile(id)
            }, sessionViewModel::removeProfile, { navController.navigate("add-profile") }, navController::navigateUp)
        }
        composable("add-profile") { LoginRoute(onBack = navController::navigateUp, defaultServerUrl = session.serverUrl) }
        composable("updates") {
            val updateViewModel: UpdateViewModel = hiltViewModel()
            val update by updateViewModel.state.collectAsStateWithLifecycle()
            UpdateScreen(update, updateViewModel::check, updateViewModel::download, updateViewModel::cancel,
                updateViewModel::installationError, navController::navigateUp)
        }
        composable(
            route = "library/{parentId}/{title}",
            arguments = listOf(
                navArgument("parentId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) {
            LibraryScreen(
                accessToken = session.accessToken,
                onBack = navController::navigateUp,
                onOpenLibrary = openLibrary,
                onOpenItem = openItem,
            )
        }
        composable(
            route = "detail/{itemId}",
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) {
            DetailScreen(
                accessToken = session.accessToken,
                onBack = navController::navigateUp,
                onOpenEpisode = { navController.navigate("player/$it") },
                onPlay = { itemId, selection ->
                    navController.navigate("player/$itemId?audio=${selection.audioIndex ?: -2}" +
                        "&subtitle=${selection.subtitleIndex ?: -2}&sourceId=${Uri.encode(selection.mediaSourceId.orEmpty())}")
                },
            )
        }
        composable(
            route = "player/{itemId}?audio={audio}&subtitle={subtitle}&sourceId={sourceId}",
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("audio") { type = NavType.IntType; defaultValue = -2 },
                navArgument("subtitle") { type = NavType.IntType; defaultValue = -2 },
                navArgument("sourceId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            PlayerScreen(onBack = navController::navigateUp, entryOrientation = settings.playbackOrientation, onSelectEpisode = { episodeId ->
                // Remove the old playback entry so its ViewModel and native player are released.
                navController.navigate("player/$episodeId") { popUpTo(it.destination.id) { inclusive = true } }
            })
        }
    }
    }
}

@Composable
private fun LoginRoute(viewModel: LoginViewModel = hiltViewModel(), onBack: (() -> Unit)? = null, defaultServerUrl: String = "") {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { if (state.serverUrl.isBlank() && defaultServerUrl.isNotBlank()) viewModel.setServerUrl(defaultServerUrl) }
    LaunchedEffect(state.completed) { if (state.completed) onBack?.invoke() }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
    LoginScreen(
        state = state,
        onServerUrlChange = viewModel::setServerUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onLogin = viewModel::login,
    )
    onBack?.let { IconButton(onClick = it, modifier = Modifier.align(Alignment.TopStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "取消添加 profile") } }
    }
}

@Composable
private fun SignedOutProfiles(viewModel: SessionViewModel, orientation: com.embydog.app.core.settings.PlaybackOrientation) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val error by viewModel.profileError.collectAsStateWithLifecycle()
    val busy by viewModel.profileBusy.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    if (offline) SignedOutOffline(orientation) { offline = false }
    else if (showing) ProfileScreen(profiles, error, busy, viewModel::selectProfile, viewModel::removeProfile,
        { showing = false }, { showing = false })
    else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LoginRoute()
            if (profiles.profiles.isNotEmpty()) TextButton(onClick = { showing = true }, modifier = Modifier.align(Alignment.TopEnd)) { Text("已保存的 profiles") }
        }
        TextButton(onClick = { offline = true }, modifier = Modifier.fillMaxWidth()) { Text("查看离线缓存（无需连接服务器）") }
    }
}

@Composable
private fun SignedOutOffline(orientation: com.embydog.app.core.settings.PlaybackOrientation, onBack: () -> Unit) {
    val controller = rememberNavController()
    val current by controller.currentBackStackEntryAsState()
    BackHandler(enabled = current?.destination?.route == "offline", onBack = onBack)
    NavHost(controller, "offline") {
        composable("offline") { OfflineScreen(onBack, { controller.navigate("offline-player/$it") }) }
        composable("offline-player/{offlineId}", arguments = listOf(navArgument("offlineId") { type = NavType.StringType })) {
            PlayerScreen(onBack = controller::navigateUp, entryOrientation = orientation)
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
