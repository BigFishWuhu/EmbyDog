package com.embydog.app.core.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.core.model.Session
import com.embydog.app.core.security.TokenCipher
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ProfileStoreDeviceTest {
    private fun session(user: String, server: String = "https://profiles.example/emby") = Session(server, "Fixture", "server", user, user, "fixture-token-$user")

    @Test fun legacyLoginMigratesWithoutReauthenticationAndKeepsTokensEncrypted(): Unit = runBlocking {
        fixture { data, store, cipher ->
            val old = session("legacy")
            data.edit { prefs ->
                mapOf("server_url" to old.serverUrl, "server_name" to old.serverName, "server_id" to old.serverId,
                    "user_id" to old.userId, "user_name" to old.userName, "access_token_encrypted" to cipher.encrypt(old.accessToken))
                    .forEach { (name, value) -> prefs[stringPreferencesKey(name)] = value }
            }
            assertEquals(old, store.session.first())
            val first = store.state.first()
            assertEquals(1, first.profiles.size)
            store.save(session("second"))
            store.select(first.activeId!!)
            assertEquals(old, store.session.first())
            val preferences = data.data.first()
            assertNull(preferences[stringPreferencesKey("access_token_encrypted")])
            val serialized = preferences[stringPreferencesKey("profiles_v1")]!!
            assertFalse(serialized.contains("fixture-token-"))
            assertEquals(2, store.state.first().profiles.size)
            assertEquals(old, SessionStore(data, cipher).session.first())
        }
    }

    @Test fun switchingRestoresLastProfileAndLogoutOnlyRemovesCurrent(): Unit = runBlocking {
        fixture { data, store, cipher ->
            val a = session("a"); val b = session("b"); val other = session("a", "https://other.example/emby")
            store.save(a); store.save(b); store.save(other)
            assertEquals(3, store.state.first().profiles.size)
            store.select(profileId(b.serverUrl, b.userId))
            assertEquals(b, SessionStore(data, cipher).session.first())
            store.clear()
            assertEquals(2, store.state.first().profiles.size)
            assertFalse(store.state.first().profiles.any { it.id == profileId(b.serverUrl, b.userId) })
            assertNotNull(store.session.first())
            store.state.first().profiles.forEach { store.remove(it.id) }
            assertNull(store.session.first())
        }
    }

    @Test fun reloginReplacesTokenWithoutDuplicatingProfile(): Unit = runBlocking {
        fixture { _, store, _ ->
            val a = session("a")
            store.save(a); store.save(a.copy(accessToken = "renewed-fixture"))
            assertEquals(1, store.state.first().profiles.size)
            assertEquals("renewed-fixture", store.session.first()!!.accessToken)
            try { store.select("missing"); fail("Missing profile should fail") } catch (_: IllegalArgumentException) { }
            assertEquals(a.userId, store.session.first()!!.userId)
        }
    }

    private suspend fun fixture(block: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, SessionStore, TokenCipher) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("profile-fixture-", ".preferences_pb", context.cacheDir).apply { delete() }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val cipher = TokenCipher()
        try { block(data, SessionStore(data, cipher), cipher) }
        finally { job.cancelAndJoin(); file.delete() }
    }
}
