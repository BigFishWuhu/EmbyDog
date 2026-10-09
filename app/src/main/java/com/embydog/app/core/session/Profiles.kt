package com.embydog.app.core.session

import com.embydog.app.core.model.Session
import com.embydog.app.core.network.EmbyUrl
import java.security.MessageDigest
import kotlinx.serialization.Serializable

fun profileId(serverUrl: String, userId: String): String = privateCacheKey(EmbyUrl.normalize(serverUrl).toString(), userId)

fun privateCacheKey(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }

data class ProfileSummary(val id: String, val serverUrl: String, val serverName: String, val userName: String)
data class ProfileState(val profiles: List<ProfileSummary> = emptyList(), val activeId: String? = null, val session: Session? = null)

@Serializable
internal data class StoredProfile(val id: String, val serverUrl: String, val serverName: String,
    val serverId: String, val userId: String, val userName: String, val encryptedToken: String, val lastUsedAt: Long = 0) {
    fun summary() = ProfileSummary(id, serverUrl, serverName, userName)
    fun session(token: String) = Session(serverUrl, serverName, serverId, userId, userName, token)
}

@Serializable
internal data class StoredProfiles(val profiles: List<StoredProfile> = emptyList(), val activeId: String? = null) {
    fun upsert(profile: StoredProfile) = copy(profiles = profiles.filterNot { it.id == profile.id } + profile, activeId = profile.id)
    fun select(id: String, now: Long): StoredProfiles {
        require(profiles.any { it.id == id }) { "Profile no longer exists" }
        return copy(profiles = profiles.map { if (it.id == id) it.copy(lastUsedAt = now) else it }, activeId = id)
    }
    fun remove(id: String): StoredProfiles {
        val remaining = profiles.filterNot { it.id == id }
        return copy(profiles = remaining, activeId = if (activeId == id) remaining.maxByOrNull { it.lastUsedAt }?.id else activeId)
    }
}
