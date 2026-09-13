package com.htmlnotes.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.htmlnotes.app.databinding.ActivityEditorBinding
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简风笔记编辑器：
 * - 顶栏：返回 / 撤销重做(灰显) / 更多 / 完成
 * - 元数据栏：修改时间 | 字数 | 笔记本
 * - 正文：文字便签为富文本所见即所得（WebView），HTML 便签为代码编辑
 * - 悬浮工具栏：待办 / 清单 / 格式 / 添加
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var store: ProjectStore
    private var projectId: String? = null
    private var noteType: String = Project.TYPE_HTML
    private var pendingContent: String? = null
    private var saving = false
    private var bodyLen = 0
    private var timeText = ""

    private val enabledColor: Int by lazy {
        MaterialColors.getColor(binding.btnDone, android.R.attr.textColorPrimary)
    }
    private val disabledColor = Color.rgb(158, 158, 158)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ProjectStore(this)

        projectId = intent.getStringExtra("id")
        projectId?.let { id ->
            val p = store.get(id)
            if (p != null) {
                noteType = p.type
                binding.edTitle.setText(p.title)
                timeText = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(p.updatedAt))
                if (p.type == Project.TYPE_TEXT) loadRichContent(p.content)
                else {
                    binding.edContent.setText(p.content)
                    bodyLen = p.content.length
                }
            }
        } ?: run {
            noteType = intent.getStringExtra("type") ?: Project.TYPE_HTML
            timeText = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date())
            if (noteType == Project.TYPE_TEXT) loadRichContent("")
        }
        applyTypeStyle()
        updateMeta()

        // ---- 顶栏交互 ----
        binding.btnBack.setOnClickListener { saveAndExit() }
        binding.btnUndo.setOnClickListener {
            if (noteType == Project.TYPE_TEXT) exec("undo")
        }
        binding.btnRedo.setOnClickListener {
            if (noteType == Project.TYPE_TEXT) exec("redo")
        }
        binding.btnDone.setOnClickListener { doDone() }
        binding.btnMore.setOnClickListener { showMoreMenu() }

        // ---- 标题 / 字数 ----
        binding.edTitle.doAfterTextChanged { updateMeta() }

        // ---- 悬浮工具栏 ----
        binding.btnTodo.setOnClickListener { insertText("☐ ") }
        binding.btnCheck.setOnClickListener { insertText("☑ ") }
        binding.btnFormat.setOnClickListener { showFormatSheet() }
        binding.btnAdd.setOnClickListener { toast(R.string.insert_placeholder) }

        // 收起键盘后保留光标（标题 / 代码框 / 富文本）
        CursorKeeper.attach(this, binding.edTitle, binding.edContent, binding.richWeb)
    }

    // ---------------- 富文本编辑器（文字便签） ----------------

    private fun setupRichWeb(content: String) {
        with(binding.richWeb.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }
        binding.richWeb.addJavascriptInterface(RichBridge(), "HtmlNotes")
        binding.richWeb.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                binding.richWeb.evaluateJavascript(
                    "var ed=document.getElementById('ed');" +
                        "function refreshState(){" +
                        "HtmlNotes.onState(document.queryCommandEnabled('undo')," +
                        "document.queryCommandEnabled('redo'),ed.innerText.length);}" +
                        "ed.addEventListener('input',refreshState);" +
                        "ed.addEventListener('keyup',refreshState);" +
                        "document.execCommand('styleWithCSS', false, true);" +
                        "document.getElementById('ed').innerHTML = ${JSONObject.quote(content)};" +
                        "refreshState();",
                    null
                )
            }
        }
    }

    private fun loadRichContent(content: String) {
        setupRichWeb(content)
        binding.richWeb.loadDataWithBaseURL(
            null,
            editorHtml(),
            "text/html",
            "UTF-8",
            null
        )
        // 打开即编辑：自动聚焦正文并弹出键盘
        binding.richWeb.postDelayed({
            binding.richWeb.evaluateJavascript(
                "var ed=document.getElementById('ed');ed.focus();" +
                    "var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);" +
                    "var s=getSelection();s.removeAllRanges();s.addRange(r);",
                null
            )
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.richWeb, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 350)
    }

    /** 富文本编辑页面外壳，颜色跟随深浅色主题。 */
    private fun editorHtml(): String {
        val bg = toHex(ContextCompat.getColor(this, R.color.md_surface))
        val fg = toHex(ContextCompat.getColor(this, R.color.md_on_surface))
        val accent = toHex(ContextCompat.getColor(this, R.color.md_primary))
        val sub = toHex(ContextCompat.getColor(this, R.color.md_on_surface_variant))
        val quoteBg = toHex(ContextCompat.getColor(this, R.color.md_surface_variant))
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<style>" +
            "html,body{margin:0;padding:0;min-height:100%;}" +
            "body{background:$bg;color:$fg;}" +
            "#ed{outline:none;min-height:100%;padding:16px 20px;" +
            "font-family:-apple-system,'Segoe UI',Roboto,sans-serif;font-size:17px;line-height:1.7;word-break:break-word;}" +
            "#ed h1,#ed h2,#ed h3{margin:.6em 0 .4em;line-height:1.3;}" +
            "#ed blockquote{margin:.5em 0;padding:.5em .9em;border-left:3px solid $accent;" +
            "color:$sub;background:$quoteBg;border-radius:6px;}" +
            "#ed ul,#ed ol{padding-left:1.5em;margin:.4em 0;}" +
            "#ed a{color:$accent;}" +
            "#ed hr{border:none;border-top:1px solid $sub;margin:1em 0;}" +
            "#ed:empty::before{content:'写点什么…';color:$sub;}" +
            "</style></head><body><div id=\"ed\" contenteditable=\"true\"></div></body></html>"
    }

    private fun toHex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

    /** 执行排版命令，执行后刷新撤销/重做状态。 */
    private fun exec(cmd: String, value: String? = null) {
        binding.richWeb.requestFocus()
        val js = if (value == null) "document.execCommand('$cmd');refreshState();"
        else "document.execCommand('$cmd', false, ${JSONObject.quote(value)});refreshState();"
        binding.richWeb.evaluateJavascript(js, null)
    }

    /** 在光标处插入文本（待办等）。 */
    private fun insertText(text: String) {
        if (noteType == Project.TYPE_TEXT) {
            exec("insertText", text)
        } else {
            val cur = binding.edContent.text.toString()
            binding.edContent.setText(cur + if (cur.isEmpty()) text else "\n$text")
            binding.edContent.setSelection(binding.edContent.text.length)
        }
    }

    /** 打开格式设置底部弹窗。 */
    private fun showFormatSheet() {
        if (noteType != Project.TYPE_TEXT) return
        val sheet = FormatSheet(this) { cmd, value -> exec(cmd, value) }
        sheet.show()
    }

    // ---------------- 顶部撤销/重做状态 ----------------

    private inner class RichBridge {
        @JavascriptInterface
        fun onState(undo: Boolean, redo: Boolean, len: Int) {
            runOnUiThread {
                bodyLen = len
                setUndoRedo(binding.btnUndo, undo)
                setUndoRedo(binding.btnRedo, redo)
                updateMeta()
            }
        }
    }

    private fun setUndoRedo(v: TextView, enabled: Boolean) {
        v.setTextColor(if (enabled) enabledColor else disabledColor)
        v.isClickable = enabled
        v.isEnabled = enabled
    }

    // ---------------- 元数据 / 类型样式 ----------------

    private fun updateMeta() {
        val count = bodyLen + (binding.edTitle.text?.length ?: 0)
        binding.metaInfo.text =
            getString(R.string.meta_format, timeText, count, getString(R.string.notebook_default))
    }

    private fun applyTypeStyle() {
        val isText = noteType == Project.TYPE_TEXT
        if (isText) {
            binding.richWeb.visibility = View.VISIBLE
            binding.edContent.visibility = View.GONE
            binding.floatToolbar.visibility = View.VISIBLE
            setUndoRedo(binding.btnUndo, false)
            setUndoRedo(binding.btnRedo, false)
        } else {
            binding.richWeb.visibility = View.GONE
            binding.edContent.visibility = View.VISIBLE
            binding.floatToolbar.visibility = View.GONE
            setUndoRedo(binding.btnUndo, false)
            setUndoRedo(binding.btnRedo, false)
            binding.edContent.typeface = android.graphics.Typeface.MONOSPACE
            binding.edContent.textSize = 13f
            binding.edContent.doAfterTextChanged {
                bodyLen = it?.length ?: 0
                updateMeta()
            }
        }
    }

    // ---------------- 保存 / 更多菜单 ----------------

    private fun defaultTitle() =
        if (noteType == Project.TYPE_TEXT) getString(R.string.unnamed_text) else getString(R.string.unnamed_html)

    private fun collectContent(onDone: (String?) -> Unit) {
        if (noteType == Project.TYPE_TEXT) {
            binding.richWeb.evaluateJavascript(
                "(function(){ return document.getElementById('ed').innerHTML; })()"
            ) { raw ->
                val html = try {
                    JSONTokener(raw).nextValue() as String
                } catch (e: Exception) {
                    raw
                }
                onDone(html)
            }
        } else {
            onDone(binding.edContent.text.toString())
        }
    }

    /** 智能标题：标题为空时取正文首个非空行前 20 字。 */
    private fun smartTitle(content: String): String {
        val plain = if (noteType == Project.TYPE_TEXT) htmlToPlain(content) else content
        val first = plain.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.isEmpty()) defaultTitle() else first.take(20)
    }

    private fun persist(title: String, content: String): String? {
        val finalTitle = title.ifBlank { smartTitle(content) }
        return if (projectId == null) {
            store.create(finalTitle, content, noteType).id
        } else {
            store.get(projectId!!)?.let { p ->
                p.title = finalTitle
                p.content = content
                store.save(p)
                p.id
            }
        }
    }

    /** 返回（顶栏箭头或系统返回键）：自动保存；完全空白的新便签不保存。 */
    private fun saveAndExit() {
        if (saving) return
        saving = true
        collectContent { content ->
            val text = content.orEmpty()
            val plain = if (noteType == Project.TYPE_TEXT) htmlToPlain(text) else text
            val empty = plain.isBlank() && binding.edTitle.text.isNullOrBlank()
            // 编辑已有便签时即使清空也保存（尊重用户的删除意图）；新建便签空白则丢弃
            if (!empty || projectId != null) {
                persist(binding.edTitle.text.toString(), text)
            }
            saving = false
            finish()
        }
    }

    override fun onBackPressed() {
        saveAndExit()
    }

    /** 点击「完成」：保存并返回。 */
    private fun doDone() {
        saveAndExit()
    }

    private fun showMoreMenu() {
        val options = arrayOf(
            getString(R.string.btn_preview),
            getString(R.string.share),
            getString(R.string.delete)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> previewNote()
                    1 -> shareNote()
                    2 -> confirmDelete()
                }
            }
            .show()
    }

    private fun previewNote() {
        if (saving) return
        saving = true
        collectContent { content ->
            saving = false
            if (content != null) {
                val id = persist(binding.edTitle.text.toString(), content) ?: return@collectContent
                startActivity(Intent(this, PreviewActivity::class.java).putExtra("id", id))
            }
        }
    }

    private fun shareNote() {
        collectContent { content ->
            val title = binding.edTitle.text.toString().ifBlank { defaultTitle() }
            val text = if (noteType == Project.TYPE_TEXT)
                htmlToPlain(content.orEmpty()) else content.orEmpty()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    private fun htmlToPlain(html: String): String = html
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun confirmDelete() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, binding.edTitle.text.ifBlank { defaultTitle() }))
            .setPositiveButton(R.string.delete) { _, _ ->
                projectId?.let { store.delete(it) }
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 工具 ----------------

    private fun toast(res: Int) =
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
}
