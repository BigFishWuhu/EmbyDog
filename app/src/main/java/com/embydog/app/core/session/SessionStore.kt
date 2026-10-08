package com.embydog.app.core.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.embydog.app.core.model.Session
import com.embydog.app.core.security.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore by preferencesDataStore(name = "session")

@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenCipher: TokenCipher,
) {
    val session: Flow<Session?> = context.sessionDataStore.data.map { preferences ->
        val encryptedToken = preferences[Keys.token] ?: return@map null
        runCatching {
            Session(
                serverUrl = requireNotNull(preferences[Keys.serverUrl]),
                serverName = requireNotNull(preferences[Keys.serverName]),
                serverId = requireNotNull(preferences[Keys.serverId]),
                userId = requireNotNull(preferences[Keys.userId]),
                userName = requireNotNull(preferences[Keys.userName]),
                accessToken = tokenCipher.decrypt(encryptedToken),
            )
        }.getOrNull()
    }

    suspend fun save(session: Session) {
        val encryptedToken = tokenCipher.encrypt(session.accessToken)
        context.sessionDataStore.edit { preferences ->
            preferences[Keys.serverUrl] = session.serverUrl
            preferences[Keys.serverName] = session.serverName
            preferences[Keys.serverId] = session.serverId
            preferences[Keys.userId] = session.userId
            preferences[Keys.userName] = session.userName
            preferences[Keys.token] = encryptedToken
        }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val serverName = stringPreferencesKey("server_name")
        val serverId = stringPreferencesKey("server_id")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val token = stringPreferencesKey("access_token_encrypted")
    }
}

