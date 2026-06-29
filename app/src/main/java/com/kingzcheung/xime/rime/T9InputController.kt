package com.kingzcheung.xime.rime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 九键拼音输入控制器
 *
 * 封装九宫格拼音输入的状态机和所有业务逻辑。
 * 原内联于 NineKeyKeyboardLayout.kt 的左侧候选区逻辑提取至此。
 *
 * 职责：
 * - inputBuffer 管理（已确认拼音 + 分隔符 + 数字序列）
 * - 左侧候选区（firstOptions）的计算与锁定控制
 * - 分词、选词、删除、清空等操作
 * - 向 RIME 引擎发送输入（通过 onReplaceFullPinyin 回调）
 *
 * 使用示例（在 Compose 中）：
 *   val controller = remember { T9InputController(onReplaceFullPinyin) }
 *   LaunchedEffect(resetSignal) { controller.reset() }
 *   Text(controller.firstOptions.map { it.pinyin }.toString())
 */
class T9InputController(
    private val onReplaceFullPinyin: (String) -> Unit,
    private val onQueryRimeComposition: (() -> RimeComposition)? = null,
    /** 可选回调：撤销一次右侧候选选词（partial commit）。服务层应同步删除已提交文本，
     *  并从 partial commit 累积列表中移除最近一次提交的候选。 */
    private val onRightCommitUndone: ((Int) -> Unit)? = null
) {
    companion object {
        /** sendToRime 的特殊标记：仅清除 RIME composition，保留 partial commit 累积文本 */
        const val CLEAR_COMPOSITION_ONLY = "\u0000CLEAR_COMPOSITION_ONLY"
        /** sendToRime 的特殊标记：清除 RIME composition 并清空 partial commit 累积文本 */
        const val CLEAR_ALL = "\u0000CLEAR_ALL"
    }
    // ── 响应式状态 ──

    /** 退格操作结果类型 */
    enum class DeleteResult {
        /** 已消费：删除了一个字符 */
        DELETED,
        /** 已消费：撤销了左侧候选区选词操作（onChoiceSelected） */
        UNDO_CHOICE,
        /** 已消费：撤销了右侧候选列表选词操作（onRightCandidateSelected） */
        UNDO_COMMIT,
        /** 未消费：inputBuffer 已空 */
        NOT_CONSUMED
    }

    /** 发送给 RIME 的完整输入序列。
     *  格式：已确认拼音 + 分隔符(') + 数字 ...
     *  例如 "j'43"（5后分词确认j，再输入43）、"ji'482"（左侧选ji后继续输入） */
    var inputBuffer: String by mutableStateOf("")
        internal set

    /** 左侧候选区的音节选项列表 */
    var firstOptions: List<T9PinyinMap.SyllableOption> by mutableStateOf(emptyList())
        private set

    /** 上次发送给 RIME 的输入（避免冗余调用） */
    var lastRimeInput: String? by mutableStateOf(null)
        private set

    /** 左侧候选区是否锁定（分词后锁定，用户选择后解锁） */
    var leftColumnLocked: Boolean by mutableStateOf(false)
        private set

    // ── 内部状态 ──

    /**
     * lastDigitSegment() 结果缓存，避免相同数字段重复调用 T9PinyinMap 计算。
     * key = 数字段，value = 对应 firstOptions 结果。
     * 当数字段变化或 force=true 时清空。
     */
    private var cachedDigitSegment: String? = null
    private var cachedFirstOptions: List<T9PinyinMap.SyllableOption> = emptyList()

    /**
     * 快照式撤销记录。
     *
     * 每次语义操作前保存快照，撤销时恢复到上一个快照。
     * 相比增量撤销，逻辑更简单：
     *   - 撤销逻辑从 O(n) 降为 O(1)
     *   - 消除复杂的 UndoEntry 状态机
     *   - 状态更可预测，测试路径更少
     *
     * 存储代价：在输入法场景中，每个 buffer 通常 < 20 字符，
     * 存储 10 个快照 ≈ 200 bytes，可忽略。
     *
     * 回退优先级（[onDeleted]）：
     *   1. RightCommit 后 remaining 未被修改 → 撤销 RightCommit
     *   2. 当前 buffer 有数字段 → 逐位删除数字（不查阅快照）
     *   3. 快照栈非空 → 撤销到上一个快照
     *   4. buffer 非空 → 删除最后一个字符
     */
    private data class Snapshot(
        val buffer: String,
        val isRightCommit: Boolean,
        /** RightCommit 使用：消费的数字段 */
        val consumedDigits: String = "",
        /** RightCommit 使用：commit 后剩余的数字段 */
        val remainingAfterCommit: String = "",
        /** 撤销后应恢复的辅助状态 */
        val separatorConsumedDigits: String? = null,
        val lastChoiceConsumedDigits: String? = null,
        val leftColumnLocked: Boolean = false,
    )

    private val snapshots = mutableListOf<Snapshot>()

    /**
     * RightCommit 后，remainingAfterCommit 是否被修改过。
     * 一旦修改，后续撤销 RightCommit 只恢复 consumedDigits，而非完整 prevBuffer。
     */
    private var rightCommitRemainingDirty: Boolean = false

    /**
     * 记录最近一次分词键消费的数字段。
     * 用于在分词后显示候选，让用户能修改首字母。
     * 当用户选择左侧候选或撤销时清空。
     */
    private var separatorConsumedDigits: String? = null

    /**
     * 记录最近一次左侧候选选择消费的数字段。
     * 用于在分隔符结尾时显示候选。
     */
    private var lastChoiceConsumedDigits: String? = null

    // ── 公开 API ──

    /** 重置所有状态（响应 resetSignal） */
    fun reset() {
        inputBuffer = ""
        firstOptions = emptyList()
        lastRimeInput = null
        leftColumnLocked = false
        snapshots.clear()
        rightCommitRemainingDirty = false
        separatorConsumedDigits = null
        lastChoiceConsumedDigits = null
        cachedDigitSegment = null
        cachedFirstOptions = emptyList()
    }

    /** 从 inputBuffer 中提取最后一个纯数字段，用于左侧候选区展示 */
    fun lastDigitSegment(): String = parseInputBuffer(inputBuffer).digitSegment

    /**
     * inputBuffer 解析结果。
     *
     * 将 `indexOf('\\'')`、`lastDigitSegment()` 等分散解析逻辑集中到此数据类，
     * 减少控制器中重复的下标计算，提高可维护性。
     */
    private data class BufferParts(
        /** 原始 buffer */
        val raw: String,
        /** 第一个分隔符位置，-1 表示无分隔符 */
        val apostropheIndex: Int,
    ) {
        /** 是否存在分隔符 */
        val hasApostrophe: Boolean get() = apostropheIndex >= 0

        /** 第一个分隔符前的已确认拼音；无分隔符时为空 */
        val confirmedPinyin: String get() = if (hasApostrophe) raw.substring(0, apostropheIndex) else ""

        /** 第一个分隔符后的内容；无分隔符时为整个 raw */
        val afterApostrophe: String get() = if (hasApostrophe) raw.substring(apostropheIndex + 1) else raw

        /** 当前 buffer 中最后一个连续数字段 */
        val digitSegment: String get() {
            val idx = raw.indexOfLast { it !in '0'..'9' }
            return if (idx >= 0) raw.substring(idx + 1) else raw
        }

        /** 是否为纯数字 buffer（无分隔符且全是数字） */
        val isPureDigits: Boolean get() = raw.isNotEmpty() && raw.all { it in '0'..'9' }
    }

    /** 解析 inputBuffer 为 [BufferParts]，以最后一个分隔符划分已确认拼音与剩余内容 */
    private fun parseInputBuffer(buffer: String): BufferParts = BufferParts(buffer, buffer.lastIndexOf('\''))

    /**
     * 更新左侧候选区。
     *
     * 使用 [cachedDigitSegment] 缓存避免相同数字段重复调用 T9PinyinMap 的 HashMap 查找。
     * 仅在 [force]=true 或当前数字段与缓存不同时重新计算。
     *
     * @param force 是否强制更新（用户主动选择时强制刷新）
     */
    fun updateCandidates(force: Boolean = false) {
        if (leftColumnLocked && !force) return

        val parts = parseInputBuffer(inputBuffer)

        // 分词键后用户尚未从左侧候选区选择拼音：
        // 优先显示分词键所消费数字段对应的候选，保持简拼首字母可修改。
        val lastSeparatorDigits = lastSeparatorConsumedDigits()
        if (lastSeparatorDigits != null && parts.hasApostrophe) {
            if (force || cachedDigitSegment != lastSeparatorDigits) {
                cachedDigitSegment = lastSeparatorDigits
                cachedFirstOptions = T9PinyinMap.firstSyllableOptions(lastSeparatorDigits, maxResults = 12)
            }
            firstOptions = cachedFirstOptions
            leftColumnLocked = false
            return
        }

        val segment = parts.digitSegment
        if (segment.isNotEmpty()) {
            if (force || cachedDigitSegment != segment) {
                cachedDigitSegment = segment
                cachedFirstOptions = T9PinyinMap.firstSyllableOptions(segment, maxResults = 12)
            }
            firstOptions = cachedFirstOptions
        } else if (parts.raw.endsWith("'")) {
            if (force || cachedDigitSegment != null) {
                // 以分隔符结尾：表示已确认拼音后仍有可编辑空间，
                // 此时左侧候选区应基于该次确认所消费的数字段显示候选，
                // 让用户能继续选择该数字段对应的拼音/字母。
                val digits = lastConsumedDigitsForCandidates()
                cachedDigitSegment = digits
                cachedFirstOptions = digits?.let {
                    T9PinyinMap.firstSyllableOptions(it, maxResults = 12)
                } ?: emptyList()
            }
            firstOptions = cachedFirstOptions
            leftColumnLocked = false
        } else {
            // 已确认完整拼音组合且无剩余数字：左侧候选区进入空闲态
            cachedDigitSegment = null
            cachedFirstOptions = emptyList()
            firstOptions = emptyList()
            leftColumnLocked = false
        }
    }

    /**
     * 返回最近一次分词键消费的数字段。
     * 用于在分词后显示候选，让用户能修改首字母。
     */
    private fun lastSeparatorConsumedDigits(): String? = separatorConsumedDigits

    /**
     * 返回最近一次左侧候选选择消费的数字段；若不存在则回退到分词键消费的数字段。
     * 用于在分隔符结尾时显示候选。
     */
    private fun lastConsumedDigitsForCandidates(): String? = lastChoiceConsumedDigits ?: separatorConsumedDigits

    /** 将 inputBuffer 发送给 RIME 引擎 */
    fun sendToRime() {
        val input = inputBuffer
        if (input.isEmpty()) {
            if (lastRimeInput != null) {
                lastRimeInput = null
                // 当 buffer 为空但仍有 RightCommit 未撤销时，只清除 RIME composition
                // 但不清空 partial commit 累积文本（预编辑区域仍需显示已提交的候选词）
                val hasPendingRightCommit = snapshots.any { it.isRightCommit }
                onReplaceFullPinyin(if (hasPendingRightCommit) CLEAR_COMPOSITION_ONLY else CLEAR_ALL)
            }
            return
        }
        if (input == lastRimeInput) return
        lastRimeInput = input
        onReplaceFullPinyin(input)
    }

    /**
     * 优先通过 RIME 候选 comment 反推当前数字段的最优首音节。
     *
     * 取当前 composition 第一个带 comment 的候选，将其 comment（如 "ji gua"）
     * 按空格拆分为音节，取第一个音节并验证其数字编码是否与 [digits] 前缀匹配。
     * 若 RIME 未返回有效 comment 或匹配失败，则回退到本地贪婪最长匹配。
     */
    private fun inferFirstSyllableFromRime(digits: String): T9PinyinMap.SyllableOption? {
        val composition = onQueryRimeComposition?.invoke() ?: return fallbackFirstSyllable(digits)

        val comment = composition.candidates
            .firstOrNull { it.comment.isNotBlank() }
            ?.comment
            ?.trim()
            ?: return fallbackFirstSyllable(digits)

        val firstPinyin = comment.split(Regex("\\s+"))
            .firstOrNull { it.isNotEmpty() }
            ?: return fallbackFirstSyllable(digits)

        val code = T9PinyinMap.pinyinToDigitCode(firstPinyin)
        if (code != null && digits.startsWith(code)) {
            return T9PinyinMap.SyllableOption(firstPinyin.lowercase(), code.length)
        }
        return fallbackFirstSyllable(digits)
    }

    private fun fallbackFirstSyllable(digits: String): T9PinyinMap.SyllableOption? {
        return T9PinyinMap.firstSyllableOptions(digits, maxResults = 1).firstOrNull()
    }

    /** 处理数字按键/分词键按下 */
    fun onDigitPressed(digit: String) {
        if (digit == "1") {
            // 分词键：将当前数字段转为拼音 + 分隔符，锁定左侧候选区。
            // 优先通过 RIME 候选 comment 反推最优首音节；失败时回退到贪婪最长匹配。
            val segment = lastDigitSegment()
            if (segment.isNotEmpty()) {
                val segmentStart = inputBuffer.length - segment.length
                val confirmed = inferFirstSyllableFromRime(segment)
                if (confirmed != null) {
                    // 在修改 buffer 前保存快照：保留分词前完整 buffer
                    snapshots.add(
                        Snapshot(
                            buffer = inputBuffer,
                            isRightCommit = false,
                            separatorConsumedDigits = separatorConsumedDigits,
                            lastChoiceConsumedDigits = lastChoiceConsumedDigits,
                            leftColumnLocked = leftColumnLocked,
                        )
                    )
                    // 记录分词键消费的数字段
                    separatorConsumedDigits = segment.take(confirmed.digitLength)
                    lastChoiceConsumedDigits = null
                    val remaining = segment.drop(confirmed.digitLength)
                    inputBuffer = inputBuffer.substring(0, segmentStart) + confirmed.pinyin + "'" + remaining
                } else {
                    if (!inputBuffer.endsWith("'")) {
                        inputBuffer += "'"
                    }
                }
            }
            leftColumnLocked = true
            sendToRime()
            return
        }

        // 在 RightCommit 后输入数字会修改 remainingAfterCommit，标记 dirty
        if (snapshots.isNotEmpty() && snapshots.last().isRightCommit) {
            rightCommitRemainingDirty = true
        }
        // 已确认拼音（字母结尾）后追加数字时，自动插入分隔符 '。
        // 让 RIME 把已确认拼音与新数字段解析为多音节，否则 "l4" 会被当作
        // 单音节展开为 "li"，lua filter 无法替换数字段，preedit 显示 "l4" 而非 "l g"。
        if (inputBuffer.isNotEmpty() && inputBuffer.last().isLetter()) {
            inputBuffer += "'"
        }
        inputBuffer += digit
        updateCandidates()
        sendToRime()
    }

    /** 处理用户从左侧列选择音节 */
    fun onChoiceSelected(option: T9PinyinMap.SyllableOption) {
        // 在 RightCommit 后选择左侧候选会修改 remainingAfterCommit，标记 dirty
        if (snapshots.isNotEmpty() && snapshots.last().isRightCommit) {
            rightCommitRemainingDirty = true
        }

        val parts = parseInputBuffer(inputBuffer)

        if (parts.apostropheIndex > 0) {
            val afterApostrophe = parts.afterApostrophe
            if (leftColumnLocked) {
                // ── 锁定态：仅替换已有拼音，不消费新数字 ──
                // 快照保存到分隔符为止的状态（剩余数字随当前选择一起可撤销）
                snapshots.add(
                    Snapshot(
                        buffer = parts.confirmedPinyin + "'",
                        isRightCommit = false,
                        separatorConsumedDigits = separatorConsumedDigits,
                        lastChoiceConsumedDigits = lastChoiceConsumedDigits,
                        leftColumnLocked = leftColumnLocked,
                    )
                )
                // 锁定态选择后，将分词键消费的数字段转给 lastChoiceConsumedDigits，
                // 以便以分隔符结尾时仍能显示候选
                lastChoiceConsumedDigits = separatorConsumedDigits
                separatorConsumedDigits = null
                inputBuffer = option.pinyin + "'" + afterApostrophe
            } else {
                // ── 未锁定态：从剩余数字段中消费 N 位 ──
                val remaining = afterApostrophe.drop(option.digitLength)
                val consumedDigits = afterApostrophe.take(option.digitLength)
                // 快照保存撤销后应恢复的状态：confirmedPinyin + "'" + 本次消费的数字段
                snapshots.add(
                    Snapshot(
                        buffer = parts.confirmedPinyin + "'" + consumedDigits,
                        isRightCommit = false,
                        separatorConsumedDigits = separatorConsumedDigits,
                        lastChoiceConsumedDigits = lastChoiceConsumedDigits,
                        leftColumnLocked = leftColumnLocked,
                    )
                )
                // 记录左侧候选选择消费的数字段
                lastChoiceConsumedDigits = consumedDigits
                // 清空分词键消费的数字段
                separatorConsumedDigits = null
                inputBuffer = if (remaining.isEmpty()) {
                    parts.confirmedPinyin + option.pinyin
                } else {
                    parts.confirmedPinyin + option.pinyin + "'" + remaining
                }
            }
            leftColumnLocked = false
            updateCandidates(force = true)
            sendToRime()
            return
        }

        // ── 纯数字段（无 '）：消费前 N 位数字 ──
        val segment = parts.digitSegment
        if (segment.isEmpty()) return
        if (option.digitLength > segment.length) return
        val segmentStart = inputBuffer.length - segment.length
        val remaining = segment.drop(option.digitLength)
        val consumedDigits = segment.take(option.digitLength)
        // 快照保存「前缀已确认拼音 + 本次消费的数字段」：剩余数字由 onDeleted 优先级2逐位删除。
        // 若只保存 consumedDigits，当 inputBuffer 含前缀字母（如 "l4"）时撤销会丢失前缀 "l"。
        snapshots.add(
            Snapshot(
                buffer = inputBuffer.substring(0, segmentStart) + consumedDigits,
                isRightCommit = false,
                separatorConsumedDigits = separatorConsumedDigits,
                lastChoiceConsumedDigits = lastChoiceConsumedDigits,
                leftColumnLocked = leftColumnLocked,
            )
        )
        // 记录左侧候选选择消费的数字段
        lastChoiceConsumedDigits = consumedDigits
        separatorConsumedDigits = null
        inputBuffer = if (remaining.isEmpty()) {
            inputBuffer.substring(0, segmentStart) + option.pinyin
        } else {
            inputBuffer.substring(0, segmentStart) + option.pinyin + "'" + remaining
        }
        leftColumnLocked = false
        updateCandidates(force = true)
        sendToRime()
    }

    /**
     * 处理退格删除。
     *
     * 快照式撤销逻辑（优先级）：
     *   1. RightCommit 后 remaining 未被修改 → 撤销 RightCommit（恢复完整 prevBuffer）
     *   2. buffer 有数字段 → 逐位删除数字
     *   3. 快照栈非空 → 撤销到上一个快照
     *   4. buffer 非空 → 删除最后一个字符
     *
     * @return DeleteResult 区分操作类型：
     *   - UNDO_COMMIT: 撤销右侧候选选词。clearRimeAndResend 已同步删除已提交文本
     *   - UNDO_CHOICE: 撤销左侧候选区选词/分词键。已内部调用 sendToRime()
     *   - DELETED:    已删除一个字符。已内部调用 sendToRime()
     *   - NOT_CONSUMED: 无内容可删
     */
    fun onDeleted(): DeleteResult {
        // ── 优先级1：RightCommit 撤销（remaining 未被修改且当前数字段完整保留）──
        if (snapshots.isNotEmpty()) {
            val lastSnapshot = snapshots.last()
            if (lastSnapshot.isRightCommit && !rightCommitRemainingDirty) {
                val currentDigitSegment = lastDigitSegment()
                if (currentDigitSegment == lastSnapshot.remainingAfterCommit) {
                    val snapshot = snapshots.removeAt(snapshots.lastIndex)
                    rightCommitRemainingDirty = false
                    separatorConsumedDigits = null
                    lastChoiceConsumedDigits = null
                    inputBuffer = snapshot.buffer
                    leftColumnLocked = false
                    updateCandidates(force = true)
                    // UNDO_COMMIT: 不调 sendToRime() — 由 UI 层先删除已提交文本再调用
                    return DeleteResult.UNDO_COMMIT
                }
            }
        }

        // ── 优先级2：buffer 有数字段 → 逐位删除数字 ──
        if (lastDigitSegment().isNotEmpty()) {
            inputBuffer = inputBuffer.dropLast(1)
            leftColumnLocked = false
            updateCandidates()
            sendToRime()
            // 如果正在删除 RightCommit 的 remaining，标记 dirty
            if (snapshots.isNotEmpty() && snapshots.last().isRightCommit) {
                rightCommitRemainingDirty = true
            }
            return DeleteResult.DELETED
        }

        // ── 优先级3：快照栈非空 → 撤销到上一个快照 ──
        if (snapshots.isNotEmpty()) {
            val snapshot = snapshots.removeAt(snapshots.lastIndex)
            // 恢复辅助状态
            separatorConsumedDigits = snapshot.separatorConsumedDigits
            lastChoiceConsumedDigits = snapshot.lastChoiceConsumedDigits
            leftColumnLocked = snapshot.leftColumnLocked

            if (snapshot.isRightCommit) {
                rightCommitRemainingDirty = false
                // remaining 已被删光，只恢复 consumedDigits
                inputBuffer = snapshot.consumedDigits
                updateCandidates(force = true)
                // UNDO_COMMIT: 不调 sendToRime() — 由 UI 层先删除已提交文本再调用
                return DeleteResult.UNDO_COMMIT
            } else {
                // LeftChoice/Separator：快照已预计算好撤销后应恢复的 buffer，直接恢复
                inputBuffer = snapshot.buffer
                updateCandidates(force = true)
                sendToRime()
                return DeleteResult.UNDO_CHOICE
            }
        }

        // ── 优先级4：buffer 非空 → 删除最后一个字符 ──
        if (inputBuffer.isNotEmpty()) {
            inputBuffer = inputBuffer.dropLast(1)
            leftColumnLocked = false
            updateCandidates()
            sendToRime()
            return DeleteResult.DELETED
        }

        return DeleteResult.NOT_CONSUMED
    }

    /**
     * 处理右侧候选列表选词（partial commit，无拼音注释信息）。
     *
     * 委托给 [onRightCandidateSelected] 并传入 null。
     * 当拼音注释不可用时，回退到基于 [firstSyllableOptions] 消费第一个音节。
     */
    fun onRightCandidateSelected(): Boolean = onRightCandidateSelected(null)

    /**
     * 处理右侧候选列表选词（partial commit）。
     *
     * 用户从右侧候选列表选词后，RIME 做 partial commit，提交选中文本并保留剩余拼音。
     * 本方法接收候选词的拼音注释，通过计算拼音字母数确定消费的数字位数，
     * 零额外 JNI 调用——拼音注释已由 getCandidatesWithComments() 获取。
     *
     * @param candidatePinyin 候选词的拼音注释（如 "jin xing"、"ce"）。
     *                        null = 无拼音注释，回退到消费第一个音节。
     *                        拼音注释中每个字母对应一个数字键，字母总数 = 消费位数。
     *
     * 设计决策：
     * - 不调用 sendToRime() — RIME 已有正确状态
     * - 保存快照，使 backspace 能撤销这次操作恢复原始数字序列
     * - 零额外 JNI 调用 — 使用已有的拼音注释数据
     *
     * @return 如果本次选词后 inputBuffer 已空，说明输入序列被完整消费，调用方应视为 full commit。
     */
    fun onRightCandidateSelected(candidatePinyin: String?): Boolean {
        if (inputBuffer.isEmpty()) return true

        // ── 保存快照 ──
        // 计算 consumedDigits 与 remainingAfterCommit，用于撤销时恢复
        val parts = parseInputBuffer(inputBuffer)
        val (consumedDigits, remainingAfterCommit) = if (parts.apostropheIndex >= 0) {
            // 带分隔符时，候选词可能消费 confirmed pinyin 之后的剩余字母。
            // 用候选拼音字母总数减去 confirmed 字母数，得到剩余段应消费的长度。
            val candidateLetterCount = candidatePinyin?.count { it.isLetter() } ?: 0
            val consumedAfter = candidateLetterCount - parts.confirmedPinyin.length
            if (consumedAfter > 0) {
                val remaining = parts.afterApostrophe.drop(consumedAfter)
                parts.afterApostrophe.take(consumedAfter) to remaining
            } else {
                // 候选未消费分隔符后内容：保持原有行为
                parts.afterApostrophe to parts.afterApostrophe
            }
        } else {
            val segment = parts.digitSegment
            if (segment.isNotEmpty()) {
                val consumedCount = computeConsumedDigitsFromPinyin(segment, candidatePinyin)
                segment.take(consumedCount) to segment.drop(consumedCount)
            } else {
                // 纯拼音段：视为消费全部，剩余为空
                "" to ""
            }
        }
        snapshots.add(
            Snapshot(
                buffer = inputBuffer,
                isRightCommit = true,
                consumedDigits = consumedDigits,
                remainingAfterCommit = remainingAfterCommit,
                separatorConsumedDigits = separatorConsumedDigits,
                lastChoiceConsumedDigits = lastChoiceConsumedDigits,
                leftColumnLocked = leftColumnLocked,
            )
        )
        rightCommitRemainingDirty = false
        // 清空辅助状态
        separatorConsumedDigits = null
        lastChoiceConsumedDigits = null

        // Case 1: buffer 含分隔符（已确认拼音 + 剩余数字）
        //   例如 "pi'4" → RIME 消费 "pi"，保留 "4"
        //   例如 "k'ge" + 候选 kehu → RIME 消费全部，buffer 清空
        if (parts.apostropheIndex >= 0) {
            inputBuffer = remainingAfterCommit
            leftColumnLocked = false
            updateCandidates(force = true)
            lastRimeInput = inputBuffer
            return inputBuffer.isEmpty()
        }

        // Case 2: 纯数字段 — 使用候选词拼音注释计算消费位数
        val segment = parts.digitSegment
        if (segment.isNotEmpty()) {
            val consumedCount = computeConsumedDigitsFromPinyin(segment, candidatePinyin)
            val segmentStart = inputBuffer.length - segment.length
            val remaining = segment.drop(consumedCount)
            inputBuffer = inputBuffer.substring(0, segmentStart) + remaining
            leftColumnLocked = false
            updateCandidates(force = true)
            lastRimeInput = inputBuffer
            return inputBuffer.isEmpty()
        }

        // Case 3: 纯拼音段（无数字，无分隔符）— RIME 消费全部
        //   例如 "pi" → RIME 全量消费，buffer 清空
        inputBuffer = ""
        firstOptions = emptyList()
        leftColumnLocked = false
        lastRimeInput = inputBuffer
        return true
    }

    /**
     * 清除 RIME composition 后重新发送完整 inputBuffer。
     *
     * 用于 UNDO_COMMIT 场景：先撤销最近一次右侧候选选词（删除已提交文本并从
     * partial commit 列表中移除），再清 RIME composition，最后送完整数字序列
     * （setInput）。同步执行避免异步 KEYCODE_DEL 干扰新 composition。
     */
    fun clearRimeAndResend() {
        onRightCommitUndone?.invoke(1)
        lastRimeInput = null
        onReplaceFullPinyin("")
        sendToRime()
    }

    /** 清空所有输入和候选 */
    fun clearAll() {
        inputBuffer = ""
        firstOptions = emptyList()
        leftColumnLocked = false
        snapshots.clear()
        rightCommitRemainingDirty = false
        separatorConsumedDigits = null
        lastChoiceConsumedDigits = null
        onReplaceFullPinyin(CLEAR_ALL)
    }

    /**
     * 用户按"换行"键提交预编辑文本后调用。
     * 彻底清空控制器状态并通知服务层清空 partial commit 累积文本，
     * 防止下一轮输入出现上一轮 partial commit 残留。
     */
    fun onEnterCommit() {
        inputBuffer = ""
        firstOptions = emptyList()
        lastRimeInput = null
        leftColumnLocked = false
        snapshots.clear()
        rightCommitRemainingDirty = false
        separatorConsumedDigits = null
        lastChoiceConsumedDigits = null
        cachedDigitSegment = null
        cachedFirstOptions = emptyList()
        onReplaceFullPinyin(CLEAR_ALL)
    }

    /**
     * 从候选词拼音注释计算消费的数字位数
     *
     * 原理：T9 九宫格中每个字母对应一个数字键，拼音注释的字母总数 = 消费的数字位数。
     * 例如 "jin xing" = 7 个字母 = 7 位数字，"ce" = 2 个字母 = 2 位数字。
     *
     * @param segment 当前纯数字段
     * @param candidatePinyin 候选词的拼音注释，null 表示无拼音信息
     * @return 应消费的数字位数
     */
    private fun computeConsumedDigitsFromPinyin(segment: String, candidatePinyin: String?): Int {
        if (!candidatePinyin.isNullOrEmpty()) {
            // 计算拼音注释中的 ASCII 字母数量（每个字母对应一个数字键）
            val letterCount = candidatePinyin.count { it in 'a'..'z' || it in 'A'..'Z' }
            if (letterCount > 0 && letterCount <= segment.length) {
                return letterCount
            }
        }
        // 无拼音信息或计算失败，回退到单音节消费
        val options = T9PinyinMap.firstSyllableOptions(segment, maxResults = 1)
        return options.firstOrNull()?.digitLength ?: 0
    }
}
