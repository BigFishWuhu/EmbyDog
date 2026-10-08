package com.embydog.app.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object EmbyUrl {
    fun normalize(value: String): HttpUrl {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "请输入服务器地址" }

        val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val parsed = candidate.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务器地址格式不正确")
        require(parsed.scheme == "http" || parsed.scheme == "https") {
            "服务器地址必须使用 HTTP 或 HTTPS"
        }

        val path = parsed.encodedPath.let { if (it.endsWith('/')) it else "$it/" }
        return parsed.newBuilder()
            .encodedPath(path)
            .query(null)
            .fragment(null)
            .build()
    }

    fun endpoint(baseUrl: HttpUrl, relativePath: String): String =
        requireNotNull(baseUrl.resolve(relativePath)) { "无法创建 Emby API 地址" }.toString()

    fun serverPath(baseUrl: HttpUrl, value: String): HttpUrl {
        val parsed = value.toHttpUrlOrNull()
        if (parsed != null) return parsed.withoutAccessToken()

        val normalized = value.substringBefore('?').trimStart('/')
        val basePath = baseUrl.encodedPath.trim('/')
        val relativePath = if (basePath.isNotEmpty() && normalized.startsWith("$basePath/")) {
            normalized.removePrefix("$basePath/")
        } else {
            normalized
        }
        val resolved = requireNotNull(baseUrl.resolve(relativePath)) { "无法解析服务器媒体地址" }
        val rawQuery = value.substringAfter('?', missingDelimiterValue = "")
        val builder = resolved.newBuilder()
        if (rawQuery.isNotEmpty()) builder.encodedQuery(rawQuery)
        return builder.build().withoutAccessToken()
    }

    private fun HttpUrl.withoutAccessToken(): HttpUrl = newBuilder()
        .removeAllQueryParameters("api_key")
        .removeAllQueryParameters("X-Emby-Token")
        .build()
}
