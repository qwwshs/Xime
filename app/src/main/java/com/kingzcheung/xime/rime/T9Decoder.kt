package com.kingzcheung.xime.rime

import android.content.Context
import java.io.FileNotFoundException

/**
 * 九宫格拼音解码器
 *
 * 使用 DP + bi-gram 语言模型 将数字序列解码为拼音序列。
 * 支持模糊音（翘舌、前后鼻音等）和键盘邻居容错。
 */
class T9Decoder {

    val digitToLetters: Map<Char, List<Char>> = mapOf(
        '2' to listOf('a', 'b', 'c'), '3' to listOf('d', 'e', 'f'),
        '4' to listOf('g', 'h', 'i'), '5' to listOf('j', 'k', 'l'),
        '6' to listOf('m', 'n', 'o'), '7' to listOf('p', 'q', 'r', 's'),
        '8' to listOf('t', 'u', 'v'), '9' to listOf('w', 'x', 'y', 'z')
    )

    private val lm: PinyinBigramLM

    /** 当前启用的模糊音规则 */
    var enabledFuzzyRules: List<FuzzyPinyin.Rule> = FuzzyPinyin.ALL_RULES

    /** 是否启用键盘邻居容错 */
    var neighborEnabled = true

    /** decode() 结果缓存 — 避免同一次按键处理中重复调用 */
    private var decodeCache: Pair<String, List<Path>>? = null

    constructor() {
        val stream = this::class.java.classLoader
            ?.getResourceAsStream("pinyin_lm.bin")
            ?: throw RuntimeException(
                "Cannot find pinyin_lm.bin on classpath. " +
                        "Ensure src/main/assets is in test resources"
            )
        lm = PinyinBigramLM.loadFromStream(stream)
    }

    constructor(context: Context) {
        try {
            val stream = context.assets.open("pinyin_lm.bin")
            lm = PinyinBigramLM.loadFromStream(stream)
        } catch (e: FileNotFoundException) {
            throw RuntimeException("pinyin_lm.bin not found in assets/", e)
        }
    }

    data class Path(
        val pinyins: List<String>,
        val current: String,
        val score: Double,
        val digitLengths: List<Int> = emptyList()
    ) {
        val resolved: String get() = pinyins.joinToString("")
        val full: String get() = resolved + current
    }

    data class SyllableOption(
        val pinyin: String,
        val digitLength: Int
    )

    companion object {
        private const val BOS = "<BOS>"
        private const val MAX_PINYIN_LEN = 6

        /** K-Best: 每个 DP 状态保留的最优前驱数量 */
        private const val BACK_BEAM = 2

        /** DP 回溯中的一条前驱记录 */
        data class BackEntry(val prevPos: Int, val prevPinyin: String, val score: Double)

        /** 编码→拼音映射（含精确匹配 + 模糊音扩展） */
        private val codeToPinyins: Map<String, List<String>> by lazy {
            FuzzyPinyin.PINYIN_LIST.groupBy { pinyin ->
                pinyin.map { FuzzyPinyin.LETTER_TO_DIGIT[it] ?: it }.joinToString("")
            }
        }

        /** 编码→模糊拼音映射（预计算，避免运行时重复构建） */
        private val fuzzyCodeMap: Map<String, List<String>> by lazy {
            val result = mutableMapOf<String, MutableList<String>>()
            for (pinyin in FuzzyPinyin.PINYIN_LIST) {
                val variants = FuzzyPinyin.expandAll(listOf(pinyin), FuzzyPinyin.ALL_RULES)
                for (v in variants) {
                    if (v == pinyin) continue
                    val code = v.map { FuzzyPinyin.LETTER_TO_DIGIT[it] ?: it }.joinToString("")
                    result.getOrPut(code) { mutableListOf() }.add(pinyin)
                }
            }
            result.mapValues { it.value.distinct().sorted() }
        }

        /** 九宫格键盘邻居 */
        private val KEYBOARD_NEIGHBORS = mapOf(
            '2' to setOf('1','2','3','4','5','6'),
            '3' to setOf('2','3','5','6'),
            '4' to setOf('1','4','5','7','8'),
            '5' to setOf('1','2','3','4','5','6','7','8','9'),
            '6' to setOf('2','3','5','6','8','9'),
            '7' to setOf('4','5','7','8'),
            '8' to setOf('4','5','6','7','8','9'),
            '9' to setOf('5','6','8','9')
        )
    }

