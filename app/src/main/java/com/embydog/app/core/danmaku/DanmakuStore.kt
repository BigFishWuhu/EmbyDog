package com.embydog.app.core.danmaku

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.embydog.app.core.security.TokenCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.danmakuDataStore by preferencesDataStore(name = "danmaku")

@Singleton
class DanmakuStore internal constructor(private val dataStore: DataStore<Preferences>, private val cipher: TokenCipher, private val json: Json) {
    @Inject constructor(@ApplicationContext context: Context, cipher: TokenCipher, json: Json) : this(context.danmakuDataStore, cipher, json)
    private val appIdKey = stringPreferencesKey("app_id")
    private val secretKey = stringPreferencesKey("encrypted_app_secret")
    private val displayKey = stringPreferencesKey("display")
    private val bindingsKey = stringPreferencesKey("bindings")
    private val data = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val credentials = data.map { prefs -> DanmakuCredentials(prefs[appIdKey].orEmpty(),
        prefs[secretKey]?.let { runCatching { cipher.decrypt(it) }.getOrNull() }.orEmpty()) }.flowOn(Dispatchers.IO)
    val display = data.map { prefs -> runCatching { json.decodeFromString<DanmakuDisplay>(prefs[displayKey].orEmpty()).normalized() }.getOrDefault(DanmakuDisplay()) }

    suspend fun saveCredentials(appId: String, secret: String) {
        require(appId.trim().length <= 200 && secret.length <= 1024) { "凭据长度不正确" }
        require(appId.trim().all { it.code in 33..126 }) { "AppId 不能包含空格、换行或非英文字符" }
        require((appId.isBlank() && secret.isBlank()) || (appId.isNotBlank() && secret.isNotBlank())) { "请填写 AppId 和 AppSecret" }
        val encrypted = if (secret.isNotBlank()) withContext(Dispatchers.IO) { cipher.encrypt(secret.trim()) } else null
        dataStore.edit {
            if (encrypted == null) { it.remove(appIdKey); it.remove(secretKey) }
            else { it[appIdKey] = appId.trim(); it[secretKey] = encrypted }
        }
    }

    suspend fun saveDisplay(display: DanmakuDisplay) {
        dataStore.edit { it[displayKey] = json.encodeToString(display.normalized()) }
    }

    suspend fun binding(scope: String): DanmakuEpisode? = try {
        json.decodeFromString<Map<String, DanmakuEpisode>>(data.first()[bindingsKey].orEmpty())[scope]
    } catch (error: CancellationException) { throw error } catch (_: Exception) { null }

    suspend fun bind(scope: String, episode: DanmakuEpisode?) {
        dataStore.edit { prefs ->
            val previous = runCatching { json.decodeFromString<Map<String, DanmakuEpisode>>(prefs[bindingsKey].orEmpty()) }.getOrDefault(emptyMap())
            val updated = previous.toMutableMap().apply { remove(scope); if (episode != null) put(scope, episode) }
            prefs[bindingsKey] = json.encodeToString(updated.entries.toList().takeLast(200).associate { it.toPair() })
        }
    }
}
