package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

/**
 * T9InputController 单元测试
 *
 * 覆盖以下关键场景：
 * - 数字按键 → inputBuffer 更新 + 候选区更新
 * - 分词键 → 确认拼音 + 锁定 + 锁定后继续输入
 * - 从锁定候选区选词（替换已确认拼音）
 * - 从剩余数字选词（消费剩余数字）
 * - 退格键 — 返回 DeleteResult 区分操作类型
 * - 右侧候选列表选词（partial commit）+ 撤销
 * - 清空
 */
class T9InputControllerTest {

    private fun createController(): T9InputController {
        return T9InputController(onReplaceFullPinyin = { /* no-op */ })
    }

    private fun createControllerWithRime(composition: RimeComposition): T9InputController {
        return T9InputController(
            onReplaceFullPinyin = { /* no-op */ },
            onQueryRimeComposition = { composition }
        )
    }

    private fun T9InputController.delete(): T9InputController.DeleteResult = onDeleted()

    private fun assertPinyins(controller: T9InputController, vararg expected: String) {
        assertEquals(expected.toList(), controller.firstOptions.map { it.pinyin })
    }

    // ── 基础按键 ──

    @Test
    fun `press 5 updates buffer and candidates`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isNotEmpty())
        assertEquals("j", ctrl.firstOptions.first().pinyin)
    }

    @Test
    fun `press 5 then 4 updates buffer to 54`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("4")
        assertEquals("54", ctrl.inputBuffer)
    }

    // ── 分词键 ──

    @Test
    fun `separator key converts digit 5 to j-apostrophe and locks`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1") // 分词键
        assertEquals("j'", ctrl.inputBuffer)
        assertTrue(ctrl.leftColumnLocked)
    }

    @Test
    fun `after separator pressing 43 appends digits and keeps panel locked`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1") // 分词 → "j'"
        ctrl.onDigitPressed("4") // "j'4" — panel locked, no update
        ctrl.onDigitPressed("3") // "j'43" — panel locked, no update
        assertEquals("j'43", ctrl.inputBuffer)
        assertTrue(ctrl.leftColumnLocked)
    }

    // ── 分词键：RIME comment 反推音节切分 ──

    @Test
    fun `separator uses rime comment to choose li over ji`() {
        // RIME 返回首选候选 comment 为 "li hua"，分词键应按 RIME 意图确认 "li"
        val composition = RimeComposition(
            input = "54482",
            preedit = "li hua",
            committedText = "",
            candidates = arrayOf(RimeCandidate("梨花", "li hua")),
            hasNextPage = false,
            hasPrevPage = false,
            isAsciiMode = false
        )
        val ctrl = createControllerWithRime(composition)
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("8")
        ctrl.onDigitPressed("2")
        ctrl.onDigitPressed("1") // 分词键

        assertEquals("li'482", ctrl.inputBuffer)
        assertTrue(ctrl.leftColumnLocked)
    }

    @Test
    fun `separator falls back to greedy when rime comment is empty`() {
        val composition = RimeComposition(
            input = "54482",
            preedit = "",
            committedText = "",
            candidates = arrayOf(RimeCandidate("ji", "")),
            hasNextPage = false,
            hasPrevPage = false,
            isAsciiMode = false
        )
        val ctrl = createControllerWithRime(composition)
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("8")
        ctrl.onDigitPressed("2")
        ctrl.onDigitPressed("1") // 分词键

        // 贪婪最长匹配："ji" 消费 54，剩余 482
        assertEquals("ji'482", ctrl.inputBuffer)
    }

    @Test
    fun `separator falls back to greedy when comment does not match digits prefix`() {
        // RIME comment 中的首音节 "gua" 对应 482，与当前数字段 54482 前缀不匹配
        val composition = RimeComposition(
            input = "54482",
            preedit = "",
            committedText = "",
            candidates = arrayOf(RimeCandidate("瓜", "gua")),
            hasNextPage = false,
            hasPrevPage = false,
            isAsciiMode = false
        )
        val ctrl = createControllerWithRime(composition)
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("8")
        ctrl.onDigitPressed("2")
        ctrl.onDigitPressed("1") // 分词键

        assertEquals("ji'482", ctrl.inputBuffer)
    }

    // ── 从锁定候选区选词（替换已确认拼音）──

    @Test
    fun `select k from locked panel replaces j with k`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1") // "j'" (locked)
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("3") // "j'43" (still locked)

        val result = ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.inputBuffer)
        assertFalse(ctrl.leftColumnLocked)
        assertTrue(ctrl.firstOptions.isNotEmpty())
        assertTrue(ctrl.firstOptions.any { it.pinyin == "he" })
    }

    // ── 从剩余数字选词（消费剩余数字） ──

    @Test
    fun `select he from unlocked panel with remaining 43 merges to khe`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1") // "j'"
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("3") // "j'43"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1)) // "k'43", unlocked

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("he", 2))
        assertEquals("khe", ctrl.inputBuffer)
        // 完整拼音组合且无剩余数字：左侧候选区进入空闲态
        assertTrue(ctrl.firstOptions.isEmpty())
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `select g from remaining 43 with digit length 1 leaves 3`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("1") // "j'"
        ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("3") // "j'43"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1)) // "k'43", unlocked

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("g", 1))
        assertEquals("kg'3", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isNotEmpty())
    }

    // ── 纯数字段（无 '）选词 ──

    @Test
    fun `select ji from 54482 produces ji-apostrophe-482`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ji", 2))
        assertEquals("ji'482", ctrl.inputBuffer)
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `select j from 54482 produces j-apostrophe-4482`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("j", 1))
        assertEquals("j'4482", ctrl.inputBuffer)
    }

    // ── 退格键 ──

    @Test
    fun `backspace removes last char and updates candidates`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("5", ctrl.inputBuffer)
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `backspace on empty buffer returns NOT_CONSUMED`() {
        val ctrl = createController()
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `backspace unlocks panel and undoes separator`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        assertEquals("j'", ctrl.inputBuffer)
        assertTrue(ctrl.leftColumnLocked)

        // 分词键后按退格：撤销分词键，恢复原始数字并解锁
        ctrl.delete()
        assertEquals("5", ctrl.inputBuffer)
        assertFalse(ctrl.leftColumnLocked)
    }

    // ── Backspace with undo（候选点击撤销） ──

    @Test
    fun `scenario 1 - after li backspace returns DELETED not UNDO_CHOICE when digits present`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("8"); ctrl.onDigitPressed("2")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))

        // "li'482" 有数字段，先删除数字而非撤销
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'48", ctrl.inputBuffer)
    }

    @Test
    fun `scenario 1 - 54482 plus li hua equals 7 backspaces total`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("hua", 3))
        assertEquals("lihua", ctrl.inputBuffer)

        // 预期回退顺序：hua→2→8→4→li→4→5 (7次)
        // BS1: 撤销 hua (UNDO_CHOICE)
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("li'482", ctrl.inputBuffer)
        // BS2: 删除数字 2 (DELETED)
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'48", ctrl.inputBuffer)
        // BS3: 删除数字 8 (DELETED)
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'4", ctrl.inputBuffer)
        // BS4: 删除数字 4 (DELETED) → "li'"，左侧应显示 li 对应数字段 54 的候选
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.map { it.pinyin }.containsAll(listOf("ji", "li", "j", "k", "l")))
        // BS5: 撤销 li (UNDO_CHOICE) — 无数字段了
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("54", ctrl.inputBuffer)
        // BS6: 删除数字 4 (DELETED)
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("5", ctrl.inputBuffer)
        // BS7: 删除数字 5 (DELETED)
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `scenario 1 variant - 54482 li gu b backspace order`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("li", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("gu", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("b", 1))
        assertEquals("ligub", ctrl.inputBuffer)

        // 回退顺序：b, 2, gu, 8, 2, li, 4, 5
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("ligu'2", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("ligu'", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("li'48", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'4", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("li'", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("54", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("5", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `scenario 2 - 54482 no clicks equals 5 backspaces`() {
        val ctrl = createController()
        for (d in listOf("5", "4", "4", "8", "2")) ctrl.onDigitPressed(d)

        for (expected in listOf("5448", "544", "54", "5", "")) {
            assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
            assertEquals(expected, ctrl.inputBuffer)
        }
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `scenario 3 - 5143 no clicks equals 4 backspaces`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.inputBuffer)
        // 锁定态下左侧候选区保持分词前的 5 键字母映射
        assertPinyins(ctrl, "j", "k", "l")

        // BS1: 删除 3 → "j'4"，未进行左侧候选区选择，继续显示分词键 5 的字母映射
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("j'4", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")
        // BS2: 删除 4 → "j'"，仍显示 5 的字母映射
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("j'", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")
        // BS3: 撤销分词键 → 恢复 "5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")
        // BS4: 删除 5 → ""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `scenario 4 - 5143 plus k ge equals 6 backspaces total`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("ge", 2))
        assertEquals("kge", ctrl.inputBuffer)
        // 完整拼音组合且无剩余数字：左侧候选区进入空闲态
        assertTrue(ctrl.firstOptions.isEmpty())

        // BS1: 撤销 ge (UNDO_CHOICE) → "k'43"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("k'43", ctrl.inputBuffer)
        assertPinyins(ctrl, "ge", "he", "g", "h", "i")
        // BS2: 删除 3 (DELETED) → "k'4"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("k'4", ctrl.inputBuffer)
        assertPinyins(ctrl, "g", "h", "i")
        // BS3: 删除 4 (DELETED) → "k'"，以分隔符结尾，显示 5 键字母映射
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("k'", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")
        // BS4: 撤销 k (UNDO_CHOICE) → "j'"，以分隔符结尾，仍显示 5 键字母映射
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("j'", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")
        // BS5: 撤销分词键 (UNDO_CHOICE) → "5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")
        // BS6: 删除 5 (DELETED) → ""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    // ── 清空 ──

    @Test
    fun `clearAll resets everything`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.clearAll()
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `reset clears buffer candidates and partial commit state`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected()
        assertTrue(ctrl.inputBuffer.isNotEmpty())

        ctrl.reset()

        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
        assertFalse(ctrl.leftColumnLocked)
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.onDeleted())
    }

    // ── 完整流程（5143 左侧选词） ──

    @Test
    fun `full flow 5143 select k then select he results in khe`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("j'43", ctrl.inputBuffer)
        assertTrue(ctrl.leftColumnLocked)

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        assertEquals("k'43", ctrl.inputBuffer)
        assertFalse(ctrl.leftColumnLocked)
        assertTrue(ctrl.firstOptions.any { it.pinyin == "he" })

        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("he", 2))
        assertEquals("khe", ctrl.inputBuffer)
        // 完整拼音组合且无剩余数字：左侧候选区进入空闲态
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ── lastDigitSegment ──

    @Test
    fun `lastDigitSegment on pure digits returns all`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("4")
        assertEquals("54", ctrl.lastDigitSegment())
    }

    @Test
    fun `lastDigitSegment with apostrophe returns after apostrophe`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        assertEquals("43", ctrl.lastDigitSegment())
    }

    @Test
    fun `lastDigitSegment on merged pinyin returns empty`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("k", 1))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("he", 2))
        assertEquals("", ctrl.lastDigitSegment())
    }

    // ── Right candidate selection (partial commit from prediction list) ──
    //
    // 用户从右侧候选列表选词后，RIME 做 partial commit（提交文本，保留拼音变体）。
    // T9Controller 同步消费对应数字，刷新左侧候选区。
    // 回退时 backspace 返回 UNDO_COMMIT => 由 UI 层先发送 delete 删除已提交文本，再 sendToRime 恢复数字。

    @Test
    fun `rightCandidate on 23744 consumes first 2 digits to 744`() {
        val ctrl = createController()
        ctrl.onDigitPressed("2"); ctrl.onDigitPressed("3")
        ctrl.onDigitPressed("7"); ctrl.onDigitPressed("4"); ctrl.onDigitPressed("4")
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.inputBuffer)
        assertTrue("left options should contain shi", ctrl.firstOptions.any { it.pinyin == "shi" })
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `rightCandidate on empty buffer does nothing`() {
        val ctrl = createController()
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `rightCandidate on 5 consumes all to empty`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5")
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    @Test
    fun `rightCandidate on 5143 with delimiter consumes confirmed pinyin j leaving 43`() {
        val ctrl = createController()
        ctrl.onDigitPressed("5"); ctrl.onDigitPressed("1")  // 5→分词→j'
        ctrl.onDigitPressed("4"); ctrl.onDigitPressed("3")  // j'43
        ctrl.onRightCandidateSelected()
        // RIME 消费 confirmed pinyin "j"，保留剩余数字 "43"
        assertEquals("43", ctrl.inputBuffer)
        assertTrue("left options should contain ge/he for 43", ctrl.firstOptions.isNotEmpty())
        assertFalse(ctrl.leftColumnLocked)
    }

    @Test
    fun `first backspace after rightCandidate returns UNDO_COMMIT restores buffer without sendToRime`() {
        val ctrl = createController()
        ctrl.onDigitPressed("2"); ctrl.onDigitPressed("3")
        ctrl.onDigitPressed("7"); ctrl.onDigitPressed("4"); ctrl.onDigitPressed("4")
        ctrl.onRightCandidateSelected() // buffer="744", lastRimeInput="744"
        assertEquals("744", ctrl.inputBuffer)

        // BS1: UNDO_COMMIT — 恢复 buffer，但不调用 sendToRime (lastRimeInput 不变)
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23744", ctrl.inputBuffer)
        assertTrue("left options should contain ce", ctrl.firstOptions.any { it.pinyin == "ce" })

        // sendToRime() 应当被调用（调用后 lastRimeInput 同步）
        ctrl.sendToRime()
    }

    @Test
    fun `full flow 23744 candidate ce then 6 backspaces clears all`() {
        val ctrl = createController()
        // 5 次按键
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        assertTrue(ctrl.firstOptions.any { it.pinyin == "ce" })

        // 1 次右侧候选选词
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.any { it.pinyin == "shi" })

        // BS1: UNDO_COMMIT — 恢复 "23744"，未调用 sendToRime
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23744", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.any { it.pinyin == "ce" })

        // 模拟 UI 层行为：sendToRime 后 RIME 接收 "23744"
        ctrl.sendToRime()

        // BS2-6: 5 次 DELETED → 逐步清空
        for (expected in listOf("2374", "237", "23", "2", "")) {
            assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
            assertEquals(expected, ctrl.inputBuffer)
        }
        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    // ── callback spy 验证 ──

    @Test
    fun `rightCandidate undo does not invoke onReplaceFullPinyin`() {
        val calls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { calls.add(it) })

        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        val digitCalls = calls.size // 每次 onDigitPressed 调用 sendToRime

        // 初始 log
        calls.clear()
        ctrl.onRightCandidateSelected()
        // onRightCandidateSelected 不调用 sendToRime
        assertTrue("rightCandidate should not call sendToRime", calls.isEmpty())

        // UNDO_COMMIT 也不调用 sendToRime
        ctrl.delete()
        assertTrue("rightCandidate undo should not call sendToRime", calls.isEmpty())

        // 显式调用 sendToRime 后才触发
        ctrl.sendToRime()
        assertEquals(1, calls.size)
        assertEquals("23744", calls[0])
    }

    // ── clearRimeAndResend ──

    @Test
    fun `clearRimeAndResend calls onRightCommitUndone then clears composition and resends full input`() {
        val calls = mutableListOf<String>()
        var undoCount = 0
        val ctrl = T9InputController(
            onReplaceFullPinyin = { calls.add(it) },
            onRightCommitUndone = { undoCount += it }
        )

        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        calls.clear()

        ctrl.clearRimeAndResend()
        assertEquals(1, undoCount)
        assertEquals(2, calls.size)
        assertEquals("", calls[0])
        assertEquals("23744", calls[1])
    }

    // ── Bug 1 修复验证：右侧候选连续选词不应重复消费 inputBuffer ──
    //
    // 场景：输入 "23744" → 右侧选"策"(partial commit) → 右侧选"使"(full commit)
    // onRightCandidateSelected 第一次消费 "23" → buffer="744"
    // onRightCandidateSelected 第二次消费 "744" → buffer=""
    // 两次选词后 inputBuffer 应为空，不应出现重复消费

    @Test
    fun `bug1 - consecutive right candidate selections consume buffer correctly`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        // 第一次右侧选词：RIME partial commit 消费 "23"(ce)，剩余 "744"
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.inputBuffer)

        // 第二次右侧选词：RIME full commit 消费 "744"(shi)，剩余为空
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ── Bug 2 修复验证：左侧选词后右侧选词，inputBuffer 应正确同步 ──
    //
    // 场景：输入 "23744" → 右侧选"测"(partial commit) → 左侧选"pi" → 右侧选"披"
    // 步骤1: 输入 "23744"
    // 步骤2: 右侧选"测" → RIME 消费 "23"(ce)，inputBuffer 应为 "744"
    // 步骤3: 左侧选 "pi"(digitLength=2) → inputBuffer 应为 "pi'4"
    // 步骤4: 右侧选"披" → RIME 消费 "pi"，inputBuffer 应为 "4"
    //        左侧候选区应显示 "4" 对应的拼音选项 [g, h, i]

    @Test
    fun `bug2 - right candidate after left choice syncs buffer with remaining digits`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)

        // 右侧选"测"：消费 "23"
        ctrl.onRightCandidateSelected()
        assertEquals("744", ctrl.inputBuffer)

        // 左侧选 "pi"（digitLength=2）：确认 "pi"，剩余 "4"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        assertEquals("pi'4", ctrl.inputBuffer)

        // 右侧选"披"：RIME 消费 "pi"，剩余 "4"
        // 修复前：onRightCandidateSelected 错误地从 lastDigitSegment("4") 消费，
        //         导致 buffer 变为 "pi'" 而非 "4"
        // 修复后：应正确识别 "pi" 被消费，buffer 变为 "4"
        ctrl.onRightCandidateSelected()
        assertEquals("4", ctrl.inputBuffer)
        assertTrue("left options should contain g/h/i for digit 4",
            ctrl.firstOptions.map { it.pinyin }.containsAll(listOf("g", "h", "i")))
    }

    @Test
    fun `bug2 - right candidate after left choice with no remaining digits clears buffer`() {
        val ctrl = createController()
        for (d in listOf("2", "3", "7", "4")) ctrl.onDigitPressed(d)

        // 右侧选词：消费 "23"
        ctrl.onRightCandidateSelected()
        assertEquals("74", ctrl.inputBuffer)

        // 左侧选 "qi"（digitLength=2）：确认 "qi"，剩余为空
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("qi", 2))
        assertEquals("qi", ctrl.inputBuffer)

        // 右侧选词：RIME 消费 "qi"，buffer 清空
        ctrl.onRightCandidateSelected()
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ── 场景6：右侧候选 + 左侧候选混合链路退格 ──
    //
    // 操作流程：
    // 1. 输入 "23744" → buffer="23744"
    // 2. 点击"策"(右侧候选) → RIME partial commit，消费 "23"(ce)，buffer="744"
    // 3. 点击"pi"(左侧) → 消费 "74"，buffer="pi'4"
    // 4. 点击"h"(左侧) → 消费 "4"，buffer="pih"
    // 5. 退格：撤销 h → pi'4 → 删 4 → pi' → 撤销 pi → 74 → 删 4 → 7 → 删 7 → "" → 撤销 策 → 23 → 删 3 → 2 → 删 2 → ""
    // 总计：8 次退格

    @Test
    fun `scenario 6 - right candidate then left choices backspace in correct order`() {
        val ctrl = createController()
        // 步骤1: 输入 "23744"
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        assertEquals("23744", ctrl.inputBuffer)

        // 步骤2: 点击"策"(右侧候选) → 消费 "23"，剩余 "744"
        ctrl.onRightCandidateSelected("ce")
        assertEquals("744", ctrl.inputBuffer)

        // 步骤3: 点击"pi"(左侧) → 消费 "74"，剩余 "4"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        assertEquals("pi'4", ctrl.inputBuffer)

        // 步骤4: 点击"h"(左侧) → 消费 "4"，无剩余
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.inputBuffer)

        // 步骤5: 退格流程（8次）
        // BS1: 撤销 h (UNDO_CHOICE) → "pi'4"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("pi'4", ctrl.inputBuffer)

        // BS2: 删除数字 "4" (DELETED) → "pi'"，左侧应显示 pi 对应数字段 74 的候选
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("pi'", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.map { it.pinyin }.containsAll(listOf("pi", "qi", "p", "q", "r", "s")))

        // BS3: 撤销 pi (UNDO_CHOICE) → "74"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("74", ctrl.inputBuffer)

        // BS4: 删除数字 "4" (DELETED) → "7"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("7", ctrl.inputBuffer)

        // BS5: 删除数字 "7" (DELETED) → ""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)

        // BS6: 撤销 策 (UNDO_COMMIT) → "23"（只恢复消费的数字，不是全量 prevBuffer）
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23", ctrl.inputBuffer)

        // BS7: 删除数字 "3" (DELETED) → "2"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("2", ctrl.inputBuffer)

        // BS8: 删除数字 "2" (DELETED) → ""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `scenario 6 - undo RightCommit only restores consumed digits not full prevBuffer`() {
        val ctrl = createController()
        // 输入 "23744"
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)

        // 右侧选"策"：消费 "23"，剩余 "744"
        ctrl.onRightCandidateSelected("ce")
        assertEquals("744", ctrl.inputBuffer)

        // 继续输入数字 "4"（模拟用户继续输入）
        ctrl.onDigitPressed("4")
        assertEquals("7444", ctrl.inputBuffer)

        // 退格：删除 "4" → "744"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("744", ctrl.inputBuffer)

        // 继续删除 "4" → "74"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("74", ctrl.inputBuffer)

        // 继续删除 "4" → "7"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("7", ctrl.inputBuffer)

        // 继续删除 "7" → ""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)

        // 撤销 策：只恢复 "23"，不是 "23744"
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23", ctrl.inputBuffer)
    }

    @Test
    fun `scenario 6 - BS5 sends CLEAR_COMPOSITION_ONLY not empty string when RightCommit pending`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 "23744" → 右侧选"策" → 选"pi" → 选"h"
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        rimeCalls.clear()

        // BS1-4: undo h, delete 4, undo pi, delete 4
        ctrl.delete() // undo h
        ctrl.delete() // delete 4
        ctrl.delete() // undo pi
        ctrl.delete() // delete 4 → buffer = "7"
        rimeCalls.clear()

        // BS5: 删除数字 "7" → buffer 为空，但有 RightCommit 未撤销
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)

        // sendToRime 应发送 CLEAR_COMPOSITION_ONLY 而非空串
        // 这样服务层不会清空 t9PartialCommitTexts，预编辑文本仍显示"策"
        assertTrue(
            "Expected CLEAR_COMPOSITION_ONLY in $rimeCalls",
            rimeCalls.contains(T9InputController.CLEAR_COMPOSITION_ONLY)
        )
        assertFalse(
            "Should NOT send empty string when RightCommit is pending",
            rimeCalls.contains("")
        )
    }

    @Test
    fun `scenario 6 - after undo RightCommit sendToRime clears all when no pending commit`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 输入 "23744" → 右侧选"策" → 选"pi" → 选"h"
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))

        // 完整8次退格到空buffer
        repeat(5) { ctrl.delete() } // BS1-5: undo h, delete 4, undo pi, delete 4, delete 7
        assertEquals("", ctrl.inputBuffer)

        // BS6: 撤销 RightCommit → buffer = "23"
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23", ctrl.inputBuffer)

        // BS7-8: 删除 3, 2
        ctrl.delete() // delete 3
        ctrl.delete() // delete 2
        assertEquals("", ctrl.inputBuffer)

        // 此时 undoHistory 为空，clearRimeAndResend 发送空串仅清除 composition
        rimeCalls.clear()
        ctrl.clearRimeAndResend()
        assertTrue(
            "clearRimeAndResend should send empty string to clear composition",
            rimeCalls.contains("")
        )
    }

    // ── 场景 4.1：简拼+全拼混合输入后右侧选"客户"应完整消费输入 ──

    @Test
    fun `scenario 4_1 - right candidate kehu consumes the whole k'ge buffer`() {
        val ctrl = createController()

        // 模拟场景4步骤1-3后控制器状态：左侧已选 k 和 ge，buffer 为 k'ge
        ctrl.inputBuffer = "k'ge"
        ctrl.updateCandidates(force = true)
        assertEquals("k'ge", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())

        // 右侧候选"客户"的拼音注释为 kehu，应完整消费 k'ge
        ctrl.onRightCandidateSelected("kehu")
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
    }

    // ── 场景 6.1：半提交后按"换行"键提交预编辑文本，再次输入不应残留 partial commit ──

    @Test
    fun `scenario 6_1 - enter after partial commit clears controller state and sends CLEAR_ALL`() {
        val rimeCalls = mutableListOf<String>()
        val ctrl = T9InputController(onReplaceFullPinyin = { rimeCalls.add(it) })

        // 1. 输入 23744 → 右侧选"策"，模拟 partial commit 后 controller 状态
        for (d in listOf("2", "3", "7", "4", "4")) ctrl.onDigitPressed(d)
        ctrl.onRightCandidateSelected("ce")
        assertEquals("744", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isNotEmpty())

        // 2. 用户按"换行"键，controller 应收到 enter 通知并彻底清空
        ctrl.onEnterCommit()
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())
        assertTrue(
            "Enter commit should notify service to clear all T9 state",
            rimeCalls.contains(T9InputController.CLEAR_ALL)
        )
    }

    // ── 场景 8：多个 RightCommit 连续撤销时 partial commit 顺序 ──

    @Test
    fun `scenario 8 - undoing later RightCommit preserves earlier partial commit`() {
        var undoCount = 0
        val ctrl = T9InputController(
            onReplaceFullPinyin = { /* no-op */ },
            onRightCommitUndone = { undoCount += it }
        )

        // 输入 546946423744（分词后等价于 jin xing ce shi）
        for (d in listOf("5", "4", "6", "9", "4", "6", "4", "2", "3", "7", "4", "4")) {
            ctrl.onDigitPressed(d)
        }

        // 右侧选"进行"：消费 "5469464"，剩余 "23744"
        ctrl.onRightCandidateSelected("jin xing")
        assertEquals("23744", ctrl.inputBuffer)

        // 右侧选"策"：消费 "23"，剩余 "744"
        ctrl.onRightCandidateSelected("ce")
        assertEquals("744", ctrl.inputBuffer)

        // 左侧选"pi" → "pi'4"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("pi", 2))
        assertEquals("pi'4", ctrl.inputBuffer)

        // 左侧选"h" → "pih"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("pih", ctrl.inputBuffer)

        // 回退顺序：h, 4, pi, 4, 7, 策, 3, 2, 进行, ...
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete()) // h → pi'4
        assertEquals("pi'4", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete()) // 4 → pi'
        assertEquals("pi'", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete()) // pi → 74
        assertEquals("74", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete()) // 4 → 7
        assertEquals("7", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete()) // 7 → ""
        assertEquals("", ctrl.inputBuffer)

        // 撤销"策"：当前数字段已空，只恢复 consumedDigits="23"，触发一次 onRightCommitUndone
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("23", ctrl.inputBuffer)
        ctrl.clearRimeAndResend()
        assertEquals(1, undoCount)

        // 继续删除 3, 2
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete()) // 3 → 2
        assertEquals("2", ctrl.inputBuffer)

        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete()) // 2 → ""
        assertEquals("", ctrl.inputBuffer)

        // 撤销"进行"：当前数字段已空，只恢复 consumedDigits="5469464"，再次触发 onRightCommitUndone
        assertEquals(T9InputController.DeleteResult.UNDO_COMMIT, ctrl.delete())
        assertEquals("5469464", ctrl.inputBuffer)
        ctrl.clearRimeAndResend()
        assertEquals(2, undoCount)
    }

    // ── 场景9：简拼左选后继续输入数字，再左选，退格顺序 ──
    //
    // 操作流程（来自 .trae/回退测试.md 场景9）：
    // 1. 输入 5 → inputBuffer="5"，左侧候选区【j,k,l】
    // 2. 左选 l → inputBuffer="l"，左侧候选区空闲态
    // 3. 输入 4 → inputBuffer="l'4"（修复后自动加分隔符，让 RIME 解析为 "l"+"4" 多音节）
    //            左侧候选区【g,h,i】
    // 4. 左选 h → inputBuffer="lh"，左侧候选区空闲态
    // 5. 退格4次：撤销h → "l'4" → 删4 → "l'" → 撤销l → "5" → 删5 → ""
    //
    // 修复前 bug：
    //   - 步骤3：inputBuffer="l4"（无分隔符），RIME 解析为单音节 "li"，
    //     lua filter 无法替换数字段，preedit 显示 "l4" 而非 "l g"
    //   - BS1：snapshot.buffer=consumedDigits="4"，撤销后 inputBuffer="4"，
    //     丢失前缀已确认拼音 "l"

    @Test
    fun `scenario 9 - left choice then digit then left choice backspace order`() {
        val ctrl = createController()

        // 步骤1: 输入 5
        ctrl.onDigitPressed("5")
        assertEquals("5", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")

        // 步骤2: 左选 l
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("l", 1))
        assertEquals("l", ctrl.inputBuffer)
        assertTrue("left panel should be idle after full pinyin choice",
            ctrl.firstOptions.isEmpty())

        // 步骤3: 输入 4（修复后自动加分隔符 "l'4"，让 RIME 解析多音节）
        ctrl.onDigitPressed("4")
        assertEquals("l'4", ctrl.inputBuffer)
        assertPinyins(ctrl, "g", "h", "i")

        // 步骤4: 左选 h
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("lh", ctrl.inputBuffer)
        assertTrue("left panel should be idle after full pinyin choice",
            ctrl.firstOptions.isEmpty())

        // 步骤5: 退格（4次）
        // BS1: 撤销 h → "l'4"（修复后：snapshot 保留完整前缀 "l"）
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("l'4", ctrl.inputBuffer)
        assertPinyins(ctrl, "g", "h", "i")

        // BS2: 删除 4 → "l'"
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("l'", ctrl.inputBuffer)
        // 以分隔符结尾：显示 l 对应数字段 5 的候选
        assertPinyins(ctrl, "j", "k", "l")

        // BS3: 撤销 l → "5"
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("5", ctrl.inputBuffer)
        assertPinyins(ctrl, "j", "k", "l")

        // BS4: 删除 5 → ""
        assertEquals(T9InputController.DeleteResult.DELETED, ctrl.delete())
        assertEquals("", ctrl.inputBuffer)
        assertTrue(ctrl.firstOptions.isEmpty())

        assertEquals(T9InputController.DeleteResult.NOT_CONSUMED, ctrl.delete())
    }

    @Test
    fun `bug3 - digit pressed after letter inserts apostrophe for multi-syllable parsing`() {
        val ctrl = createController()
        // 输入 5，选 l → "l"
        ctrl.onDigitPressed("5")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("l", 1))
        assertEquals("l", ctrl.inputBuffer)

        // 输入 4 → 应自动加分隔符 "l'4"，让 RIME 把 "l" 和 "4" 解析为两个音节
        // 修复前：inputBuffer="l4"，RIME 解析为单音节 "li"，lua filter 无法替换数字段
        ctrl.onDigitPressed("4")
        assertEquals("l'4", ctrl.inputBuffer)
        assertPinyins(ctrl, "g", "h", "i")
    }

    @Test
    fun `bug3 - left choice after letter-digit preserves prefix in snapshot`() {
        val ctrl = createController()
        // 输入 5，选 l → "l"
        ctrl.onDigitPressed("5")
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("l", 1))
        // 输入 4 → "l'4"
        ctrl.onDigitPressed("4")
        assertEquals("l'4", ctrl.inputBuffer)
        // 选 h → "lh"
        ctrl.onChoiceSelected(T9PinyinMap.SyllableOption("h", 1))
        assertEquals("lh", ctrl.inputBuffer)

        // 撤销 h → 应恢复 "l'4"，不丢失 "l"
        // 修复前：snapshot.buffer=consumedDigits="4"，撤销后 inputBuffer="4"（丢失 "l"）
        assertEquals(T9InputController.DeleteResult.UNDO_CHOICE, ctrl.delete())
        assertEquals("l'4", ctrl.inputBuffer)
    }

    @Test
    fun `bug3 - digit pressed after pure digits does not insert apostrophe`() {
        val ctrl = createController()
        // 纯数字 inputBuffer 不应加分隔符
        ctrl.onDigitPressed("5")
        ctrl.onDigitPressed("4")
        assertEquals("54", ctrl.inputBuffer)

        // 分隔符后输入数字也不应再加分隔符
        // 分词键贪婪匹配最长拼音 "ji"（digitLength=2），消费 "54" → "ji'"
        ctrl.onDigitPressed("1") // 分词键 → "ji'"
        ctrl.onDigitPressed("4")
        assertEquals("ji'4", ctrl.inputBuffer)
    }
}
