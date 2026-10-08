package com.embydog.app.feature.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings

internal enum class PlayerRotation { AUTOMATIC, LANDSCAPE, PORTRAIT, SYSTEM }

internal fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}

internal class PlayerEnvironment(private val activity: Activity) {
    private val originalOrientation = activity.requestedOrientation
    private val originalBrightness = activity.window.attributes.screenBrightness
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun rotate(mode: PlayerRotation) {
        activity.requestedOrientation = when (mode) {
            PlayerRotation.AUTOMATIC -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            PlayerRotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerRotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            PlayerRotation.SYSTEM -> originalOrientation
        }
    }

    fun currentRotation(): PlayerRotation =
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            PlayerRotation.LANDSCAPE
        } else PlayerRotation.PORTRAIT

    fun manualRotation(): PlayerRotation = if (currentRotation() == PlayerRotation.LANDSCAPE) PlayerRotation.PORTRAIT else PlayerRotation.LANDSCAPE

    fun brightness(): Float = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
        ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)

    fun setBrightness(value: Float) {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = value.coerceIn(0.02f, 1f)
        }
    }

    fun volume(): Float = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
        audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun setVolume(value: Float): Float {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volume = kotlin.math.round(value.coerceIn(0f, 1f) * maximum).toInt()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        return volume()
    }

    fun restore() {
        activity.requestedOrientation = originalOrientation
        activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
    }
}
