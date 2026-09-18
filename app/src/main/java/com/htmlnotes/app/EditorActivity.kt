package com.htmlnotes.app

import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.htmlnotes.app.databinding.ActivityEditorBinding
import com.htmlnotes.app.databinding.SheetMoreBinding
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 便签编辑器（工具栏交互重构版）：
 * - 顶栏：返回 / 撤销 / 重做 / 更多 / 完成
 * - 元数据栏：修改时间 | 字数
 * - 正文：文字便签为富文本（WebView），HTML 便签为带行号的代码编辑
 * - 底部悬浮工具栏（深浅色磨砂适配，键盘弹出自动避让）：
 *   · 文字模式：常驻一级（B I U S / 对齐 / 撤销重做 / 更多）+ 折叠二级面板（段落/字号/颜色/高亮/列表/引用/缩进/链接/图片/清除）
 *   · 代码模式：常驻（撤销重做 / 缩进反缩进 / 格式化 / 清空 / 代码片段）
 * - 工具状态随光标实时同步；每个操作有 toast / 按钮短暂高亮反馈
 * - 30 秒自动保存草稿，离开前台立刻保存
 */
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private lateinit var store: ProjectStore
    private var projectId: String? = null
    private var noteType: String = Project.TYPE_HTML
    private var saving = false
    /** 由首页卡片以容器变换过渡进入：跳过 overridePendingTransition，退出动画交给 exitTransition。 */
    private var containerTransition = false
    private var bodyLen = 0
    private var timeText = ""
    private var htmlDraftChanged = false
    private var richDraftChanged = false
    /** 富文本 WebView 是否已加载完成。未完成时读不到正文，此时不能写盘，否则会覆盖便签。 */
    private var richReady = false
    /** 上一次已知的键盘可见性，null 表示尚未收到过 insets 回调。 */
    private var lastImeVisible: Boolean? = null
    private var richBlock = ""
    private var richStatesJson: String? = null
    private val autoSaveIntervalMs = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoSaveTask = object : Runnable {
        override fun run() {
            autoSaveDraft()
            mainHandler.postDelayed(this, autoSaveIntervalMs)
        }
    }

    private val enabledColor: Int by lazy {
        MaterialColors.getColor(binding.btnDone, android.R.attr.textColorPrimary)
    }
    private val disabledColor = Color.rgb(158, 158, 158)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        containerTransition = NoteTransition.applyEnter(this)
        enableEdgeToEdge()
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

        // 新建 HTML 便签：聚焦标题并弹出键盘，打开即可输入（文本便签由 loadRichContent 聚焦正文）
        if (projectId == null && noteType == Project.TYPE_HTML) {
            binding.edTitle.postDelayed({
                binding.edTitle.requestFocus()
                ViewCompat.getWindowInsetsController(binding.edTitle)
                    ?.show(WindowInsetsCompat.Type.ime())
            }, 300)
        }

        // ---- 系统栏 + 键盘 insets：统一用 WindowInsets 计算底部 padding ----
        // 用 IME insets 而非 getWindowVisibleDisplayFrame（后者在 Android 15 边到边下不可靠，
        // 曾导致键盘弹出时底部 padding 失效、悬浮工具栏被键盘盖住）
        // 记录当前生效的四边 padding。View.setPadding 不比较旧值、每次都无条件 requestLayout，
        // 而 insets 的分发远比键盘动画本身频繁，不加这层判断会让整棵树多做很多次无用布局。
        var padL = -1
        var padT = -1
        var padR = -1
        var padB = -1
        fun applyInsets(insets: WindowInsetsCompat) {
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // 键盘可见性驱动工具栏形态：收起时胶囊、弹出时展开。
            // 必须放在下面的提前 return 之前 —— 否则 padding 未变时会被跳过，形态切不过来。
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible != lastImeVisible) {
                lastImeVisible = imeVisible
                updateDockMode(imeVisible)
            }

            val l = sys.left
            val t = sys.top
            val r = sys.right
            val b = maxOf(sys.bottom, ime.bottom)
            if (l == padL && t == padT && r == padR && b == padB) return
            padL = l
            padT = t
            padR = r
            padB = b
            binding.root.setPadding(l, t, r, b)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            applyInsets(insets)
            insets
        }
        // 键盘动画过程中持续跟进，内容随键盘平滑抬升/回落
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyInsets(insets)
                    return insets
                }
            }
        )

        // ---- 顶栏交互 ----
        binding.btnBack.setOnClickListener { saveAndExit() }
        // 返回键 / 返回手势：键盘显示时先收起键盘（与多数编辑器一致，工具栏也随之收回胶囊），
        // 再按一次才保存退出。Android 13+ 预测式返回同样生效。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lastImeVisible == true) hideKeyboard() else saveAndExit()
            }
        })
        binding.btnUndo.setOnClickListener { performUndo() }
        binding.btnRedo.setOnClickListener { performRedo() }
        binding.btnDone.setOnClickListener { doDone() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.btnPreview.setOnClickListener { previewNote() }
        // 胶囊本身就是"开始编辑"的入口：点它唤起键盘，工具栏随之展开
        binding.capsuleText.setOnClickListener { focusBody() }
        binding.capsuleCode.setOnClickListener { focusBody() }

        // 胶囊↔完整条、面板展开/收起统一走柔和过渡：渐显 + 轻微上浮。
        // 注意：LayoutTransition 只拦截「直接子 View」的显隐/布局变化——
        // 面板（panelStyle/panelFormat/panelCode）挂在 fullText/fullCode 之下，
        // 所以工具栏容器负责胶囊↔完整条的形态过渡（220ms，跟手），full 容器负责
        // 面板子项的渐显渐隐（280ms，展开更有质感），两者各挂一份，缺一不可，
        // 否则面板会「只占位不显示」（alpha 停在 0）。
        val dockTransition = { LayoutTransition().apply {
            setDuration(220)
            val decel = DecelerateInterpolator()
            setInterpolator(LayoutTransition.CHANGE_APPEARING, decel)
            setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, decel)
            setInterpolator(LayoutTransition.CHANGING, decel)
            setAnimator(
                LayoutTransition.APPEARING,
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(null, View.ALPHA, 0f, 1f),
                        ObjectAnimator.ofFloat(null, View.TRANSLATION_Y, dp(12).toFloat(), 0f)
                    )
                }
            )
            setAnimator(
                LayoutTransition.DISAPPEARING,
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(null, View.ALPHA, 1f, 0f),
                        ObjectAnimator.ofFloat(null, View.TRANSLATION_Y, 0f, dp(8).toFloat())
                    )
                }
            )
        } }
        listOf(binding.toolbarText, binding.toolbarCode).forEach { dock ->
            dock.layoutTransition = dockTransition()
        }
        // 面板的直接父容器：只处理面板项的渐显/渐隐（280ms 更有展开质感），
        // CHANGING 交给外层容器统一播放，避免面板内部按钮状态变化触发多余的布局抖动。
        listOf(binding.fullText, binding.fullCode).forEach { host ->
            host.layoutTransition = LayoutTransition().apply {
                setDuration(280)
                val decel = DecelerateInterpolator()
                setAnimator(
                    LayoutTransition.APPEARING,
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(null, View.ALPHA, 0f, 1f),
                            ObjectAnimator.ofFloat(null, View.TRANSLATION_Y, dp(12).toFloat(), 0f)
                        )
                    }
                )
                setAnimator(
                    LayoutTransition.DISAPPEARING,
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(null, View.ALPHA, 1f, 0f),
                            ObjectAnimator.ofFloat(null, View.TRANSLATION_Y, 0f, dp(8).toFloat())
                        )
                    }
                )
                setAnimator(LayoutTransition.CHANGING, null)
                setAnimator(LayoutTransition.CHANGE_APPEARING, null)
                setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null)
            }
        }

        // 全部可点元素附加按压缩放触感
        listOf(
            binding.btnBack, binding.btnUndo, binding.btnRedo,
            binding.btnMore, binding.btnDone, binding.btnPreview,
            binding.btnTB, binding.btnTI, binding.btnTU, binding.btnTS,
            binding.btnAlignL, binding.btnAlignC, binding.btnAlignR,
            binding.btnPanelStyle, binding.btnPanelFormat,
            binding.segTitle, binding.segSubtitle, binding.segHeading,
            binding.segBody, binding.segCaption,
            binding.btnSize, binding.btnColor, binding.btnHl, binding.btnFont,
            binding.btnHr, binding.btnUl, binding.btnOl, binding.btnQuote,
            binding.btnOutdent, binding.btnIndent, binding.btnCode,
            binding.btnLink, binding.btnImage, binding.btnClear,
            binding.capsuleText, binding.capsuleCode,
            binding.btnCIndent,
            binding.btnCOutdent, binding.btnFormatCode, binding.btnClearCode,
            binding.btnMoreCode,
            binding.btnSnLink, binding.btnSnImage, binding.btnSnUl,
            binding.btnSnHr, binding.btnSnTodo, binding.btnSnCheck, binding.btnCopyAll
        ).forEach { it.attachPressScale(0.92f, 90L) }

        binding.edTitle.doAfterTextChanged {
            markDirty()
            updateMeta()
        }

        // ---- 文字模式工具栏 ----
        binding.btnTB.setOnClickListener { exec("bold", null); toast(R.string.toast_bold) }
        binding.btnTI.setOnClickListener { exec("italic", null); toast(R.string.toast_italic) }
        binding.btnTU.setOnClickListener { exec("underline", null); toast(R.string.toast_underline) }
        binding.btnTS.setOnClickListener { exec("strikeThrough", null); toast(R.string.toast_strike) }
        // S 按钮以删除线呈现，一眼看懂用途
        binding.btnTS.paintFlags = binding.btnTS.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        binding.btnAlignL.setOnClickListener { exec("justifyLeft", null); toast(R.string.toast_align) }
        binding.btnAlignC.setOnClickListener { exec("justifyCenter", null); toast(R.string.toast_align) }
        binding.btnAlignR.setOnClickListener { exec("justifyRight", null); toast(R.string.toast_align) }
        // 两个格式面板互斥展开
        binding.btnPanelStyle.setOnClickListener { togglePanel(binding.panelStyle, binding.btnPanelStyle) }
        binding.btnPanelFormat.setOnClickListener { togglePanel(binding.panelFormat, binding.btnPanelFormat) }

        // ---- 二级面板：段落 ----
        binding.segTitle.setOnClickListener { exec("formatBlock", "<h1>") }
        binding.segSubtitle.setOnClickListener { exec("formatBlock", "<h2>") }
        binding.segHeading.setOnClickListener { exec("formatBlock", "<h3>") }
        binding.segBody.setOnClickListener { exec("formatBlock", "<p>") }
        binding.segCaption.setOnClickListener { exec("formatBlock", "<small>") }

        // ---- 二级面板：低频格式 ----
        binding.btnSize.setOnClickListener { pickSize() }
        binding.btnColor.setOnClickListener { pickColor() }
        binding.btnHl.setOnClickListener {
            exec("hiliteColor", "#FFEB3B"); toast(R.string.toast_highlight)
        }
        binding.btnFont.setOnClickListener { cycleFontFamily() }
        binding.btnHr.setOnClickListener { exec("insertHorizontalRule", null); toast(R.string.toast_hr) }
        binding.btnUl.setOnClickListener { exec("insertUnorderedList", null) }
        binding.btnOl.setOnClickListener { exec("insertOrderedList", null) }
        binding.btnQuote.setOnClickListener { exec("formatBlock", "<blockquote>") }
        binding.btnOutdent.setOnClickListener { exec("outdent", null) }
        binding.btnIndent.setOnClickListener { exec("indent", null) }
        binding.btnCode.setOnClickListener { exec("inlineCode", null) }
        binding.btnLink.setOnClickListener { insertLink() }
        binding.btnImage.setOnClickListener { insertImage() }
        binding.btnClear.setOnClickListener { exec("removeFormat", null); toast(R.string.rich_clear) }

        // ---- 代码模式工具栏（撤销/重做只在顶栏，这里不重复） ----
        binding.btnCIndent.setOnClickListener {
            binding.edContent.indentSelection(false)
            flash(binding.btnCIndent); updateCodeUndoRedo(); markDirty()
        }
        binding.btnCOutdent.setOnClickListener {
            binding.edContent.indentSelection(true)
            flash(binding.btnCOutdent); updateCodeUndoRedo(); markDirty()
        }
        binding.btnFormatCode.setOnClickListener {
            binding.edContent.formatCode()
            flash(binding.btnFormatCode); updateCodeUndoRedo(); markDirty()
            toast(R.string.toast_format)
        }
        binding.btnClearCode.setOnClickListener { confirmClearCode() }
        binding.btnMoreCode.setOnClickListener { togglePanel(binding.panelCode, binding.btnMoreCode) }

        // ---- 代码片段面板 ----
        binding.btnSnLink.setOnClickListener {
            val sn = "<a href=\"https://\">链接文本</a>"
            insertCodeSnippet(sn, sn.indexOf("https://"), "https://".length); toast(R.string.toast_link)
        }
        binding.btnSnImage.setOnClickListener {
            val sn = "<img src=\"https://\" alt=\"\">"
            insertCodeSnippet(sn, sn.indexOf("https://"), "https://".length); toast(R.string.toast_image)
        }
        binding.btnSnUl.setOnClickListener { insertCodeSnippet("<ul>\n  <li></li>\n</ul>\n"); toast(R.string.rich_ul) }
        binding.btnSnHr.setOnClickListener { insertCodeSnippet("<hr>\n"); toast(R.string.toast_hr) }
        binding.btnSnTodo.setOnClickListener { insertCodeSnippet("☐ "); toast(R.string.todo_menu) }
        binding.btnSnCheck.setOnClickListener { insertCodeSnippet("☑ "); toast(R.string.check_list) }
        binding.btnCopyAll.setOnClickListener { copyAllCode() }

        CursorKeeper.attach(this, binding.edTitle, binding.edContent, binding.richWeb)
    }

    // ---------------- 工具栏通用：面板折叠 / 短暂高亮 / 轻量 toast ----------------

    /**
     * 面板展开/收起：显隐交给工具栏容器的 LayoutTransition 播放「渐显 + 轻微上浮」过渡，
     * 工具栏高度随之变化，正文区同步平滑让位。
     * 文字模式的两个格式面板互斥，同时展开会把工具栏撑得过高、挤压正文。
     */
    private fun togglePanel(panel: View, trigger: View) {
        val opening = panel.visibility != View.VISIBLE
        if (opening) collapseSiblingsOf(panel)
        panel.visibility = if (opening) View.VISIBLE else View.GONE
        trigger.isSelected = opening
    }

    /** 收起与 [panel] 同组的面板，保证文字模式两个格式面板只展开一个（同样带过渡动画）。 */
    private fun collapseSiblingsOf(panel: View) {
        val pair = when (panel) {
            binding.panelStyle -> binding.panelFormat to binding.btnPanelFormat
            binding.panelFormat -> binding.panelStyle to binding.btnPanelStyle
            else -> return
        }
        val (other, otherTrigger) = pair
        if (other.visibility == View.VISIBLE) {
            other.visibility = View.GONE
            otherTrigger.isSelected = false
        }
    }

    /** 代码操作反馈：按钮短暂高亮 320ms 后回落。 */
    private fun flash(v: View) {
        v.isSelected = true
        v.postDelayed({ v.isSelected = false }, 320)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 标题改动同样标记为草稿已变更，保证只改标题也会被自动保存，并显示"未保存"状态。 */
    private fun markDirty() {
        if (noteType == Project.TYPE_TEXT) richDraftChanged = true else htmlDraftChanged = true
        showSaveState(getString(R.string.unsaved), onSurfaceVariantColor, sticky = true)
    }

    /** 顶部右侧"未保存 / 已保存 ✓"状态提示。 */
    private fun showSaveState(text: String, color: Int, sticky: Boolean) {
        binding.saveState.text = text
        binding.saveState.setTextColor(color)
        binding.saveState.animate().cancel()
        binding.saveState.removeCallbacks(hideSaveState)
        binding.saveState.animate().alpha(1f).setDuration(160).start()
        if (!sticky) {
            binding.saveState.postDelayed(hideSaveState, 2400)
        }
    }

    private val hideSaveState = Runnable {
        binding.saveState.animate().alpha(0f).setDuration(420).start()
    }

    private val onSurfaceVariantColor: Int by lazy {
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    private val primaryColor: Int by lazy {
        MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(autoSaveTask)
        mainHandler.postDelayed(autoSaveTask, autoSaveIntervalMs)
    }

    override fun onPause() {
        super.onPause()
        // 离开前台立即保存一次草稿，避免被系统回收
        autoSaveDraft()
        mainHandler.removeCallbacks(autoSaveTask)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoSaveTask)
        // 富文本 WebView 注册了 @JavascriptInterface（间接持有 Activity），不销毁会泄漏。
        // 代码模式下它虽然没被使用，但同样持有资源，一并销毁。
        binding.richWeb.removeJavascriptInterface("HtmlNotes")
        binding.richWeb.stopLoading()
        binding.richWeb.webChromeClient = null
        binding.richWeb.destroy()
        super.onDestroy()
    }

    // ---------------- 富文本编辑器 ----------------

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
                        "var st=JSON.stringify({" +
                        "b:document.queryCommandState('bold')," +
                        "i:document.queryCommandState('italic')," +
                        "u:document.queryCommandState('underline')," +
                        "s:document.queryCommandState('strikeThrough')," +
                        "ul:document.queryCommandState('insertUnorderedList')," +
                        "ol:document.queryCommandState('insertOrderedList')," +
                        "jc:document.queryCommandState('justifyCenter')," +
                        "jr:document.queryCommandState('justifyRight')," +
                        "jf:document.queryCommandState('justifyFull')});" +
                        "HtmlNotes.onState(document.queryCommandEnabled('undo')," +
                        "document.queryCommandEnabled('redo')," +
                        "document.queryCommandValue('formatBlock')," +
                        "ed.innerText.length,st);}" +
                        "ed.addEventListener('input',function(){refreshState();HtmlNotes.onDirty();});" +
                        "ed.addEventListener('keyup',refreshState);" +
                        "ed.addEventListener('mouseup',refreshState);" +
                        // 光标/选区变化实时刷新工具栏状态，告别状态滞后
                        "document.addEventListener('selectionchange',refreshState);" +
                        "document.execCommand('styleWithCSS', false, false);" +
                        "document.getElementById('ed').innerHTML = ${JSONObject.quote(content)};" +
                        "refreshState();"
                ) {
                    richReady = true
                }
            }
        }
    }

    private fun loadRichContent(content: String) {
        setupRichWeb(content)
        binding.richWeb.loadDataWithBaseURL(null, NoteHtml.editorPage(NoteHtml.paletteOf(this)), "text/html", "UTF-8", null)
        binding.richWeb.postDelayed({
            binding.richWeb.evaluateJavascript(
                "var ed=document.getElementById('ed');ed.focus();" +
                    "var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);" +
                    "var s=getSelection();s.removeAllRanges();s.addRange(r);",
                null
            )
            ViewCompat.getWindowInsetsController(binding.richWeb)
                ?.show(WindowInsetsCompat.Type.ime())
        }, 350)
    }

    /**
     * 用 span 包裹当前选区（比 Range.surroundContents 更稳：跨节点选区也不会抛异常），
     * 包裹后重新选中内容，便于连续叠加样式。
     */
    private fun wrapSelectionJs(styleDeclaration: String): String =
        "var s=document.getSelection();" +
            "if(s.rangeCount>0&&!s.isCollapsed){" +
            "var r=s.getRangeAt(0);var span=document.createElement('span');" +
            "span.style.cssText=${JSONObject.quote(styleDeclaration)};" +
            "span.appendChild(r.extractContents());r.insertNode(span);" +
            "var nr=document.createRange();nr.selectNodeContents(span);" +
            "s.removeAllRanges();s.addRange(nr);}refreshState();"

    private fun exec(cmd: String, value: String? = null) {
        if (noteType == Project.TYPE_TEXT) {
            binding.richWeb.requestFocus()
            val js = when (cmd) {
                "setFontPx" -> {
                    val px = value?.toIntOrNull() ?: 17
                    wrapSelectionJs("font-size:${px}px;")
                }
                "fontFamily" -> wrapSelectionJs("font-family:${value};")
                "wavy" -> wrapSelectionJs("text-decoration:underline wavy;")
                "inlineCode" ->
                    "var s=document.getSelection();if(s.rangeCount>0&&!s.isCollapsed){" +
                        "var r=s.getRangeAt(0);var span=document.createElement('span');" +
                        "span.className='_ic';span.appendChild(r.extractContents());" +
                        "r.insertNode(span);HtmlNotes.relayoutCodes();}refreshState();"
                else -> if (value == null) "document.execCommand('$cmd');refreshState();"
                else "document.execCommand('$cmd', false, ${JSONObject.quote(value)});refreshState();"
            }
            binding.richWeb.evaluateJavascript(js, null)
        } else {
            // HTML 模式：仅撤销/重做对代码编辑器生效
            when (cmd) {
                "undo" -> binding.edContent.undo()
                "redo" -> binding.edContent.redo()
                else -> { /* 其它命令仅对富文本生效 */ }
            }
        }
    }

    /** HTML 模式：在代码编辑器光标处插入片段，可选插入后选中某段（如选中 url 方便替换）。 */
    private fun insertCodeSnippet(snippet: String, selectStart: Int = -1, selectLen: Int = 0) {
        val ed = binding.edContent
        val s = minOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        val e = maxOf(ed.selectionStart, ed.selectionEnd).coerceAtLeast(0)
        ed.text?.replace(s, e, snippet)
        if (selectStart >= 0) {
            ed.setSelection((s + selectStart).coerceAtMost(s + snippet.length),
                (s + selectStart + selectLen).coerceAtMost(s + snippet.length))
        } else {
            ed.setSelection(s + snippet.length)
        }
        markDirty()
        updateCodeUndoRedo()
    }

    private fun performUndo() {
        if (noteType == Project.TYPE_TEXT) {
            exec("undo")
        } else {
            binding.edContent.undo()
            updateCodeUndoRedo()
        }
        toast(R.string.toast_undo)
    }

    private fun performRedo() {
        if (noteType == Project.TYPE_TEXT) {
            exec("redo")
        } else {
            binding.edContent.redo()
            updateCodeUndoRedo()
        }
        toast(R.string.toast_redo)
    }

    private fun copyAllCode() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", binding.edContent.text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearCode() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_code)
            .setMessage(R.string.toast_clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                binding.edContent.clearCode()
                updateCodeUndoRedo()
                markDirty()
                toast(R.string.toast_cleared)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 二级面板：字号 / 颜色 / 字体 ----------------

    private fun pickSize() {
        val sizes = intArrayOf(14, 16, 18, 20, 24, 32)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.font_size)
            .setItems(sizes.map { "${it}sp" }.toTypedArray()) { _, which ->
                exec("setFontPx", sizes[which].toString())
                toast(getString(R.string.toast_size, sizes[which]))
            }
            .show()
    }

    private fun pickColor() {
        val colors = arrayOf(
            "#1B1B20" to "黑色",
            "#757575" to "灰色",
            "#E53935" to "红色",
            "#FB8C00" to "橙色",
            "#FDD835" to "明黄",
            "#43A047" to "绿色",
            "#1E88E5" to "蓝色",
            "#8E24AA" to "紫色"
        )
        val density = resources.displayMetrics.density
        val adapter = object : ArrayAdapter<Pair<String, String>>(this, 0, colors) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@EditorActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((20 * density).toInt(), (13 * density).toInt(),
                        (20 * density).toInt(), (13 * density).toInt())
                    val dot = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (18 * density).toInt(), (18 * density).toInt()
                        ).apply { marginEnd = (12 * density).toInt() }
                    }
                    val label = TextView(context).apply {
                        textSize = 15f
                        setTextColor(onSurfaceVariantColor)
                        layoutParams = LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                    }
                    addView(dot)
                    addView(label)
                }
                val (hex, name) = colors[position]
                ((row.getChildAt(0) as View).background as? GradientDrawable)
                    ?.setColor(Color.parseColor(hex))
                    ?: run {
                        row.getChildAt(0).background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor(hex))
                            setStroke((1 * density).toInt(), Color.parseColor("#33000000"))
                        }
                    }
                (row.getChildAt(1) as TextView).text = name
                return row
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.font_color)
            .setAdapter(adapter) { _, which -> onCommandColor(colors[which].first) }
            .show()
    }

    private fun onCommandColor(hex: String) {
        exec("foreColor", hex)
        toast(R.string.toast_color)
    }

    /** 系统默认 → 衬线 → 等宽 循环切换。 */
    private var fontCycle = 0
    private val fontStacks = arrayOf(
        "-apple-system,'Segoe UI',Roboto,sans-serif",
        "Georgia,'Songti SC','SimSun',serif",
        "Menlo,Consolas,'Courier New',monospace"
    )

    private fun cycleFontFamily() {
        fontCycle = (fontCycle + 1) % fontStacks.size
        binding.btnFont.text = resources.getStringArray(R.array.font_family_names)[fontCycle]
        exec("fontFamily", fontStacks[fontCycle])
        toast(R.string.toast_font)
    }

    // ---------------- 顶栏撤销/重做状态 ----------------

    private inner class RichBridge {
        @JavascriptInterface
        fun onState(undo: Boolean, redo: Boolean, block: String, len: Int, states: String) {
            runOnUiThread {
                bodyLen = len
                richBlock = block
                richStatesJson = states
                setUndoRedo(binding.btnUndo, undo)
                setUndoRedo(binding.btnRedo, redo)
                syncRichToolbar(block, states)
                updateMeta()
            }
        }

        @JavascriptInterface
        fun onDirty() {
            runOnUiThread {
                richDraftChanged = true
                showSaveState(getString(R.string.unsaved), onSurfaceVariantColor, sticky = true)
            }
        }

        /** 重新把 _ic span 渲染为 <code> 风格 */
        @JavascriptInterface
        fun relayoutCodes() {
            // @JavascriptInterface 运行在 WebView 的 JavaBridge 线程，而 WebView 方法必须在主线程调用
            runOnUiThread {
                binding.richWeb.evaluateJavascript(
                    "document.querySelectorAll('#ed span._ic').forEach(function(s){" +
                        "var c=document.createElement('code');c.innerHTML=s.innerHTML;" +
                        "s.parentNode.replaceChild(c,s);});",
                    null
                )
            }
        }
    }

    /** 光标处真实格式 → 底部工具栏与二级面板选中态实时同步。 */
    private fun syncRichToolbar(block: String?, statesJson: String?) {
        val normalized = block?.lowercase()?.trim()?.removePrefix("<")?.removeSuffix(">").orEmpty()
        val st = try {
            JSONObject(statesJson ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        // 一级：字符样式
        binding.btnTB.isSelected = st.optBoolean("b")
        binding.btnTI.isSelected = st.optBoolean("i")
        binding.btnTU.isSelected = st.optBoolean("u")
        binding.btnTS.isSelected = st.optBoolean("s")
        // 一级：对齐（左对齐 = 其余都不激活）
        val center = st.optBoolean("jc")
        val right = st.optBoolean("jr")
        val justify = st.optBoolean("jf")
        binding.btnAlignL.isSelected = !center && !right && !justify
        binding.btnAlignC.isSelected = center
        binding.btnAlignR.isSelected = right
        // 二级：段落层级
        val segIndex = when (normalized) {
            "h1" -> 0
            "h2" -> 1
            "h3" -> 2
            "p", "div" -> 3
            "small" -> 4
            else -> -1
        }
        val segments = listOf(
            binding.segTitle, binding.segSubtitle, binding.segHeading,
            binding.segBody, binding.segCaption
        )
        segments.forEachIndexed { i, tv ->
            tv.isSelected = i == segIndex
        }
        // 二级：列表/引用
        binding.btnUl.isSelected = st.optBoolean("ul")
        binding.btnOl.isSelected = st.optBoolean("ol")
        binding.btnQuote.isSelected = normalized == "blockquote"
    }

    private fun setUndoRedo(v: View, enabled: Boolean) {
        if (v is TextView) {
            v.setTextColor(if (enabled) enabledColor else disabledColor)
        } else {
            v.alpha = if (enabled) 1f else 0.35f
        }
        v.isClickable = enabled
        v.isEnabled = enabled
    }

    // ---------------- 元数据 / 类型样式 ----------------

    private fun updateMeta() {
        val count = bodyLen + (binding.edTitle.text?.length ?: 0)
        binding.metaTime.text = "$timeText  ·  ${count}字"
    }

    private fun applyTypeStyle() {
        val isText = noteType == Project.TYPE_TEXT
        // 初始先收成胶囊（此刻键盘还没出来）；键盘弹出后由 insets 回调自动展开
        updateDockMode(expanded = false)
        // 预览是 HTML 便签的核心操作，提到顶栏常驻；文字便签打开即编辑，不需要预览入口
        binding.btnPreview.visibility = if (isText) View.GONE else View.VISIBLE
        if (isText) {
            binding.richWeb.visibility = View.VISIBLE
            binding.edContent.visibility = View.GONE
            binding.toolbarText.visibility = View.VISIBLE
            binding.toolbarCode.visibility = View.GONE
            setUndoRedo(binding.btnUndo, false)
            setUndoRedo(binding.btnRedo, false)
        } else {
            binding.richWeb.visibility = View.GONE
            binding.edContent.visibility = View.VISIBLE
            binding.toolbarText.visibility = View.GONE
            binding.toolbarCode.visibility = View.VISIBLE
            binding.edContent.typeface = Typeface.MONOSPACE
            binding.edContent.textSize = 14f
            binding.edContent.doAfterTextChanged {
                bodyLen = it?.length ?: 0
                htmlDraftChanged = true
                showSaveState(getString(R.string.unsaved), onSurfaceVariantColor, sticky = true)
                updateMeta()
            }
            binding.edContent.onHistoryChanged = { updateCodeUndoRedo() }
            // 初始内容载入完成后清空历史，避免一进来就能撤销到空文本
            binding.edContent.resetHistory()
        }
    }

    private fun updateCodeUndoRedo() {
        setUndoRedo(binding.btnUndo, binding.edContent.canUndo())
        setUndoRedo(binding.btnRedo, binding.edContent.canRedo())
    }

    // ---------------- 保存 / 自动保存 ----------------

    private fun defaultTitle() =
        if (noteType == Project.TYPE_TEXT) getString(R.string.unnamed_text) else getString(R.string.unnamed_html)

    /**
     * 读取正文。回调收到 null 表示「读取失败」（例如 WebView 页面尚未加载完成），
     * 与「内容确实为空」的 "" 区分开：前者不能写盘，否则会用空内容覆盖已有便签。
     */
    private fun collectContent(onDone: (String?) -> Unit) {
        if (noteType == Project.TYPE_TEXT) {
            if (!richReady) {
                onDone(null)
                return
            }
            binding.richWeb.evaluateJavascript(
                "(function(){ return document.getElementById('ed').innerHTML; })()"
            ) { raw ->
                onDone(parseJsString(raw))
            }
        } else {
            onDone(binding.edContent.text.toString())
        }
    }

    /** 解析 evaluateJavascript 回传的字面量；"null" 或非法 JSON 一律视为读取失败。 */
    private fun parseJsString(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun smartTitle(content: String): String {
        val plain = if (noteType == Project.TYPE_TEXT) htmlToPlain(content) else content
        val first = plain.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (first.isEmpty()) defaultTitle() else first.take(20)
    }

    private fun persist(title: String, content: String): String? {
        val finalTitle = title.ifBlank { smartTitle(content) }
        val id = projectId ?: return store.create(finalTitle, content, noteType).id
        val existing = store.get(id)
        // 文件可能已被删除（如在设置页「清空数据」后返回）：重新落盘，不能静默丢弃内容
        if (existing == null) return store.create(finalTitle, content, noteType).id
        existing.title = finalTitle
        existing.content = content
        store.save(existing)
        return existing.id
    }

    /** 自动保存草稿：若内容空则不保存；否则更新 updatedAt，并刷新元数据栏。 */
    private fun autoSaveDraft() {
        if (noteType == Project.TYPE_TEXT) {
            if (!richDraftChanged) return
            collectContent { content ->
                if (content == null) return@collectContent
                val text = content
                if (text.isBlank() && binding.edTitle.text.isNullOrBlank()) return@collectContent
                val id = persist(binding.edTitle.text.toString(), text) ?: return@collectContent
                projectId = id
                richDraftChanged = false
                runOnUiThread {
                    timeText = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date())
                    updateMeta()
                    showSaveState(getString(R.string.saved), primaryColor, sticky = false)
                }
            }
        } else {
            if (!htmlDraftChanged) return
            val text = binding.edContent.text.toString()
            if (text.isBlank() && binding.edTitle.text.isNullOrBlank()) return
            val id = persist(binding.edTitle.text.toString(), text) ?: return
            projectId = id
            htmlDraftChanged = false
            runOnUiThread {
                timeText = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date())
                updateMeta()
                showSaveState(getString(R.string.saved), primaryColor, sticky = false)
            }
        }
    }

    private fun saveAndExit() {
        if (saving) return
        saving = true
        collectContent { content ->
            // 正文读取失败（页面尚未加载完成）时直接退出，不用空内容覆盖已有便签
            if (content != null) {
                val plain = if (noteType == Project.TYPE_TEXT) htmlToPlain(content) else content
                val empty = plain.isBlank() && binding.edTitle.text.isNullOrBlank()
                if (!empty || projectId != null) {
                    persist(binding.edTitle.text.toString(), content)
                }
            }
            saving = false
            richDraftChanged = false
            htmlDraftChanged = false
            finish()
            if (!containerTransition) overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
        }
    }

    private fun doDone() = saveAndExit()

    private fun showMoreMenu() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetMoreBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        val isText = noteType == Project.TYPE_TEXT
        sheet.itemInsert.visibility = if (isText) View.GONE else View.VISIBLE
        sheet.itemInsert.setOnClickListener {
            dialog.dismiss()
            showAddMenu()
        }
        sheet.itemShare.setOnClickListener {
            dialog.dismiss()
            shareNote()
        }
        sheet.itemDelete.setOnClickListener {
            dialog.dismiss()
            confirmDelete()
        }
        dialog.show()
    }

    private fun previewNote() {
        if (saving) return
        saving = true
        collectContent { content ->
            saving = false
            if (content != null) {
                val id = persist(binding.edTitle.text.toString(), content) ?: return@collectContent
                startActivity(Intent(this, PreviewActivity::class.java).putExtra("id", id))
                overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
            }
        }
    }

    private fun shareNote() {
        collectContent { content ->
            if (content == null) return@collectContent
            val title = binding.edTitle.text.toString().ifBlank { defaultTitle() }
            val text = if (noteType == Project.TYPE_TEXT)
                htmlToPlain(content) else content
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, binding.edTitle.text.ifBlank { defaultTitle() }))
            .setPositiveButton(R.string.delete) { _, _ ->
                projectId?.let { store.delete(it) }
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 原「更多-插入代码」入口保留：HTML 便签在更多菜单中仍可插入片段。 */
    private fun showAddMenu() {
        if (noteType == Project.TYPE_TEXT) return
        val options = arrayOf(
            getString(R.string.todo_menu),
            getString(R.string.check_list),
            getString(R.string.insert_hr),
            getString(R.string.insert_link),
            getString(R.string.insert_image),
            getString(R.string.copy_all_code)
        )
        MaterialAlertDialogBuilder(this)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> insertCodeSnippet("☐ ")
                    1 -> insertCodeSnippet("☑ ")
                    2 -> insertCodeSnippet("<hr>\n")
                    3 -> {
                        val snippet = "<a href=\"https://\">链接文本</a>"
                        insertCodeSnippet(snippet, snippet.indexOf("https://"), "https://".length)
                    }
                    4 -> {
                        val snippet = "<img src=\"https://\" alt=\"\">"
                        insertCodeSnippet(snippet, snippet.indexOf("https://"), "https://".length)
                    }
                    5 -> copyAllCode()
                }
            }
            .show()
    }

    // ---------------- 链接 / 图片插入对话框 ----------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 切换悬浮工具栏的形态。
     * 键盘收起时收成一个小胶囊，几乎不挡正文，方便阅读；键盘弹出时展开成完整工具条，
     * 此时它在键盘正上方、正文也已经让位，展开不会遮挡内容。
     */
    private fun updateDockMode(expanded: Boolean) {
        val isText = noteType == Project.TYPE_TEXT
        // 胶囊态要摘掉外层容器的磨砂背景：否则屏幕上会同时出现「一条贯穿屏幕的白底」和
        // 其内居中的胶囊，两层轮廓叠在一起。展开态再挂回去。
        val dock = if (isText) binding.toolbarText else binding.toolbarCode
        dock.background = if (expanded) {
            ContextCompat.getDrawable(this, R.drawable.bg_toolbar_dock)
        } else {
            null
        }

        if (isText) {
            binding.capsuleText.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.fullText.visibility = if (expanded) View.VISIBLE else View.GONE
        } else {
            binding.capsuleCode.visibility = if (expanded) View.GONE else View.VISIBLE
            binding.fullCode.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        // 形态切换由工具栏容器的 LayoutTransition 播放过渡（胶囊↔完整条渐变上浮/回落）
    }

    /** 收起软键盘；工具栏会随键盘收起自动收回胶囊态。 */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    /** 点击胶囊：聚焦正文并唤起键盘，工具栏随键盘一起展开。 */
    private fun focusBody() {
        if (noteType == Project.TYPE_TEXT) {
            binding.richWeb.requestFocus()
            binding.richWeb.evaluateJavascript(
                "var ed=document.getElementById('ed');if(ed)ed.focus();", null
            )
            ViewCompat.getWindowInsetsController(binding.richWeb)
                ?.show(WindowInsetsCompat.Type.ime())
        } else {
            binding.edContent.requestFocus()
            ViewCompat.getWindowInsetsController(binding.edContent)
                ?.show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun insertLink() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.link_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.insert_link)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    exec("createLink", url)
                    toast(R.string.toast_link)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun insertImage() {
        val input = TextInputEditText(this).apply {
            hint = getString(R.string.image_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.insert_image)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    exec("insertImage", url)
                    toast(R.string.toast_image)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
