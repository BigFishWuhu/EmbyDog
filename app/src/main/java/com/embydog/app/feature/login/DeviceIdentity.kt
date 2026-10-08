package com.embydog.app.feature.login

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext context: Context,
) {
    val id: String = context
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .let { preferences ->
            preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
                preferences.edit { putString(KEY_DEVICE_ID, it) }
            }
        }

    private companion object {
        const val PREFERENCES_NAME = "device_identity"
        const val KEY_DEVICE_ID = "installation_id"
    }
}
