package com.embydog.app.core.model

import java.util.Locale

data class SubtitleMetadata(
    val index: Int = -1,
    val language: String? = null,
    val title: String? = null,
    val displayTitle: String? = null,
    val fileName: String? = null,
    val codec: String = "",
    val external: Boolean = false,
    val default: Boolean = false,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
)

data class SubtitleDescription(val label: String, val details: String, val chinesePriority: Int?)

/** Metadata hints only: never pretend to detect the script by examining subtitle content. */
fun describeSubtitle(metadata: SubtitleMetadata): SubtitleDescription {
    val tag = metadata.language.orEmpty().lowercase(Locale.ROOT).replace('_', '-')
    val evidence = listOfNotNull(metadata.title, metadata.displayTitle, metadata.fileName)
        .joinToString(" ").lowercase(Locale.ROOT)
    fun hint(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(evidence)
    val simplified = tag.contains("hans") || tag in setOf("zh-cn", "zh-sg", "chs", "zh-chs") ||
        hint("简体|簡體|简中|簡中|简英|簡英|简日|簡日|简繁|簡繁|simplified|(?<![a-z])chs(?:eng|en|jpn|jp)?(?![a-z])|(?<![a-z])hans(?![a-z])|gb2312|gb18030")
    val traditional = tag.contains("hant") || tag in setOf("zh-tw", "zh-hk", "zh-mo", "cht", "zh-cht") ||
        hint("繁体|繁體|繁中|繁英|繁日|简繁|簡繁|traditional|(?<![a-z])cht(?:eng|en|jpn|jp)?(?![a-z])|(?<![a-z])hant(?![a-z])|big5")
    val chinese = simplified || traditional || tag.substringBefore('-') in setOf("zh", "zho", "chi", "cmn", "yue") ||
        hint("中文|汉语|漢語|国语|國語|粤语|粵語|(?<![a-z])(?:chinese|zho|chi)(?![a-z])")
    val chineseEnglish = hint("中英|英中|简英|簡英|繁英|(?<![a-z])ch[st][._+& /-]?(?:eng|en)(?![a-z])") ||
        (hint("中文|chinese|(?<![a-z])ch[st](?![a-z])") && hint("英文|英语|英語|(?<![a-z])(?:english|eng)(?![a-z])"))
    val chineseJapanese = hint("中日|日中|简日|簡日|繁日|(?<![a-z])ch[st][._+& /-]?(?:jpn|jp)(?![a-z])")
    val bilingual = hint("双语|雙語|(?<![a-z])bilingual(?![a-z])")
    val language = when {
        chinese -> when {
            simplified && traditional -> "简繁中文"
            simplified -> "简体中文"
            traditional -> "繁体中文"
            tag.startsWith("yue") || hint("粤语|粵語") -> "粤语字幕（字形未标明）"
            else -> "中文（简繁未标明）"
        }
        tag.substringBefore('-') in setOf("en", "eng") -> "英文"
        tag.substringBefore('-') in setOf("ja", "jpn") -> "日文"
        tag.substringBefore('-') in setOf("ko", "kor") -> "韩文"
        tag.substringBefore('-') in setOf("fr", "fra", "fre") -> "法文"
        tag.substringBefore('-') in setOf("de", "deu", "ger") -> "德文"
        tag.isBlank() || tag == "und" -> "语言未标明"
        else -> metadata.language.orEmpty()
    }
    val label = language + when {
        chineseEnglish -> " / 英文 · 双语"
        chineseJapanese -> " / 日文 · 双语"
        bilingual -> " · 双语（组合未标明）"
        else -> ""
    }
    val codec = when (metadata.codec.lowercase(Locale.ROOT)) {
        "subrip", "srt" -> "SRT"
        "webvtt", "vtt" -> "VTT"
        "hdmv_pgs_subtitle", "pgssub", "pgs" -> "PGS 图形字幕"
        "dvd_subtitle", "dvdsub" -> "VobSub 图形字幕"
        else -> metadata.codec.uppercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "格式未标明"
    }
    val details = buildList {
        listOfNotNull(metadata.title, metadata.displayTitle).map(String::trim)
            .filter { it.isNotBlank() && it != label }.distinct().forEach(::add)
        metadata.fileName?.takeIf { it.isNotBlank() && it !in this }?.let(::add)
        add(codec)
        add(if (metadata.external) "外挂" else "内嵌")
        if (metadata.default) add("默认")
        if (metadata.forced || hint("强制|強制|(?<![a-z])forced(?![a-z])")) add("强制")
        if (metadata.hearingImpaired || hint("听障|聽障|(?<![a-z])(?:sdh|hi)(?![a-z])")) add("听障 SDH")
        if (hint("(?<![a-z])cc(?![a-z])|closed captions")) add("隐藏式字幕 CC")
        if (metadata.index >= 0) add("轨道 #${metadata.index}")
    }.joinToString(" · ")
    return SubtitleDescription(label, details, if (!chinese) null else when {
        simplified && !traditional -> 0
        !traditional -> 1
        else -> 2
    })
}
