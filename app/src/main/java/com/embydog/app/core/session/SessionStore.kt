package com.embydog.app.core.session

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import com.embydog.app.core.model.Session
import com.embydog.app.core.security.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.sessionDataStore by preferencesDataStore(name = "session")

@Singleton
class SessionStore internal constructor(private val dataStore: DataStore<Preferences>, private val tokenCipher: TokenCipher) {
    @Inject constructor(@ApplicationContext context: Context, tokenCipher: TokenCipher) : this(context.sessionDataStore, tokenCipher)
    private val json = Json { ignoreUnknownKeys = true }
    private val profilesKey = stringPreferencesKey("profiles_v1")
    private val legacyKeys = listOf("server_url", "server_name", "server_id", "user_id", "user_name", "access_token_encrypted")
        .associateWith(::stringPreferencesKey)

    val state: Flow<ProfileState> = dataStore.data.map { preferences ->
        val stored = read(preferences)
        val active = stored.profiles.firstOrNull { it.id == stored.activeId }
        ProfileState(stored.profiles.map(StoredProfile::summary), stored.activeId,
            active?.let { runCatching { it.session(tokenCipher.decrypt(it.encryptedToken)) }.getOrNull() })
    }
    val session: Flow<Session?> = state.map { it.session }

    suspend fun sessionForProfile(id: String): Session? = read(dataStore.data.first()).profiles
        .firstOrNull { it.id == id }?.let { it.session(tokenCipher.decrypt(it.encryptedToken)) }

    suspend fun save(session: Session) {
        val profile = StoredProfile(profileId(session.serverUrl, session.userId), session.serverUrl, session.serverName,
            session.serverId, session.userId, session.userName, tokenCipher.encrypt(session.accessToken), System.currentTimeMillis())
        update { it.upsert(profile) }
    }

    suspend fun select(id: String) {
        update { stored ->
            // Do not activate a profile whose encryption key/token can no longer be read.
            tokenCipher.decrypt(requireNotNull(stored.profiles.firstOrNull { it.id == id }).encryptedToken)
            stored.select(id, System.currentTimeMillis())
        }
    }
    suspend fun remove(id: String) = update { it.remove(id) }
    suspend fun clear() = update { stored -> stored.activeId?.let(stored::remove) ?: stored }

    private suspend fun update(transform: (StoredProfiles) -> StoredProfiles) {
        dataStore.edit { preferences ->
            preferences[profilesKey] = json.encodeToString(transform(read(preferences)))
            legacyKeys.values.forEach { preferences.remove(it) }
        }
    }

    private fun read(preferences: Preferences): StoredProfiles {
        preferences[profilesKey]?.let { return json.decodeFromString(it) }
        fun legacy(name: String) = preferences[legacyKeys.getValue(name)]
        val token = legacy("access_token_encrypted") ?: return StoredProfiles()
        val url = legacy("server_url") ?: return StoredProfiles()
        val user = legacy("user_id") ?: return StoredProfiles()
        val profile = StoredProfile(profileId(url, user), url, legacy("server_name").orEmpty(),
            legacy("server_id").orEmpty(), user, legacy("user_name").orEmpty(), token)
        return StoredProfiles(listOf(profile), profile.id)
    }
}
