package com.htmlnotes.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.appcompat.widget.AppCompatEditText

/**
 * 简易代码编辑器：在原生 EditText 基础上叠加
 *  - 行号栏 + 当前行高亮
 *  - Tab / Shift+Tab 缩进（2 空格，支持多行选区）
 *  - 换行自动继承上一行缩进，上一行以 { 结尾时多缩进一级（软/硬键盘均生效）
 *  - 自带撤销/重做历史栈（连续输入自动合并），并通过 [onHistoryChanged] 驱动按钮状态
 */
class CodeEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    /** 行号字号比例（相对正文 textSize），默认 1.0 = 与正文一致 */
    var lineNumberScale = 1.0f
        set(value) {
            field = value
            updateLinePaint()
            postInvalidate()
        }

    /** 行号是否显示 */
    var showLineNumbers = true
        set(value) {
            field = value
            updateGutterPadding()
            postInvalidate()
        }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSize
        color = LINE_NUMBER_COLOR
    }
    private val gutterPaint = Paint().apply { color = GUTTER_LIGHT }
    private val gutterDividerPaint = Paint().apply {
        color = GUTTER_DIVIDER_LIGHT
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val currentLinePaint = Paint().apply { color = CURRENT_LINE_LIGHT }
    private val leftPadPx = 6f * resources.displayMetrics.density
    private val rightPadPx = 6f * resources.displayMetrics.density
    private var gutterWidthPx = 32f * resources.displayMetrics.density

    /** gutter 宽度当前对应的行号位数，位数没变时跳过多余的重算 */
    private var gutterDigits = 0

    /** 已生效的 paddingLeft，用于跳过重复 setPadding（其内部会 requestLayout） */
    private var appliedPaddingLeft = -1
    private var basePaddingLeft = 0

    // ---------------- 撤销 / 重做历史 ----------------

    private data class Snapshot(val text: String, val selStart: Int, val selEnd: Int)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var historyTracking = false
    private var isRestoring = false
    private var lastChangeAt = 0L
    private var lastRecordSimple = false
    private val coalesceWindowMs = 700L

    /** 撤销/重做可用状态变化时回调（主线程）。 */
    var onHistoryChanged: (() -> Unit)? = null

    /** 载入初始内容后调用：清空历史并开始记录。 */
    fun resetHistory() {
        undoStack.clear()
        redoStack.clear()
        lastChangeAt = 0L
        lastRecordSimple = false
        historyTracking = true
        onHistoryChanged?.invoke()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(currentSnapshot())
        restoreSnapshot(prev)
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(currentSnapshot())
        restoreSnapshot(next)
        return true
    }

    private fun currentSnapshot() =
        Snapshot(text?.toString().orEmpty(), selectionStart, selectionEnd)

    private fun restoreSnapshot(s: Snapshot) {
        isRestoring = true
        setText(s.text)
        val len = s.text.length
        setSelection(s.selStart.coerceIn(0, len), s.selEnd.coerceIn(0, len))
        isRestoring = false
        onHistoryChanged?.invoke()
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (isRestoring || !historyTracking || s == null) return
            val now = SystemClock.uptimeMillis()
            // 连续的单字符输入/删除合并为一条历史，避免撤销时逐字回退
            val simpleEdit = count <= 1 && after <= 1
            val canCoalesce = simpleEdit && lastRecordSimple && undoStack.isNotEmpty() &&
                now - lastChangeAt < coalesceWindowMs
            if (!canCoalesce) {
                undoStack.addLast(Snapshot(s.toString(), selectionStart, selectionEnd))
                if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
                redoStack.clear()
            }
            lastChangeAt = now
            lastRecordSimple = simpleEdit
            onHistoryChanged?.invoke()
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            updateLinePaint()
            postInvalidate()
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    init {
        // 给左侧 padding 留出 gutter 宽度
        basePaddingLeft = paddingLeft
        updateGutterPadding()
        applyThemeColors()
        addTextChangedListener(textWatcher)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateLinePaint()
    }

    override fun setTextSize(size: Float) {
        super.setTextSize(size)
        updateLinePaint()
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        updateLinePaint()
    }

    private fun updateLinePaint() {
        val paintSize = textSize * lineNumberScale
        if (linePaint.textSize != paintSize) {
            linePaint.textSize = paintSize
            gutterDigits = 0 // 字号变化会改变数字宽度，强制重算 gutter
        }
        // gutter 宽度自适应：按最大行号位数估算（左 6dp + 数字 + 右 6dp）。
        // 位数没变就不必重新测量，省掉每次输入都要付出的 measureText + setPadding 代价。
        val digits = lineCount.coerceAtLeast(1).toString().length.coerceAtLeast(2)
        if (digits == gutterDigits) return
        gutterDigits = digits
        gutterWidthPx = leftPadPx + linePaint.measureText("8".repeat(digits)) + rightPadPx
        updateGutterPadding()
    }

    private fun updateGutterPadding() {
        val left = if (showLineNumbers) (basePaddingLeft + gutterWidthPx.toInt()) else basePaddingLeft
        // setPadding 会触发 requestLayout；输入时 gutter 宽度通常不变，跳过它可省掉整棵视图树的重新布局
        if (left == appliedPaddingLeft) return
        appliedPaddingLeft = left
        setPadding(left, paddingTop, paddingRight, paddingBottom)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyThemeColors()
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun applyThemeColors() {
        val night = isNight()
        linePaint.color = LINE_NUMBER_COLOR
        gutterPaint.color = if (night) GUTTER_NIGHT else GUTTER_LIGHT
        gutterDividerPaint.color = if (night) GUTTER_DIVIDER_NIGHT else GUTTER_DIVIDER_LIGHT
        currentLinePaint.color = if (night) CURRENT_LINE_NIGHT else CURRENT_LINE_LIGHT
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null) {
            val sx = scrollX.toFloat()
            val sy = scrollY.toFloat()

            // 注意：父 ViewGroup 在调 onDraw 前已对 canvas 做了 (-scrollX, -scrollY) 平移。
            // 因此 onDraw 内的 layout 坐标 == 文本坐标，无需再平移即可与文本对齐。

            // ---- 固定元素（gutter 背景/分隔线）：反向平移回屏幕坐标 ----
            canvas.save()
            canvas.translate(sx, sy)
            // gutter 一直画到视图底部。正文底部留白会随悬浮工具栏的高度变化，
            // 若止于内容区底部，灰条会在工具栏上方提前收尾、留下一段突兀的白色断层；
            // 工具栏本身是近乎不透明的磨砂背景，会自然盖住底下这段灰条。
            canvas.drawRect(
                0f,
                paddingTop.toFloat(),
                gutterWidthPx,
                height.toFloat(),
                gutterPaint
            )
            canvas.drawLine(
                gutterWidthPx,
                paddingTop.toFloat(),
                gutterWidthPx,
                height.toFloat(),
                gutterDividerPaint
            )
            canvas.restore()

            // ---- 跟随文本的元素（layout 坐标，不再额外平移） ----
            // 裁剪到可视内容区（屏幕矩形换算到 layout 坐标），滚出灰条上/下缘的行号不显示
            canvas.save()
            canvas.clipRect(
                sx, sy + paddingTop,
                sx + width, sy + (height - paddingBottom)
            )

            val curLine = currentLineIndex()
            if (curLine in 0 until lineCount) {
                val top = layout.getLineTop(curLine) + paddingTop
                val bottom = layout.getLineBottom(curLine) + paddingTop
                // 当前行高亮：水平覆盖屏幕可视宽度（layout 坐标中为 [scrollX, scrollX+width]）
                canvas.drawRect(
                    sx, top.toFloat(),
                    sx + width.toFloat(), bottom.toFloat(),
                    currentLinePaint
                )
            }

            // 主题相关取值放在循环外：原先逐行调用 isNight() 与 Color.parseColor()，
            // 对长文件来说这是每帧数百次字符串解析，滚动时会明显掉帧。
            val activeLineColor = if (isNight()) LINE_NUMBER_ACTIVE_NIGHT else LINE_NUMBER_ACTIVE_LIGHT
            for (line in 0 until lineCount) {
                val baseline = layout.getLineBaseline(line) + paddingTop
                linePaint.color = if (line == curLine) activeLineColor else LINE_NUMBER_COLOR
                // 行号左对齐钉在 gutter 内：x = scrollX + 左 padding（drawText 的 x 是文字左边缘）
                canvas.drawText(
                    (line + 1).toString(),
                    sx + leftPadPx,
                    baseline.toFloat(),
                    linePaint
                )
            }
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // 当前行变化时触发重绘以更新高亮
        postInvalidate()
    }

    /** 返回光标所在「布局行」索引（经 layout.getLineForOffset 换算，软换行时也能精确定位） */
    private fun currentLineIndex(): Int {
        val l = layout ?: return -1
        val pos = selectionStart.coerceAtLeast(0).coerceAtMost(text?.length ?: 0)
        return l.getLineForOffset(pos)
    }

    // ---------------- 键盘输入：软键盘走 InputConnection，硬键盘走 onKeyDown ----------------

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text == "\n") {
                    // 软键盘回车：提交带自动缩进的换行
                    return super.commitText(buildAutoIndent(), newCursorPosition)
                }
                if (text == "\t") {
                    handleTab(false)
                    return true
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                // 部分输入法以按键事件形式发送回车，这里同样做自动缩进
                if (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val inserted = if (event.isShiftPressed) "\n" else buildAutoIndent()
                        super.commitText(inserted, 1)
                    }
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 硬件键盘：Ctrl+Z 撤销 / Ctrl+Shift+Z、Ctrl+Y 重做
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> {
                    if (event.isShiftPressed) redo() else undo()
                    return true
                }
                KeyEvent.KEYCODE_Y -> {
                    redo()
                    return true
                }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> {
                handleTab(event.isShiftPressed)
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                handleEnter()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 计算自动缩进换行文本：\n + 当前行前导空白，若当前行以 { 结尾则多缩进一级。 */
    private fun buildAutoIndent(): String {
        val s = text ?: return "\n"
        val pos = selectionStart.coerceAtLeast(0).coerceAtMost(s.length)
        val before = s.subSequence(0, pos).toString()
        val lineStart = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        val lineSoFar = before.substring(lineStart)
        val leading = lineSoFar.takeWhile { it == ' ' || it == '\t' }
        val extra = if (lineSoFar.trimEnd().endsWith("{")) "  " else ""
        return "\n$leading$extra"
    }

    private fun handleEnter() {
        val s = text ?: return
        val start = selectionStart
        if (start < 0) return
        val insertion = buildAutoIndent()
        s.insert(start, insertion)
        setSelection(start + insertion.length)
    }

    private fun handleTab(shift: Boolean) {
        val s = text ?: return
        val start = selectionStart
        val end = selectionEnd
        if (start < 0) return
        val indent = "  "

        if (start == end) {
            if (shift) {
                val lineStart = lineStartOf(s, start)
                val lineText = s.subSequence(lineStart, start).toString()
                val remove = lineText.takeWhile { it == ' ' }.length.coerceAtMost(2)
                if (remove > 0) {
                    s.replace(lineStart, lineStart + remove, "")
                    setSelection((start - remove).coerceAtLeast(lineStart))
                }
            } else {
                s.insert(start, indent)
                setSelection(start + indent.length)
            }
        } else {
            val selStart = minOf(start, end)
            val selEnd = maxOf(start, end)
            val lineStart = lineStartOf(s, selStart)
            val block = s.subSequence(lineStart, selEnd).toString()
            val lines = block.split("\n").toMutableList()
            val firstLineRm = if (shift) lines.first().takeWhile { it == ' ' }.length.coerceAtMost(2) else 0
            lines.forEachIndexed { idx, l ->
                if (shift) {
                    val rm = l.takeWhile { it == ' ' }.length.coerceAtMost(2)
                    if (rm > 0) lines[idx] = l.substring(rm)
                } else {
                    lines[idx] = indent + l
                }
            }
            val newBlock = lines.joinToString("\n")
            s.replace(lineStart, selEnd, newBlock)
            val lenDelta = newBlock.length - block.length
            val newSelStart = if (shift) (selStart - firstLineRm).coerceAtLeast(lineStart) else selStart + 2
            val newSelEnd = selEnd + lenDelta
            setSelection(newSelStart.coerceAtLeast(0), newSelEnd.coerceAtLeast(0))
        }
    }

    private fun lineStartOf(s: CharSequence, pos: Int): Int {
        val sub = s.subSequence(0, pos)
        val idx = sub.toString().lastIndexOf('\n')
        return if (idx < 0) 0 else idx + 1
    }

    // ---------------- 工具栏公开操作：缩进 / 格式化 / 清空 ----------------

    /** 工具栏缩进/反向缩进：与 Tab / Shift+Tab 同一套逻辑（支持多行选区）。 */
    fun indentSelection(shift: Boolean) {
        handleTab(shift)
        onHistoryChanged?.invoke()
    }

    /** 工具栏清空：清空全部内容（可撤销）。 */
    fun clearCode() {
        if (text.isNullOrEmpty()) return
        setText("")
        setSelection(0)
        onHistoryChanged?.invoke()
    }

    /**
     * 工具栏格式化：轻量 HTML 重排 —— 逐行去首尾空白，按标签开合计算缩进层级，
     * 保留空行与行内内容，自闭合标签不改变层级。
     */
    fun formatCode() {
        val s = text ?: return
        val raw = s.toString()
        if (raw.isBlank()) return
        val lines = raw.split("\n").map { it.trim() }
        val out = mutableListOf<String>()
        var level = 0
        val indentUnit = "  "
        val openTag = Regex("<(?!/)([a-zA-Z][a-zA-Z0-9]*)(\\s[^>]*)?(?<!/)>")
        val closeTag = Regex("</[a-zA-Z][a-zA-Z0-9]*>")
        val selfClose = Regex("<[a-zA-Z][a-zA-Z0-9]*[^>]*/>")
        for (line in lines) {
            if (line.isEmpty()) { out.add(""); continue }
            // 去掉自闭合标签后再计数，避免 <img/>、<br/> 影响层级
            val stripped = selfClose.replace(line, "")
            val opens = openTag.findAll(stripped).count()
            val closes = closeTag.findAll(stripped).count()
            val leadingClose = line.startsWith("</")
            val effective = if (leadingClose) (level - 1).coerceAtLeast(0) else level
            out.add(indentUnit.repeat(effective) + line)
            level = (level + opens - closes).coerceAtLeast(0)
        }
        val newText = out.joinToString("\n")
        if (newText == raw) return
        val sel = selectionStart.coerceIn(0, newText.length)
        setText(newText)
        setSelection(sel, sel.coerceAtMost(newText.length))
        onHistoryChanged?.invoke()
    }

    companion object {
        private const val MAX_HISTORY = 80

        // 配色预先解析为 ARGB 整数，避免在 onDraw 里反复 parseColor 字符串
        private val LINE_NUMBER_COLOR = 0xFF9E9E9E.toInt()
        private val LINE_NUMBER_ACTIVE_LIGHT = 0xFF333333.toInt()
        private val LINE_NUMBER_ACTIVE_NIGHT = 0xFFFFFFFF.toInt()
        private val GUTTER_LIGHT = 0x15000000
        private val GUTTER_NIGHT = 0x22FFFFFF
        private val GUTTER_DIVIDER_LIGHT = 0x1F000000
        private val GUTTER_DIVIDER_NIGHT = 0x33FFFFFF
        private val CURRENT_LINE_LIGHT = 0x0F1C1B1F
        private val CURRENT_LINE_NIGHT = 0x14FFFFFF
    }
}
