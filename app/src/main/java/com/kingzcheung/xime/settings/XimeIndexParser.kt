package com.kingzcheung.xime.settings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

/**
 * 纯函数层：YAML 文本 → 模型；repo 相对路径解析；版本选择；appVersion 兼容性判定。
 * 不碰网络/Android，便于 100% 单测。
 */
object XimeIndexParser {

    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun parseIndex(text: String): MarketIndex =
        yaml.decodeFromString(MarketIndex.serializer(), text)

    fun parseSubIndex(text: String): SchemesSubIndex =
        yaml.decodeFromString(SchemesSubIndex.serializer(), text)

    fun parseScheme(text: String): MarketScheme =
        yaml.decodeFromString(MarketScheme.serializer(), text)

    /** 以 repo 相对路径为中心解析引用（去 ./、相对当前文件目录、折叠 ..、绝对 / 视为根）。 */
    fun resolveRepoPath(currentPath: String, ref: String): String {
        val r = ref.trim()
        val joined = when {
            r.startsWith("/") -> r.removePrefix("/")
            else -> {
                val base = currentPath.substringBeforeLast('/', "")
                if (base.isEmpty()) r else "$base/$r"
            }
        }
        val segs = ArrayDeque<String>()
        for (seg in joined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (segs.isNotEmpty()) segs.removeLast()
                else -> segs.addLast(seg)
            }
        }
        return segs.joinToString("/")
    }

    /** appVersion 兼容性：空→true；>=X.Y.Z 取数值核心比较；无法解析→fail-open true。 */
    fun isCompatible(appVersion: String, constraint: String): Boolean {
        val c = constraint.trim()
        if (c.isEmpty()) return true
        if (!c.startsWith(">=")) return true // 不支持的操作符 → fail-open
        val min = numericCore(c.removePrefix(">=").trim())
        val app = numericCore(appVersion)
        for (i in 0..2) {
            if (app[i] != min[i]) return app[i] > min[i]
        }
        return true
    }

    /** 供"需 App ≥ x.y.z"文案。 */
    fun minAppVersionLabel(constraint: String): String {
        val c = constraint.trim()
        return when {
            c.startsWith(">=") -> c.removePrefix(">=").trim()
            c.startsWith(">") -> c.removePrefix(">").trim()
            else -> c
        }
    }

    fun toItem(scheme: MarketScheme, appVersion: String): MarketSchemeItem =
        MarketSchemeItem(
            scheme = scheme,
            compatible = isCompatible(appVersion, scheme.appVersion),
            minAppVersion = minAppVersionLabel(scheme.appVersion),
        )

    /** 取版本号的数值核心 major.minor.patch（忽略 -beta/+build 后缀，缺位补 0）。 */
    private fun numericCore(v: String): List<Int> {
        val core = v.trim().takeWhile { it != '-' && it != '+' }
        val parts = core.split('.').map { it.toIntOrNull() ?: 0 }
        return (parts + listOf(0, 0, 0)).take(3)
    }
}
