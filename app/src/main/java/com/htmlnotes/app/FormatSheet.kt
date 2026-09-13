package com.htmlnotes.app

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.htmlnotes.app.databinding.SheetFormatBinding

/**
 * 格式设置底部弹窗（Bottom Sheet）：
 * - 一级分类分段控件（标题/副标题/小标题/正文/注释）
 * - 二级文本样式工具栏（粗体/斜体/删除线/高亮/字体颜色/波浪线）
 * - 三级段落排版工具栏（列表/缩进/对齐）
 * - 底部字体控制栏（分割线/系统字体/字号/彩虹取色）
 * 所有排版动作通过 [onCommand] 回调交给编辑器执行。
 */
class FormatSheet(
    private val context: Context,
    private val onCommand: (cmd: String, value: String?) -> Unit
) {

    private val dialog = BottomSheetDialog(context)
    private val binding = SheetFormatBinding.inflate(LayoutInflater.from(context))
    private val segments: List<TextView>

    init {
        dialog.setContentView(binding.root)
        // 点击遮罩或 X 关闭
        dialog.setCanceledOnTouchOutside(true)
        binding.btnClose.setOnClickListener { dialog.dismiss() }

        segments = listOf(
            binding.segTitle, binding.segSubtitle,
            binding.segHeading, binding.segBody, binding.segCaption
        )

        setupSegments()
        setupStyleRow()
        setupParagraphRow()
        setupFontBar()
    }

    fun show() = dialog.show()

    // ---------------- 一级：分段控件 ----------------

    private fun setupSegments() {
        // 默认选中「标题」
        binding.segTitle.isSelected = true

        val actions = listOf(
            Triple(binding.segTitle, "formatBlock", "<h1>"),
            Triple(binding.segSubtitle, "formatBlock", "<h2>"),
            Triple(binding.segHeading, "formatBlock", "<h3>"),
            Triple(binding.segBody, "formatBlock", "<p>"),
            Triple(binding.segCaption, "fontSize", "2")
        )
        actions.forEach { (tv, cmd, value) ->
            tv.setOnClickListener {
                selectSegment(tv)
                onCommand(cmd, value)
            }
        }
    }

    private fun selectSegment(selected: TextView) {
        segments.forEach {
            it.isSelected = it === selected
            it.typeface = if (it.isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    // ---------------- 二级：文本样式 ----------------

    private fun setupStyleRow() {
        // B 粗体：当前呈灰色不可用状态
        binding.btnBold.isEnabled = false

        binding.btnItalic.setOnClickListener {
            toggle(binding.btnItalic)
            onCommand("italic", null)
        }
        binding.btnStrike.setOnClickListener {
            toggle(binding.btnStrike)
            onCommand("strikeThrough", null)
        }
        binding.btnHl.setOnClickListener {
            toggle(binding.btnHl)
            onCommand("hiliteColor", "#FFEB3B")
        }
        binding.btnFg.setOnClickListener {
            toggle(binding.btnFg)
            onCommand("foreColor", "#1E88E5")
        }
        binding.btnWavy.setOnClickListener {
            toggle(binding.btnWavy)
            onCommand("foreColor", "#43A047")
            onCommand("underline", null)
        }
    }

    // ---------------- 三级：段落排版 ----------------

    private fun setupParagraphRow() {
        binding.btnUl.setOnClickListener {
            toggle(binding.btnUl)
            onCommand("insertUnorderedList", null)
        }
        binding.btnOl.setOnClickListener {
            toggle(binding.btnOl)
            onCommand("insertOrderedList", null)
        }
        binding.btnOutdent.setOnClickListener { onCommand("outdent", null) }
        binding.btnIndent.setOnClickListener { onCommand("indent", null) }
        binding.btnAlignLeft.setOnClickListener {
            toggle(binding.btnAlignLeft)
            onCommand("justifyLeft", null)
        }
        binding.btnAlignRight.setOnClickListener {
            toggle(binding.btnAlignRight)
            onCommand("justifyRight", null)
        }
    }

    // ---------------- 底部：字体控制 ----------------

    private fun setupFontBar() {
        binding.btnHr.setOnClickListener { onCommand("insertHorizontalRule", null) }

        binding.btnFont.setOnClickListener {
            binding.btnFont.isSelected = !binding.btnFont.isSelected
            binding.btnFont.text = context.getString(
                if (binding.btnFont.isSelected) R.string.font_system_on else R.string.font_system
            )
        }

        binding.btnSize.setOnClickListener { pickSize() }
        binding.btnRainbow.setOnClickListener { pickColor() }
    }

    private fun pickSize() {
        val sizes = intArrayOf(16, 20, 24, 28, 32)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.font_size)
            .setItems(sizes.map { it.toString() }.toTypedArray()) { _, which ->
                binding.btnSize.text = sizes[which].toString()
                onCommand("fontSize", fontSizeCmd(sizes[which]))
            }
            .show()
    }

    private fun fontSizeCmd(size: Int): String = when (size) {
        16 -> "3"
        20 -> "4"
        24 -> "5"
        28 -> "6"
        else -> "7"
    }

    private fun pickColor() {
        val colors = arrayOf(
            "#1B1B20", "#757575", "#E53935", "#FB8C00",
            "#FDD835", "#43A047", "#1E88E5", "#8E24AA"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.font_color)
            .setItems(colors) { _, which -> onCommand("foreColor", colors[which]) }
            .show()
    }

    private fun toggle(v: View) {
        v.isSelected = !v.isSelected
    }
}
