package com.embydog.app.core.danmaku

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable

enum class DanmakuMode { SCROLL, BOTTOM, TOP }

@Serializable
data class DanmakuComment(val timeMs: Long, val mode: DanmakuMode, val color: Int, val text: String)

@Serializable
data class DanmakuEpisode(val id: Long, val animeTitle: String, val episodeTitle: String) {
    val label: String get() = "$animeTitle · $episodeTitle"
}

@Serializable
data class DanmakuDisplay(
    val enabled: Boolean = true,
    val opacity: Float = 0.75f,
    val textSizeSp: Float = 20f,
    val speed: Float = 1f,
    val area: Float = 0.5f,
    val density: Int = 2,
    val showScroll: Boolean = true,
    val showTop: Boolean = true,
    val showBottom: Boolean = true,
    val showColor: Boolean = true,
    val protectSubtitles: Boolean = true,
    // Positive values delay comments; negative values make them appear earlier.
    val offsetMs: Long = 0,
) {
    fun normalized() = copy(
        opacity = opacity.finiteOr(0.75f).coerceIn(0.1f, 1f),
        textSizeSp = textSizeSp.finiteOr(20f).coerceIn(12f, 32f),
        speed = speed.finiteOr(1f).coerceIn(0.5f, 2f),
        area = area.finiteOr(0.5f).coerceIn(0.25f, 1f),
        density = density.coerceIn(1, 3), offsetMs = offsetMs.coerceIn(-300_000, 300_000),
    )
}

private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback

data class DanmakuCredentials(val appId: String = "", val secret: String = "") {
    val configured: Boolean get() = appId.isNotBlank() && secret.isNotBlank()
    override fun toString() = "DanmakuCredentials(configured=$configured)"
}

fun dandanSignature(appId: String, timestamp: Long, path: String, secret: String): String {
    require(path.startsWith("/api/v2/") && !path.contains('?'))
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
        .digest("$appId$timestamp$path$secret".toByteArray(Charsets.UTF_8)))
}

internal fun danmakuHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun danmakuScope(server: String, userId: String?, itemId: String): String =
    danmakuHash("${server.trimEnd('/')}\u0000${userId.orEmpty()}\u0000$itemId")

@Serializable
internal data class DandanSearchResponse(val success: Boolean = true, val errorMessage: String? = null,
    val hasMore: Boolean = false, val animes: List<DandanAnime>? = null)
@Serializable
internal data class DandanAnime(val animeTitle: String? = null, val episodes: List<DandanEpisodeDto>? = null)
@Serializable
internal data class DandanEpisodeDto(val episodeId: Long, val episodeTitle: String? = null)
@Serializable
internal data class DandanCommentsResponse(val comments: List<DandanCommentDto>? = null)
@Serializable
internal data class DandanCommentDto(val p: String? = null, val m: String? = null) {
    fun parsed(): DanmakuComment? {
        val parts = p?.split(',', limit = 4) ?: return null
        if (parts.size < 3) return null
        val seconds = parts[0].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 7 * 24 * 3600 } ?: return null
        val mode = when (parts[1].toIntOrNull()) { 1 -> DanmakuMode.SCROLL; 4 -> DanmakuMode.BOTTOM; 5 -> DanmakuMode.TOP; else -> return null }
        val color = parts[2].toIntOrNull()?.takeIf { it in 0..0xFFFFFF } ?: return null
        val text = m?.replace(Regex("[\\p{Cntrl}\\r\\n]+"), " ")?.trim()?.take(160)?.takeIf { it.isNotBlank() } ?: return null
        return DanmakuComment((seconds * 1000).toLong(), mode, color, text)
    }
}
