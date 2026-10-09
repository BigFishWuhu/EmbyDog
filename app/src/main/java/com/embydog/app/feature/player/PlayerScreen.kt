package com.embydog.app.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embydog.app.feature.media.ErrorContent
import com.embydog.app.feature.media.EpisodePickerDialog
import com.embydog.app.core.settings.PlaybackOrientation
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(onBack: () -> Unit, onSelectEpisode: (String) -> Unit,
    entryOrientation: PlaybackOrientation = PlaybackOrientation.LANDSCAPE, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val exitPlayback = { viewModel.stopPlayback(); onBack() }
    BackHandler(onBack = exitPlayback)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.playerActivity()
    val environment = remember(activity) { activity?.let(::PlayerEnvironment) }
    var rotation by rememberSaveable { mutableStateOf(when (entryOrientation) {
        PlaybackOrientation.LANDSCAPE -> PlayerRotation.LANDSCAPE
        PlaybackOrientation.PORTRAIT -> PlayerRotation.PORTRAIT
        PlaybackOrientation.SENSOR -> PlayerRotation.AUTOMATIC
        PlaybackOrientation.SYSTEM -> PlayerRotation.SYSTEM
    }) }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsBusy by remember { mutableStateOf(false) }
    var episodesVisible by remember { mutableStateOf(false) }
    var gestureActive by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<GestureFeedback?>(null) }
    var interaction by remember { mutableStateOf(0) }
    DisposableEffect(environment) { onDispose { environment?.restore() } }
    LaunchedEffect(environment, rotation) { environment?.rotate(rotation) }
    LaunchedEffect(state.closeRequested) { if (state.closeRequested) onBack() }
    LaunchedEffect(gestureActive, gestureHint) {
        if (!gestureActive && gestureHint != null) {
            delay(1000)
            gestureHint = null
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForeground(false)
        }
    }
    LaunchedEffect(controlsVisible, interaction, state.playback.isPlaying, state.engine, controlsBusy, gestureActive, episodesVisible) {
        if (controlsVisible && state.playback.isPlaying && !controlsBusy && !gestureActive && !episodesVisible) {
            delay(5000)
            controlsVisible = false
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.errorMessage != null -> ErrorContent(
                    message = state.errorMessage.orEmpty(), onRetry = viewModel::retry,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                )
                state.engine != null -> {
                    val engine = requireNotNull(state.engine)
                    key(engine) {
                        AndroidView(
                            factory = { context -> engine.createVideoView(context) },
                            update = { it.keepScreenOn = state.playback.isPlaying || state.playback.isBuffering },
                            onRelease = { it.keepScreenOn = false },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(Modifier.fillMaxSize().pointerInput(engine, environment) {
                        var brightnessGesture = false
                        var axis = GestureAxis.UNDECIDED
                        var initialLevel = 0f
                        var distanceY = 0f
                        var distanceX = 0f
                        var originMs = 0L
                        var durationMs = 0L
                        detectDragGestures(
                            onDragStart = { position ->
                                axis = GestureAxis.UNDECIDED
                                brightnessGesture = position.x < size.width / 2
                                initialLevel = if (brightnessGesture) environment?.brightness() ?: 0.5f
                                    else environment?.volume() ?: 0f
                                distanceY = 0f
                                distanceX = 0f
                                originMs = viewModel.state.value.playback.positionMs
                                durationMs = viewModel.state.value.playback.durationMs
                                gestureActive = true
                                gestureHint = null
                                interaction++
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                distanceY += amount.y
                                distanceX += amount.x
                                if (axis == GestureAxis.UNDECIDED) axis = if (kotlin.math.abs(distanceX) > kotlin.math.abs(distanceY))
                                    GestureAxis.HORIZONTAL else GestureAxis.VERTICAL
                                if (axis == GestureAxis.HORIZONTAL) {
                                    if (durationMs > 0) viewModel.previewSeek(seekGesturePosition(originMs, distanceX, size.width, durationMs), originMs)
                                    return@detectDragGestures
                                }
                                val level = adjustedGestureLevel(initialLevel, distanceY, size.height,
                                    if (brightnessGesture) 0.02f else 0f)
                                if (brightnessGesture) {
                                    environment?.setBrightness(level)
                                    gestureHint = GestureFeedback(GestureFeedbackKind.BRIGHTNESS, level)
                                } else {
                                    val applied = runCatching { environment?.setVolume(level) ?: level }
                                    gestureHint = applied.fold(
                                        onSuccess = { GestureFeedback(GestureFeedbackKind.VOLUME, it) },
                                        onFailure = { GestureFeedback(GestureFeedbackKind.VOLUME, level, "系统暂不允许调整音量") })
                                }
                            },
                            onDragEnd = {
                                if (axis == GestureAxis.HORIZONTAL) viewModel.commitSeekPreview()
                                gestureActive = false; interaction++
                            },
                            onDragCancel = { viewModel.cancelSeekPreview(); gestureHint = null; gestureActive = false; interaction++ },
                        )
                    }.pointerInput(engine) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible; interaction++ },
                            onDoubleTap = { position ->
                                val offset = if (position.x < size.width / 2) -10_000L else 10_000L
                                viewModel.seekTo(viewModel.state.value.playback.positionMs + offset)
                                controlsVisible = true
                                interaction++
                            },
                        )
                    })
                    if (state.playback.isBuffering && !state.playback.isPlaying) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
            PlayerControls(
                state = state,
                visible = controlsVisible || state.isLoading || state.errorMessage != null,
                automaticRotation = rotation == PlayerRotation.AUTOMATIC,
                onRotate = { rotation = environment?.manualRotation() ?: PlayerRotation.LANDSCAPE },
                onAutomaticRotation = {
                    rotation = if (rotation == PlayerRotation.AUTOMATIC) {
                        environment?.currentRotation() ?: PlayerRotation.LANDSCAPE
                    } else PlayerRotation.AUTOMATIC
                },
                onEpisodes = { episodesVisible = true },
                onBusyChange = { controlsBusy = it },
                onBack = exitPlayback,
                onInteraction = { controlsVisible = true; interaction++ },
                onToggle = viewModel::togglePlayback,
                onSeek = viewModel::seekTo,
                onPreviewSeek = { viewModel.previewSeek(it) },
                onCancelPreview = viewModel::cancelSeekPreview,
                onEngine = viewModel::switchEngine,
                onSpeed = viewModel::setSpeed,
                onTrack = viewModel::selectTrack,
                onSleepTimer = viewModel::setSleepTimer,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
            )
            PlayerGestureFeedback(state.seekPreview, gestureHint, state.playback.durationMs, Modifier.align(Alignment.Center))
            val seriesId = state.source?.seriesId
            if (episodesVisible && seriesId != null) EpisodePickerDialog(seriesId, state.source?.seasonId,
                state.source?.accessToken.orEmpty(),
                state.source?.itemId, onDismiss = { episodesVisible = false },
                onSelect = { episodesVisible = false; viewModel.stopPlayback(); onSelectEpisode(it) })
        }
    }
}
