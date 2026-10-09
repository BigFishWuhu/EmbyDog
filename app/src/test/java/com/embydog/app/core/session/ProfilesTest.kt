package com.embydog.app.core.session

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProfilesTest {
    private fun profile(user: String = "a", server: String = "https://one/emby", used: Long = 1) =
        StoredProfile(profileId(server, user), server, "Server", "server", user, user, "encrypted-$user", used)

    @Test fun sameAccountUpdatesWithoutDuplicatesAndRestoresLastSelection() {
        val a = profile(); val b = profile("b")
        val data = StoredProfiles().upsert(a).upsert(b).upsert(a.copy(encryptedToken = "renewed"))
        assertEquals(2, data.profiles.size)
        assertEquals(a.id, data.activeId)
        val persisted = Json.decodeFromString<StoredProfiles>(Json.encodeToString(data.select(b.id, 50)))
        assertEquals(b.id, persisted.activeId)
        assertEquals(50, persisted.profiles.first { it.id == b.id }.lastUsedAt)
        assertEquals("renewed", persisted.profiles.first { it.id == a.id }.encryptedToken)
    }
    @Test fun differentServerOrAccountIsNotMerged() {
        val data = StoredProfiles().upsert(profile()).upsert(profile("b")).upsert(profile(server = "https://two/emby"))
        assertEquals(3, data.profiles.size)
        assertEquals(profileId("HTTPS://ONE:443/emby/", "a"), profileId("https://one/emby", "a"))
    }
    @Test fun removingActiveUsesMostRecentlyUsedRemainingAndDoesNotRemoveOthers() {
        val a = profile(used = 5); val b = profile("b", used = 10); val c = profile("c", used = 2)
        val data = StoredProfiles().upsert(a).upsert(b).upsert(c)
        val removed = data.remove(c.id)
        assertEquals(b.id, removed.activeId)
        assertEquals(listOf(a.id, b.id), removed.profiles.map { it.id })
        assertEquals(b.id, removed.remove(a.id).activeId)
        assertNull(removed.remove(a.id).remove(b.id).activeId)
    }
    @Test fun missingProfileSelectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { StoredProfiles().upsert(profile()).select("missing", 1) }
    }
    @Test fun cacheKeysIsolateServerAndCredentialsWithoutExposingTokens() {
        assertNotEquals(privateCacheKey("https://one/poster", "token-a"), privateCacheKey("https://two/poster", "token-a"))
        assertNotEquals(privateCacheKey("https://one/poster", "token-a"), privateCacheKey("https://one/poster", "token-b"))
        assertEquals(64, privateCacheKey("token-a").length)
        assertFalse(privateCacheKey("token-a").contains("token-a"))
    }
}