    /**
     * DP 解码：使用 Viterbi 算法 + bi-gram 语言模型，
     * 寻找最佳拼音切分路径。
     *
     * 支持精确匹配、模糊音匹配、键盘邻居容错、单字母回退。
     */
    fun decode(digits: String, maxPaths: Int = 50): List<Path> {
        if (digits.isEmpty()) return emptyList()
        // 结果缓存：连续相同输入的 decode 结果可以直接复用
        if (decodeCache?.first == digits) {
            return decodeCache!!.second
        }
        val n = digits.length

        // ── K-Best Viterbi DP ──
        // dp[pos][pinyin] = best score（保留最优，用于后续剪枝）
        val dp = Array(n + 1) { mutableMapOf<String, Double>() }
        // back[pos][pinyin] = 最多 BACK_BEAM 条前驱（含 score，用于 N-best 回溯）
        val back = Array(n + 1) { mutableMapOf<String, MutableList<BackEntry>>() }

        dp[0][BOS] = 0.0

        for (i in 0 until n) {
            if (dp[i].isEmpty()) continue
            val maxLen = minOf(MAX_PINYIN_LEN, n - i)

            for (len in 1..maxLen) {
                val code = digits.substring(i, i + len)
                val pinyins = findMatchingPinyins(code)
                val exactSet = if (len >= 2 && neighborEnabled && pinyins.isNotEmpty())
                    pinyins.toSet() else null

                // 1. 精确匹配 + 模糊音匹配
                if (pinyins.isNotEmpty()) {
                    val dpRow = dp[i]; val dpNext = dp[i + len]
                    val backRow = back[i + len]
                    for ((lastPinyin, score) in dpRow) {
                        for (pinyin in pinyins) {
                            val t = lm.transitionLogProb(lastPinyin, pinyin)
                            val lenBonus = if (pinyin.length <= 2) 0.0
                                else (pinyin.length - 2) * 0.3
                            val fuzzyPenalty = if (isFuzzyMatch(pinyin, code)) -0.5 else 0.0
                            val newScore = score + t + lenBonus + fuzzyPenalty
                            addBackEntry(backRow, dpNext, pinyin, BackEntry(i, lastPinyin, newScore))
                        }
                    }
                }

                // 2. 键盘邻居容错
                if (len >= 2 && neighborEnabled) {
                    val neighborPinyins = findNeighborPinyins(code, exactSet)
                    if (neighborPinyins.isNotEmpty()) {
                        val dpRow = dp[i]; val dpNext = dp[i + len]
                        val backRow = back[i + len]
                        for ((lastPinyin, score) in dpRow) {
                            for (pinyin in neighborPinyins) {
                                val t = lm.transitionLogProb(lastPinyin, pinyin)
                                val lenBonus = if (pinyin.length <= 2) 0.0
                                    else (pinyin.length - 2) * 0.3
                                val newScore = score + t + lenBonus - 1.5
                                addBackEntry(backRow, dpNext, pinyin, BackEntry(i, lastPinyin, newScore))
                            }
                        }
                    }
                }
            }

            // 3. 单字母回退
            val letters = digitToLetters[digits[i]] ?: continue
            val dpRow = dp[i]; val dpNext = dp[i + 1]
            val backRow = back[i + 1]
            for ((lastPinyin, score) in dpRow) {
                for (l in letters) {
                    val ch = l.toString()
                    val t = lm.transitionLogProb(lastPinyin, ch)
                    val newScore = score + t + 0.5
                    addBackEntry(backRow, dpNext, ch, BackEntry(i, lastPinyin, newScore))
                }
            }
        }

        // ── K-Best 回溯 ──
        // 对每个终点拼音 × 每条 K-Best 前驱，沿单链回溯到 BOS
        val results = mutableListOf<Path>()

        // 按每个终点的最优得分降序遍历
        val sortedTerminals = dp[n].entries
            .sortedByDescending { it.value }
            .take(maxPaths * 2) // 多取一些，后面每个 pinyin 有 K 个 entry

        for ((pinyin) in sortedTerminals) {
            if (results.size >= maxPaths) break
            val entries = back[n][pinyin]
            if (entries == null) continue

            // 每一条 K-Best 前驱产生一个独立路径
            for (e in entries.sortedByDescending { it.score }) {
                if (results.size >= maxPaths) break

                val syllables = mutableListOf<String>()
                val lengths = mutableListOf<Int>()

                // 第一步：最后一段从 e.prevPos → n
                syllables.add(0, pinyin)
                lengths.add(0, n - e.prevPos)

                // 沿单链回溯（只取每步的最优前驱）
                var pos = e.prevPos
                var cur = e.prevPinyin
                while (pos > 0) {
                    val preds = back[pos][cur]
                    if (preds == null || preds.isEmpty()) break
                    val best = preds.maxByOrNull { it.score } ?: break
                    val digitLen = pos - best.prevPos
                    syllables.add(0, cur)
                    lengths.add(0, digitLen)
                    pos = best.prevPos
                    cur = best.prevPinyin
                }

                if (syllables.isNotEmpty()) {
                    val resolved = syllables.joinToString("")
                    if (resolved !in results.map { it.resolved }.toSet()) {
                        val displayScore = kotlin.math.exp(e.score / 10.0)
                        results.add(Path(syllables, "", displayScore, lengths))
                    }
                }
            }
        }

        // 如果没有路径到结尾，找最远的非结尾路径（fallback）
        if (results.isEmpty()) {
            for (pos in n - 1 downTo 1) {
                if (dp[pos].isEmpty()) continue
                val sorted = dp[pos].entries.sortedByDescending { it.value }
                for ((pinyin) in sorted) {
                    if (results.size >= maxPaths) break
                    val entries = back[pos][pinyin] ?: continue
                    for (e in entries.sortedByDescending { it.score }) {
                        if (results.size >= maxPaths) break

                        val syllables = mutableListOf<String>()
                        val lengths = mutableListOf<Int>()
                        syllables.add(0, pinyin)
                        lengths.add(0, pos - e.prevPos)

                        var curPos = e.prevPos
                        var cur = e.prevPinyin
                        while (curPos > 0) {
                            val preds = back[curPos][cur]
                            if (preds == null || preds.isEmpty()) break
                            val best = preds.maxByOrNull { it.score } ?: break
                            val digitLen = curPos - best.prevPos
                            syllables.add(0, cur)
                            lengths.add(0, digitLen)
                            curPos = best.prevPos
                            cur = best.prevPinyin
                        }

                        if (syllables.isNotEmpty()) {
                            val resolved = syllables.joinToString("")
                            if (resolved !in results.map { it.resolved }.toSet()) {
                                val currentLetters = digits.substring(pos).mapNotNull { d ->
                                    digitToLetters[d]?.firstOrNull()
                                }.joinToString("")
                                val displayScore = kotlin.math.exp(e.score / 10.0)
                                results.add(Path(syllables, currentLetters, displayScore, lengths))
                            }
                        }
                    }
                }
                if (results.isNotEmpty()) break
            }
        }

        // Cache the result
        decodeCache = digits to results.toList()
        return results
    }

