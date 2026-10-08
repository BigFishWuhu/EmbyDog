package com.embydog.app.core.model

data class Session(
    val serverUrl: String,
    val serverName: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
)

