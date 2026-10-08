package com.embydog.app.feature.login

import com.embydog.app.BuildConfig
import com.embydog.app.core.model.Session
import com.embydog.app.core.network.AuthenticationRequestDto
import com.embydog.app.core.network.EmbyApi
import com.embydog.app.core.network.EmbyUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmbyRepository @Inject constructor(
    private val api: EmbyApi,
    private val deviceIdentity: DeviceIdentity,
) {
    suspend fun login(serverUrl: String, username: String, password: String): Session {
        val baseUrl = EmbyUrl.normalize(serverUrl)
        val systemInfo = api.getPublicSystemInfo(
            EmbyUrl.endpoint(baseUrl, "System/Info/Public"),
        )
        val authorization = buildString {
            append("MediaBrowser ")
            append("Client=\"EmbyDog\", ")
            append("Device=\"Android\", ")
            append("DeviceId=\"")
            append(deviceIdentity.id)
            append("\", Version=\"")
            append(BuildConfig.VERSION_NAME)
            append('"')
        }
        val response = api.authenticateByName(
            url = EmbyUrl.endpoint(baseUrl, "Users/AuthenticateByName"),
            authorization = authorization,
            request = AuthenticationRequestDto(username.trim(), password),
        )
        require(response.accessToken.isNotBlank()) { "服务器没有返回访问令牌" }

        return Session(
            serverUrl = baseUrl.toString().removeSuffix("/"),
            serverName = systemInfo.serverName,
            serverId = response.serverId.ifBlank { systemInfo.id },
            userId = response.user.id,
            userName = response.user.name,
            accessToken = response.accessToken,
        )
    }
}