    /** 向 K-Best backpointer 列表添加一条候选，自动修剪到 BACK_BEAM 条 */
    private fun addBackEntry(
        backRow: MutableMap<String, MutableList<BackEntry>>,
        dpNext: MutableMap<String, Double>,
        pinyin: String,
        entry: BackEntry
    ) {
        // 更新 dp 最优得分（用于后续剪枝）
        if (entry.score > (dpNext[pinyin] ?: Double.NEGATIVE_INFINITY)) {
            dpNext[pinyin] = entry.score
        }
        // 加入 K-Best 列表
        val list = backRow.getOrPut(pinyin) { mutableListOf() }
        list.add(entry)
        if (list.size > BACK_BEAM) {
            list.sortByDescending { it.score }
            list.removeAt(BACK_BEAM)
        }
    }

    /** 查找匹配的拼音（精确匹配 + 模糊音扩展） */
    private fun findMatchingPinyins(code: String): List<String> {
        val exact = codeToPinyins[code]
        val fuzzy = fuzzyCodeMap[code]
        // 模糊匹配时，必须确保拼音的标准编码长度 == 输入编码长度
        // 否则会引入长度不匹配的拼音（如"74"匹配到"shi"标准码"744"）
        if (fuzzy != null) {
            val filteredFuzzy = fuzzy.filter { pinyin ->
                val standardCode = pinyin.map { FuzzyPinyin.LETTER_TO_DIGIT[it] ?: it }.joinToString("")
                standardCode.length == code.length
            }
            return when {
                exact != null -> (exact + filteredFuzzy.filter { it !in exact }).distinct()
                else -> filteredFuzzy
            }
        }
        return exact ?: emptyList()
    }

