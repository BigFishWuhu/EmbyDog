package com.embydog.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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

@Composable
fun EmbyDogApp(sessionViewModel: SessionViewModel = hiltViewModel(), settingsViewModel: SettingsViewModel = hiltViewModel()) {
    EmbyDogTheme {
        val rootState by sessionViewModel.state.collectAsStateWithLifecycle()
        val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
        val settingsError by settingsViewModel.error.collectAsStateWithLifecycle()
        when (val state = rootState) {
            RootState.Loading -> LoadingScreen()
            RootState.SignedOut -> LoginRoute()
            is RootState.SignedIn -> key(state.session.serverId, state.session.userId) {
                settings?.let { preferences ->
                    SignedInNavigation(state.session, preferences, settingsError, settingsViewModel, sessionViewModel::logout)
                } ?: LoadingScreen()
            }
        }
    }
}

@Composable
private fun SignedInNavigation(session: Session, settings: AppSettings, settingsError: String?,
    settingsViewModel: SettingsViewModel, onLogout: () -> Unit) {
    val navController = rememberNavController()
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
            )
        }
        listOf("search", "favorites").forEach { mode ->
            composable("browse/$mode", arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = mode })) {
                BrowseScreen(session.accessToken, onOpenItem = openItem, onSettings = openSettings)
            }
        }
        composable("settings") {
            SettingsScreen(settings, settingsError, settingsViewModel::setOrientation,
                onBack = navController::navigateUp, onLogout = onLogout)
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
                onOpenEpisode = { navController.navigate("detail/$it") },
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
private fun LoginRoute(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onServerUrlChange = viewModel::setServerUrl,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onLogin = viewModel::login,
    )
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
