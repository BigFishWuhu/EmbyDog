package com.embydog.app.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class PlaybackOrientation(val label: String, val description: String) {
    LANDSCAPE("进入时横屏", "进入播放页面立即切到横屏，不随重力切回竖屏"),
    PORTRAIT("进入时竖屏", "进入播放页面立即切到竖屏"),
    SENSOR("跟随重力", "播放期间根据设备方向切换横屏或竖屏"),
    SYSTEM("保持系统方向", "进入播放页面时不主动改变屏幕方向");

    companion object {
        fun fromStored(value: String?): PlaybackOrientation = entries.firstOrNull { it.name == value } ?: LANDSCAPE
    }
}

data class AppSettings(val playbackOrientation: PlaybackOrientation = PlaybackOrientation.LANDSCAPE)

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

@Singleton
class AppSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val orientationKey = stringPreferencesKey("playback_entry_orientation")
    val settings = context.appSettingsDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { AppSettings(PlaybackOrientation.fromStored(it[orientationKey])) }

    suspend fun setPlaybackOrientation(orientation: PlaybackOrientation) {
        context.appSettingsDataStore.edit { it[orientationKey] = orientation.name }
    }
}