    /** 判断拼音是否是通过模糊音匹配的 */
    private fun isFuzzyMatch(pinyin: String, code: String): Boolean {
        val exact = codeToPinyins[code]
        return exact == null || pinyin !in exact
    }

    /** 查找键盘邻居匹配的拼音 */
    private fun findNeighborPinyins(code: String, exclude: Set<String>?): List<String> {
        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (i in code.indices) {
            val original = code[i]
            val neighbors = KEYBOARD_NEIGHBORS[original] ?: continue
            for (n in neighbors) {
                if (n == original) continue
                val modified = code.substring(0, i) + n + code.substring(i + 1)
                if (modified in seen) continue
                seen.add(modified)
                val pinyins = codeToPinyins[modified]
                if (pinyins != null) {
                    for (p in pinyins) {
                        if (exclude == null || p !in exclude) results.add(p)
                    }
                }
            }
        }

        return results.distinct()
    }

    /** 返回最佳完整拼音 */
    fun bestPinyin(digits: String): String {
        if (digits.isEmpty()) return ""
        val paths = decode(digits)
        if (paths.isEmpty()) return ""

        for (p in paths) {
            if (p.current.isEmpty()) return p.resolved
        }
        return paths.first().full
    }

    /** 返回输入数字序列对应的第一个音节的候选项（用于逐音节提示） */
    fun firstSyllableOptions(digits: String, maxResults: Int = 4): List<SyllableOption> {
        if (digits.isEmpty()) return emptyList()
        val allPinyins = codeToPinyins.values.flatten().toSet()
        val paths = decode(digits, maxPaths = 50)
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SyllableOption>()
        for (path in paths) {
            if (path.pinyins.isEmpty() || path.digitLengths.isEmpty()) continue
            val first = path.pinyins[0]
            if (first in seen || first !in allPinyins) continue
            seen.add(first)
            result.add(SyllableOption(first, path.digitLengths[0]))
            if (result.size >= maxResults) break
        }
        return result
    }

    /** 返回拼音候选项列表（用于九宫格左侧列展示） */
    fun candidates(digits: String, maxResults: Int = 10): List<String> {
        if (digits.isEmpty()) return emptyList()

        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()

        // 1. 从长到短搜索匹配的拼音，优先返回完整编码匹配
        for (len in minOf(4, digits.length) downTo 1) {
            val code = digits.substring(digits.length - len)
            val pinyins = findMatchingPinyins(code)
            for (p in pinyins) {
                if (p !in seen) {
                    seen.add(p)
                    result.add(p)
                    if (result.size >= maxResults) return result
                }
            }
        }

        // 2. 从 decode 结果补充（取最后一个有效拼音音节）
        val paths = decode(digits, maxPaths = 10)
        for (p in paths) {
            val last = p.pinyins.lastOrNull() ?: continue
            if (last.length >= 2 && last !in seen) {
                seen.add(last)
                result.add(last)
                if (result.size >= maxResults) return result
            }
        }

        // 3. 拼音前缀补全（取最后2位数字的前缀匹配）
        if (result.size < maxResults) {
            val lastCode = digits.takeLast(2)
            for (pinyin in FuzzyPinyin.PINYIN_LIST) {
                val code = pinyin.map { FuzzyPinyin.LETTER_TO_DIGIT[it] ?: it }.joinToString("")
                if (code.startsWith(lastCode) && pinyin !in seen) {
                    seen.add(pinyin)
                    result.add(pinyin)
                    if (result.size >= maxResults) return result
                }
            }
        }

        return result
    }

    /** 左列候选 */
    fun leftColumnCandidates(digits: String): List<String> {
        return candidates(digits, maxResults = 4)
    }

    /** 暴露 LM 用于诊断测试 */
    internal fun getLM(): PinyinBigramLM = lm
}
