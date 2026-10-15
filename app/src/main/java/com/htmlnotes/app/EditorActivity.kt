package com.htmlnotes.app

import android.animation.AnimatorSet
import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.htmlnotes.app.databinding.ActivityEditorBinding
import com.htmlnotes.app.databinding.ItemPaletteColorBinding
import com.htmlnotes.app.databinding.SheetColorPickerBinding
import com.htmlnotes.app.databinding.SheetImageActionBinding
import com.htmlnotes.app.databinding.DialogFullscreenImageBinding
import com.htmlnotes.app.databinding.SheetInsertImageBinding
import com.htmlnotes.app.databinding.SheetMoreBinding
import com.htmlnotes.app.databinding.ViewSelectionBarBinding
import android.app.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handlePickedImage(uri)
        }
    }
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

    // ---------------- 选区通道与锚定 ----------------
    // 见 docs/plans/2026-10-01-editor-interaction-design.md

    /**
     * 选区在 WebView 视口内的矩形（JS `getBoundingClientRect`，CSS px 与视图像素 1:1）。
     * `collapsed` = 只有光标、没有选中内容。
     */
    private data class SelRect(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val collapsed: Boolean
    )

    /** 写入在 WebView 的 JavaBridge 线程，读取在主线程。 */
    @Volatile
    private var selRect: SelRect? = null

    /**
     * 锚定的 armed/disarm 状态。
     *
     * 没有它的话，连续打字时每次 keystroke 都会判定「选区要掉出视野了」并把内容
     * 往上拽 —— 光标被不停拉扯，比遮挡本身还难受。滚过一次就 disarm，直到选区
     * 自己回到安全区才重新 arm。
     */
    private var anchorArmed = true
    private var selScrollAnimator: ValueAnimator? = null

    // ---------------- 选区浮条 ----------------

    private var selPopup: PopupWindow? = null
    private var selBar: ViewSelectionBarBinding? = null
    private var selBarDismissed = true
    /** 轻提示；键盘抬起时系统 Toast 会被盖住，所以编辑器内统一走它。 */
    private var tip: EditorTip? = null
    /** root 的四边 padding（由 insets 写入），浮条用它换算可用区域。 */
    private val rootPad = IntArray(4)

    /**
     * 正文最近一次快照，由 JS 在每次真正编辑后回传（见 RichBridge.onContent）。
     *
     * 保存路径原本只能靠 evaluateJavascript 异步读取正文，而 onPause 之后紧接着
     * 就是 onStop/onDestroy，onDestroy 会 destroy() 掉 WebView —— 回调再也回不来，
     * 于是「旋转屏幕、或被系统回收」会丢掉上一次自动保存之后的所有编辑（最多 30 秒）。
     * 有这份同步可取的快照，保存就不再依赖那个回调。
     *
     * @Volatile：写入发生在 WebView 的 JavaBridge 线程，读取在主线程。
     */
    @Volatile
    private var richCache: String? = null
    private var pendingReplaceImgId: String? = null
    private val autoSaveIntervalMs = 30_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoSaveTask = object : Runnable {
        override fun run() {
            autoSaveDraft()
            mainHandler.postDelayed(this, autoSaveIntervalMs)
        }
    }

    /**
     * 延时聚焦正文 / 弹键盘的两个任务。必须在 onDestroy 里撤销：
     * 它们跑在 WebView 上（focusBodyTask 还会调 evaluateJavascript，在已 destroy
     * 的 WebView 上执行是原生层崩溃），而且会让键盘在用户已经返回之后的界面上
     * 突然弹出来。
     */
    private var focusTitleTask: Runnable? = null
    private var focusBodyTask: Runnable? = null


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
            val task = Runnable {
                binding.edTitle.requestFocus()
                ViewCompat.getWindowInsetsController(binding.edTitle)
                    ?.show(WindowInsetsCompat.Type.ime())
            }
            focusTitleTask = task
            binding.edTitle.postDelayed(task, 300)
        }

        // ---- 系统栏 + 键盘 insets：工具栏平滑跟随键盘，无抖动、无闪烁 ----
        var isImeAnimating = false
        var lastInsets: WindowInsetsCompat? = null

        fun syncDockMode(insets: WindowInsetsCompat) {
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible != lastImeVisible) {
                lastImeVisible = imeVisible
                updateDockMode(imeVisible)
            }
        }

        fun applyInsets(insets: WindowInsetsCompat) {
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            syncDockMode(insets)
            val l = sys.left
            val t = sys.top
            val r = sys.right
            val b = maxOf(sys.bottom, ime.bottom)
            dockingToolbar().translationY = 0f
            if (l == rootPad[0] && t == rootPad[1] && r == rootPad[2] && b == rootPad[3]) return
            rootPad[0] = l
            rootPad[1] = t
            rootPad[2] = r
            rootPad[3] = b
            binding.root.setPadding(l, t, r, b)
            updateSelectionBar()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            lastInsets = insets
            if (!isImeAnimating) {
                applyInsets(insets)
            }
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        isImeAnimating = true
                    }
                }

                override fun onStart(
                    animation: WindowInsetsAnimationCompat,
                    bounds: WindowInsetsAnimationCompat.BoundsCompat
                ): WindowInsetsAnimationCompat.BoundsCompat {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        isImeAnimating = true
                    }
                    return bounds
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    lastInsets = insets
                    applyInsets(insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        isImeAnimating = false
                        lastInsets?.let { applyInsets(it) }
                    }
                }
            }
        )

        // ---- 顶栏交互 ----
        binding.btnBack.setOnClickListener { saveAndExit() }
        // 返回键 / 返回手势：键盘显示时先收起键盘，再次按保存退出。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (lastImeVisible == true) {
                    hideKeyboard()
                } else {
                    saveAndExit()
                }
            }
        })
        // 选区可能被遮挡时重算锚定，把用户正在改的那段字拉回视口安全区。
        binding.richWeb.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom) evaluateAnchor()
        }
        binding.btnUndo.setOnClickListener { performUndo() }
        binding.btnRedo.setOnClickListener { performRedo() }
        binding.btnDone.setOnClickListener { doDone() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
        binding.btnPreview.setOnClickListener { previewNote() }

        // 选区浮条走 PopupWindow
        setupSelectionBar()
        tip = EditorTip(binding.root) { tipBottomLimit() }

        // 全部格式按钮附加 ColorOS 触控物理微缩回弹
        listOf(
            binding.btnBack, binding.btnUndo, binding.btnRedo,
            binding.btnMore, binding.btnDone, binding.btnPreview,
            binding.btnToggleAa, binding.btnPalette, binding.btnMoreTools,
            binding.btnTB, binding.btnTI, binding.btnTU, binding.btnTS,
            binding.segTitle, binding.segSubtitle, binding.segHeading, binding.segBody,
            binding.btnTodo, binding.btnUl, binding.btnOl, binding.btnQuote,
            binding.btnAlignL, binding.btnAlignC, binding.btnAlignR,
            binding.btnColor, binding.btnHl, binding.btnSize, binding.btnFont,
            binding.btnLink, binding.btnImage, binding.btnCode, binding.btnHr,
            binding.btnOutdent, binding.btnIndent, binding.btnClear,
            binding.btnCIndent, binding.btnCOutdent, binding.btnFormatCode, binding.btnClearCode,
            binding.btnSnLink, binding.btnSnImage, binding.btnSnUl,
            binding.btnSnHr, binding.btnSnTodo, binding.btnSnCheck, binding.btnCopyAll
        ).forEach { it.applyColorOsPressAnim(0.92f) }

        binding.edTitle.doAfterTextChanged {
            markDirty()
            updateMeta()
        }

        // ---- 文字模式：ColorOS 16 双层悬浮岛主栏交互 ----
        binding.btnToggleAa.setOnClickListener { toggleAaPanel() }
        binding.btnPalette.setOnClickListener { v ->
            val popup = androidx.appcompat.widget.PopupMenu(this, v)
            popup.menu.add(0, 1, 0, getString(R.string.rich_color))
            popup.menu.add(0, 2, 1, getString(R.string.style_highlight))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> pickColor()
                    2 -> pickHighlight()
                }
                true
            }
            popup.show()
        }
        binding.btnMoreTools.setOnClickListener { showMoreMenu() }

        // ---- 文字模式格式工具栏（二级面板与行内样式） ----
        binding.btnTB.setOnClickListener { exec("bold", null) }
        binding.btnTI.setOnClickListener { exec("italic", null) }
        binding.btnTU.setOnClickListener { exec("underline", null) }
        binding.btnTS.setOnClickListener { exec("strikeThrough", null) }
        binding.btnTS.paintFlags = binding.btnTS.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG

        binding.segTitle.setOnClickListener {
            updateSegments(binding.segTitle)
            exec("formatBlock", "<h1>")
        }
        binding.segSubtitle.setOnClickListener {
            updateSegments(binding.segSubtitle)
            exec("formatBlock", "<h2>")
        }
        binding.segHeading.setOnClickListener {
            updateSegments(binding.segHeading)
            exec("formatBlock", "<h3>")
        }
        binding.segBody.setOnClickListener {
            updateSegments(binding.segBody)
            exec("formatBlock", "<p>")
        }

        binding.btnTodo.setOnClickListener {
            exec("insertHTML", "<p class=\"task-item\"><input type=\"checkbox\"><span>&nbsp;</span></p>")
            toast(R.string.todo_menu)
        }
        binding.btnUl.setOnClickListener { exec("insertUnorderedList", null) }
        binding.btnOl.setOnClickListener { exec("insertOrderedList", null) }
        binding.btnQuote.setOnClickListener { exec("formatBlock", "<blockquote>") }

        binding.btnAlignL.setOnClickListener { exec("justifyLeft", null) }
        binding.btnAlignC.setOnClickListener { exec("justifyCenter", null) }
        binding.btnAlignR.setOnClickListener { exec("justifyRight", null) }

        binding.btnColor.setOnClickListener { pickColor() }
        binding.btnHl.setOnClickListener { pickHighlight() }
        binding.btnSize.setOnClickListener { pickSize() }
        binding.btnFont.setOnClickListener { cycleFontFamily() }

        binding.btnLink.setOnClickListener { insertLink() }
        binding.btnImage.setOnClickListener { insertImage() }
        binding.btnImage.setOnLongClickListener {
            pickImageLauncher.launch("image/*")
            true
        }
        binding.btnCode.setOnClickListener { exec("inlineCode", null) }
        binding.btnHr.setOnClickListener { exec("insertHorizontalRule", null); toast(R.string.toast_hr) }
        binding.btnOutdent.setOnClickListener { exec("outdent", null) }
        binding.btnIndent.setOnClickListener { exec("indent", null) }
        binding.btnClear.setOnClickListener { exec("removeFormat", null); toast(R.string.rich_clear) }

        // ---- 代码模式工具栏（撤销/重做只在顶栏，这里不重复） ----
        binding.btnCIndent.setOnClickListener {
            codeAction(binding.btnCIndent) { binding.edContent.indentSelection(false) }
        }
        binding.btnCOutdent.setOnClickListener {
            codeAction(binding.btnCOutdent) { binding.edContent.indentSelection(true) }
        }
        binding.btnFormatCode.setOnClickListener {
            codeAction(binding.btnFormatCode) { binding.edContent.formatCode() }
            toast(R.string.toast_format)
        }
        binding.btnClearCode.setOnClickListener { confirmClearCode() }

        // ---- 代码片段面板 ----
        binding.btnSnLink.setOnClickListener {
            val sn = "<a href=\"https://\">链接文本</a>"
            insertCodeSnippet(sn, sn.indexOf("https://"), "https://".length); toast(R.string.toast_link)
        }
        binding.btnSnImage.setOnClickListener { insertImage() }
        binding.btnSnImage.setOnLongClickListener {
            pickImageLauncher.launch("image/*")
            true
        }
        binding.btnSnUl.setOnClickListener { insertCodeSnippet("<ul>\n  <li></li>\n</ul>\n"); toast(R.string.rich_ul) }
        binding.btnSnHr.setOnClickListener { insertCodeSnippet("<hr>\n"); toast(R.string.toast_hr) }
        binding.btnSnTodo.setOnClickListener { insertCodeSnippet("☐ "); toast(R.string.todo_menu) }
        binding.btnSnCheck.setOnClickListener { insertCodeSnippet("☑ "); toast(R.string.check_list) }
        binding.btnCopyAll.setOnClickListener { copyAllCode() }

        binding.edContent.setOnClickListener {
            if (binding.edTitle.hasFocus()) {
                binding.edTitle.clearFocus()
            }
            binding.edContent.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.edContent, InputMethodManager.SHOW_IMPLICIT)
            ViewCompat.getWindowInsetsController(binding.edContent)
                ?.show(WindowInsetsCompat.Type.ime())
        }
    }

    // ---------------- 工具栏通用：短暂高亮 / 轻量 toast ----------------

    /** 代码操作反馈：按钮短暂高亮 320ms 后回落。 */
    private fun flash(v: View) {
        v.isSelected = true
        v.postDelayed({ v.isSelected = false }, 320)
    }

    private fun toast(res: Int) {
        val t = tip
        if (t != null) t.show(res) else Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    /**
     * 代码工具栏操作的统一收尾。
     *
     * 编辑框失焦时 selectionStart 是 -1，缩进/格式化内部会直接 return、什么也不做；
     * 而原来的写法无论如何都会 markDirty()，于是「点了一下没反应的按钮」也会把便签
     * 标成未保存、并触发一次没有变化的自动保存。这里先拿回焦点，再按文本是否真的
     * 变了决定要不要标脏。
     */
    private fun codeAction(v: View, action: () -> Unit) {
        if (binding.edContent.selectionStart < 0) binding.edContent.requestFocus()
        val before = binding.edContent.text?.toString()
        action()
        flash(v)
        updateCodeUndoRedo()
        if (binding.edContent.text?.toString() != before) markDirty()
    }
    private fun toast(msg: String) {
        val t = tip
        if (t != null) t.showText(msg) else Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 标题改动同样标记为草稿已变更，保证只改标题也会被自动保存，并显示"未保存"状态。 */
    private fun markDirty() {
        if (noteType == Project.TYPE_TEXT) richDraftChanged = true else htmlDraftChanged = true
        showSaveState(getString(R.string.status_editing), onSurfaceVariantColor, sticky = true)
    }

    /** 顶部右侧"未保存 / 已保存 ✓"状态提示。 */
    private fun showSaveState(text: String, color: Int, sticky: Boolean) {
        // autoSaveDraft 的 collectContent 走 JS 异步回调，结果可能晚于 onDestroy 才回来；
        // 这里若照常执行，就会在 Activity 销毁之后又挂一条 2400ms 的延迟任务。
        if (isFinishing || isDestroyed) return
        binding.saveState.text = text
        binding.saveState.setTextColor(color)
        binding.saveState.animate().cancel()
        binding.saveState.removeCallbacks(hideSaveState)
        binding.saveState.scaleX = 0.92f
        binding.saveState.scaleY = 0.92f
        binding.saveState.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190)
            .setInterpolator(CouiMotion.EASE_INTERPOLATOR)
            .start()
        if (!sticky) {
            binding.saveState.postDelayed(hideSaveState, 2400)
        }
    }

    private val hideSaveState = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        binding.saveState.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(280)
            .setInterpolator(CouiMotion.IN_EASE_INTERPOLATOR)
            .start()
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
        // 延时聚焦任务跑在 WebView / EditText 上，必须赶在 destroy 之前撤掉
        focusTitleTask?.let { binding.edTitle.removeCallbacks(it) }
        focusBodyTask?.let { binding.richWeb.removeCallbacks(it) }
        focusTitleTask = null
        focusBodyTask = null
        // 选区锚定的滚动动画跑在 WebView 上，销毁前必须停掉
        selScrollAnimator?.cancel()
        selScrollAnimator = null
        dismissSelectionBar()
        selPopup = null
        selBar = null
        tip?.dispose()
        tip = null
        // 「未保存」提示的淡出是 view.postDelayed 挂在 saveState 上的，不撤会跑到 destroy 之后
        binding.saveState.removeCallbacks(hideSaveState)
        binding.saveState.animate().cancel()
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
        binding.richWeb.isFocusable = true
        binding.richWeb.isFocusableInTouchMode = true
        with(binding.richWeb.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            allowFileAccess = true
            allowContentAccess = true
        }
        binding.richWeb.addJavascriptInterface(RichBridge(), "HtmlNotes")
        binding.richWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleUrl(uri)
            }
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = if (url != null) Uri.parse(url) else return false
                return handleUrl(uri)
            }
            private fun handleUrl(uri: Uri): Boolean {
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: Exception) {
                        toast(R.string.toast_link)
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.richWeb.evaluateJavascript(
                    "var ed=document.getElementById('ed');" +
                        // 正文真正变化时才回传完整 HTML：MutationObserver 置脏、
                        // refreshState 冲刷。光标移动与选区变化不触发序列化，
                        // 序列化开销只落在真正的编辑上。
                        "var _dirty=false;" +
                        // 上一次已回传的选区串。selectionchange 在敲键时每次都触发，
                        // 坐标没变就不要再过一遍桥。
                        "var _lr='';" +
                        "new MutationObserver(function(){_dirty=true;})" +
                        ".observe(ed,{childList:true,subtree:true,characterData:true,attributes:true});" +
                        "function refreshState(){" +
                        "var _anchor=window.getSelection()?window.getSelection().anchorNode:null;" +
                        "var _isTodo=false;" +
                        "var _p=_anchor?(_anchor.nodeType===1?_anchor:_anchor.parentElement):null;" +
                        "while(_p&&_p!==ed){" +
                        "if(_p.querySelector&&_p.querySelector('input[type=checkbox]')){_isTodo=true;break;}" +
                        "_p=_p.parentElement;" +
                        "}" +
                        "var st=JSON.stringify({" +
                        "b:document.queryCommandState('bold')," +
                        "i:document.queryCommandState('italic')," +
                        "u:document.queryCommandState('underline')," +
                        "s:document.queryCommandState('strikeThrough')," +
                        "ul:document.queryCommandState('insertUnorderedList')," +
                        "ol:document.queryCommandState('insertOrderedList')," +
                        "jc:document.queryCommandState('justifyCenter')," +
                        "jr:document.queryCommandState('justifyRight')," +
                        "jf:document.queryCommandState('justifyFull')," +
                        "todo:_isTodo});" +
                        "HtmlNotes.onState(document.queryCommandEnabled('undo')," +
                        "document.queryCommandEnabled('redo')," +
                        "document.queryCommandValue('formatBlock')," +
                        "ed.innerText.length,st);" +
                        // 选区坐标（相对视口）。原生拿它算锚定、摆浮条，是整套交互的唯一事实来源。
                        // 放在 onState 之后：浮条要读同一轮回传的 B/I/U 状态，顺序反了就会闪一下旧值。
                        "var _r='';try{var _s=getSelection();if(_s.rangeCount){" +
                        "var _b=_s.getRangeAt(0).getBoundingClientRect();" +
                        "_r=JSON.stringify([Math.round(_b.left),Math.round(_b.top)," +
                        "Math.round(_b.width),Math.round(_b.height),_s.isCollapsed?1:0]);}}" +
                        "catch(_e){}" +
                        "if(_r&&_r!==_lr){_lr=_r;HtmlNotes.onSelection(_r);}" +
                        "if(_dirty){_dirty=false;HtmlNotes.onContent(ed.innerHTML);}}" +
                        "ed.addEventListener('input',function(){refreshState();HtmlNotes.onDirty();});" +
                        "ed.addEventListener('keyup',refreshState);" +
                        "ed.addEventListener('mouseup',refreshState);" +
                        // 光标/选区变化实时刷新工具栏状态，告别状态滞后
                        "document.addEventListener('selectionchange',refreshState);" +
                        // DOM 块级元素识别与图片容器包裹辅助函数
                        "function getTopBlock(node,ed){" +
                        "if(!node||node===ed)return null;" +
                        "var cur=(node.nodeType===3)?node.parentElement:node;" +
                        "if(!cur||!ed.contains(cur))return null;" +
                        "while(cur&&cur.parentElement&&cur.parentElement!==ed){cur=cur.parentElement;}" +
                        "return cur;" +
                        "}" +
                        "window.getTopBlock=getTopBlock;" +
                        "function getImageBlock(img,ed){" +
                        "if(!img||!ed.contains(img))return null;" +
                        "var p=img.parentElement;" +
                        "if(p===ed)return img;" +
                        "var isSoleChild=true;" +
                        "for(var i=0;i<p.childNodes.length;i++){" +
                        "var cn=p.childNodes[i];" +
                        "if(cn===img)continue;" +
                        "if(cn.nodeType===3&&cn.textContent.trim()==='')continue;" +
                        "if(cn.nodeType===1&&(cn.tagName==='BR'||cn.classList.contains('img-drop-indicator')))continue;" +
                        "isSoleChild=false;break;" +
                        "}" +
                        "if(isSoleChild&&p.parentElement===ed)return p;" +
                        "if(isSoleChild&&p.parentElement&&p.parentElement!==ed)return getTopBlock(p,ed);" +
                        "var container=document.createElement('p');container.className='note-img-container';" +
                        "img.parentNode.insertBefore(container,img);container.appendChild(img);" +
                        "return container;" +
                        "}" +
                        "window.getImageBlock=getImageBlock;" +
                        // 跟踪触控移动、长按拖拽与页面滚动
                        "var _touchStartX=0,_touchStartY=0,_isScrolling=false,_lastScrollTime=0,_lastScrollY=0;" +
                        "var _dragImg=null,_dragTimer=null,_isDragging=false,_dragAvatar=null,_dropIndicator=null;" +
                        "var _currentDropTarget=null,_dropInsertBefore=true,_lastDragEndTime=0;" +
                        "var _autoScrollTimer=null,_autoScrollSpeed=0,_lastTouchX=0,_lastTouchY=0;" +
                        "function stopAutoScroll(){if(_autoScrollTimer){clearInterval(_autoScrollTimer);_autoScrollTimer=null;}}" +
                        "function startAutoScroll(){" +
                        "if(_autoScrollTimer)return;" +
                        "_autoScrollTimer=setInterval(function(){" +
                        "if(!_isDragging){stopAutoScroll();return;}" +
                        "window.scrollBy(0,_autoScrollSpeed);" +
                        "updateDropIndicator(_lastTouchX,_lastTouchY);" +
                        "},16);" +
                        "}" +
                        "function updateDropIndicator(cx,cy){" +
                        "if(!_isDragging||!_dragImg)return;" +
                        "var el=document.elementFromPoint(cx,cy);" +
                        "if(!el)return;" +
                        "var targetBlock=getTopBlock(el,ed);" +
                        "if(!targetBlock||targetBlock===_dropIndicator)return;" +
                        "var rect=targetBlock.getBoundingClientRect();" +
                        "var insertBefore=(cy<(rect.top+rect.height/2));" +
                        "if(targetBlock!==_currentDropTarget||insertBefore!==_dropInsertBefore){" +
                        "_currentDropTarget=targetBlock;_dropInsertBefore=insertBefore;" +
                        "if(!_dropIndicator){_dropIndicator=document.createElement('div');_dropIndicator.className='img-drop-indicator';}" +
                        "if(insertBefore){targetBlock.parentNode.insertBefore(_dropIndicator,targetBlock);}" +
                        "else{targetBlock.parentNode.insertBefore(_dropIndicator,targetBlock.nextSibling);}" +
                        "HtmlNotes.onHapticTick();" +
                        "}" +
                        "}" +
                        "function startImageDrag(img,startX,startY){" +
                        "_isDragging=true;_dragTimer=null;" +
                        "HtmlNotes.onImageDragStart();" +
                        "if(document.activeElement&&document.activeElement.blur){document.activeElement.blur();}" +
                        "document.querySelectorAll('#ed img.selected').forEach(function(el){el.classList.remove('selected');});" +
                        "img.classList.add('drag-source');" +
                        "if(_dragAvatar&&_dragAvatar.parentNode)_dragAvatar.parentNode.removeChild(_dragAvatar);" +
                        "_dragAvatar=document.createElement('img');_dragAvatar.src=img.src;_dragAvatar.className='img-drag-avatar';" +
                        "_dragAvatar.style.left=startX+'px';_dragAvatar.style.top=startY+'px';" +
                        "document.body.appendChild(_dragAvatar);" +
                        "var block=getImageBlock(img,ed);" +
                        "if(!_dropIndicator){_dropIndicator=document.createElement('div');_dropIndicator.className='img-drop-indicator';}" +
                        "if(block&&block.parentNode){block.parentNode.insertBefore(_dropIndicator,block);}" +
                        "}" +
                        "window.addEventListener('touchstart',function(e){" +
                        "if(!e.touches||!e.touches.length)return;" +
                        "_touchStartX=e.touches[0].clientX;_touchStartY=e.touches[0].clientY;" +
                        "_isScrolling=false;" +
                        "if(_dragTimer){clearTimeout(_dragTimer);_dragTimer=null;}" +
                        "var t=e.target;" +
                        "if(t&&t.tagName==='IMG'&&ed.contains(t)){" +
                        "_dragImg=t;" +
                        "_dragTimer=setTimeout(function(){startImageDrag(t,_touchStartX,_touchStartY);},280);" +
                        "}" +
                        "},{passive:true});" +
                        "window.addEventListener('touchmove',function(e){" +
                        "if(!e.touches||!e.touches.length)return;" +
                        "var cx=e.touches[0].clientX,cy=e.touches[0].clientY;" +
                        "_lastTouchX=cx;_lastTouchY=cy;" +
                        "if(!_isDragging){" +
                        "var dx=Math.abs(cx-_touchStartX);var dy=Math.abs(cy-_touchStartY);" +
                        "if(dx>6||dy>6){_isScrolling=true;if(_dragTimer){clearTimeout(_dragTimer);_dragTimer=null;}}" +
                        "}else{" +
                        "if(e.cancelable)e.preventDefault();" +
                        "if(_dragAvatar){_dragAvatar.style.left=cx+'px';_dragAvatar.style.top=cy+'px';}" +
                        "var edgeMargin=72;" +
                        "if(cy<edgeMargin){_autoScrollSpeed=-Math.max(6,Math.round((edgeMargin-cy)/4));startAutoScroll();}" +
                        "else if(cy>window.innerHeight-edgeMargin){_autoScrollSpeed=Math.max(6,Math.round((cy-(window.innerHeight-edgeMargin))/4));startAutoScroll();}" +
                        "else{stopAutoScroll();}" +
                        "updateDropIndicator(cx,cy);" +
                        "}" +
                        "},{passive:false});" +
                        "window.addEventListener('touchend',function(e){" +
                        "if(_dragTimer){clearTimeout(_dragTimer);_dragTimer=null;}" +
                        "if(_isDragging){" +
                        "_isDragging=false;_lastDragEndTime=Date.now();stopAutoScroll();" +
                        "if(_dragAvatar&&_dragAvatar.parentNode){_dragAvatar.parentNode.removeChild(_dragAvatar);_dragAvatar=null;}" +
                        "if(_dragImg){_dragImg.classList.remove('drag-source');}" +
                        "var moved=false;" +
                        "if(_dropIndicator&&_dropIndicator.parentNode&&_dragImg){" +
                        "var block=getImageBlock(_dragImg,ed);" +
                        "if(block&&block.parentNode){" +
                        "if(_dropIndicator.nextSibling!==block&&_dropIndicator.previousSibling!==block){" +
                        "_dropIndicator.parentNode.insertBefore(block,_dropIndicator);moved=true;" +
                        "}else if(_dropIndicator.nextSibling!==block){" +
                        "_dropIndicator.parentNode.insertBefore(block,_dropIndicator);moved=true;" +
                        "}" +
                        "}" +
                        "_dropIndicator.parentNode.removeChild(_dropIndicator);_dropIndicator=null;" +
                        "}" +
                        "_currentDropTarget=null;_dragImg=null;" +
                        "refreshState();HtmlNotes.onDirty();HtmlNotes.onImageDragEnd(moved);" +
                        "}" +
                        "},{passive:true});" +
                        "window.addEventListener('touchcancel',function(e){" +
                        "if(_dragTimer){clearTimeout(_dragTimer);_dragTimer=null;}" +
                        "if(_isDragging){" +
                        "_isDragging=false;_lastDragEndTime=Date.now();stopAutoScroll();" +
                        "if(_dragAvatar&&_dragAvatar.parentNode){_dragAvatar.parentNode.removeChild(_dragAvatar);_dragAvatar=null;}" +
                        "if(_dragImg){_dragImg.classList.remove('drag-source');_dragImg=null;}" +
                        "if(_dropIndicator&&_dropIndicator.parentNode){_dropIndicator.parentNode.removeChild(_dropIndicator);_dropIndicator=null;}" +
                        "_currentDropTarget=null;HtmlNotes.onImageDragEnd(false);" +
                        "}" +
                        "});" +
                        "window.addEventListener('scroll',function(){" +
                        "_isScrolling=true;_lastScrollTime=Date.now();" +
                        "var curY=window.scrollY||document.documentElement.scrollTop||0;" +
                        "var dy=curY-_lastScrollY;_lastScrollY=curY;" +
                        "if(Math.abs(dy)>12){HtmlNotes.onScrollDirection(dy>0);}" +
                        "refreshState();" +
                        "},true);" +
                        // 点击事件：精准区分复选框、图片与正文聚焦（滑动与惯性制动绝对不误触发）
                        "document.addEventListener('click',function(e){" +
                        "if(_isScrolling||_isDragging||(Date.now()-_lastScrollTime<350)||(Date.now()-_lastDragEndTime<400)){return;}" +
                        "var t=e.target;" +
                        "if(t&&t.tagName==='INPUT'&&t.type==='checkbox'){" +
                        "if(t.checked){t.setAttribute('checked','checked');}else{t.removeAttribute('checked');}" +
                        "refreshState();HtmlNotes.onDirty();return;" +
                        "}" +
                        "if(t&&t.tagName==='IMG'){" +
                        "e.preventDefault();e.stopPropagation();" +
                        "if(document.activeElement&&document.activeElement!==document.body){document.activeElement.blur();}" +
                        "document.querySelectorAll('#ed img.selected').forEach(function(el){el.classList.remove('selected');});" +
                        "t.classList.add('selected');" +
                        "if(!t.id){t.id='img_'+Date.now();}" +
                        "var align='center';" +
                        "if(t.classList.contains('align-left'))align='left';" +
                        "else if(t.classList.contains('align-right'))align='right';" +
                        "else if(t.classList.contains('align-full'))align='full';" +
                        "HtmlNotes.onImageClicked(t.id,t.src,align);return;" +
                        "}" +
                        "document.querySelectorAll('#ed img.selected').forEach(function(el){el.classList.remove('selected');});" +
                        "if(document.activeElement!==ed&&(!ed.contains(t)||t===document.body)){ed.focus();}" +
                        "HtmlNotes.onEditorTap();" +
                        "});" +
                        // 稳健富文本内容注入器：选区存在则插入选区，选区丢失则智能追加末尾，永不丢图
                        "window._insertHtml=function(html){" +
                        "var ed=document.getElementById('ed');ed.focus();" +
                        "var s=window.getSelection();" +
                        "if(s&&s.rangeCount>0){" +
                        "var r=s.getRangeAt(0);" +
                        "if(ed.contains(r.commonAncestorContainer)){" +
                        "r.deleteContents();" +
                        "var el=document.createElement('div');el.innerHTML=html;" +
                        "var frag=document.createDocumentFragment();var node,lastNode;" +
                        "while((node=el.firstChild)){lastNode=frag.appendChild(node);}" +
                        "r.insertNode(frag);" +
                        "if(lastNode){var nr=document.createRange();nr.setStartAfter(lastNode);nr.collapse(true);s.removeAllRanges();s.addRange(nr);}" +
                        "refreshState();HtmlNotes.onDirty();return;" +
                        "}" +
                        "}" +
                        "var el=document.createElement('div');el.innerHTML=html;" +
                        "while(el.firstChild){ed.appendChild(el.firstChild);}" +
                        "refreshState();HtmlNotes.onDirty();" +
                        "};" +
                        // 外接键盘快捷键。必须在页面里拦：Ctrl+X 之后的键事件早就被 IME/WebView 吃掉，
                        // 原生 onKeyDown 根本收不到。capture 挂在 document 上，赶在浏览器默认动作之前
                        // 抹掉它，否则 execCommand 会跟内置处理各翻一次加粗，等于没按。
                        "document.addEventListener('keydown',function(e){" +
                        "if(!(e.ctrlKey||e.metaKey)||e.altKey)return;" +
                        "var k=(e.key||'').toLowerCase(),m='';" +
                        "if(k==='b')m='bold';else if(k==='i')m='italic';else if(k==='u')m='underline';" +
                        "else if(k==='z')m=e.shiftKey?'redo':'undo';" +
                        "else if(k==='y')m='redo';else if(k==='a')m='selectAll';" +
                        "if(!m)return;" +
                        "e.preventDefault();e.stopPropagation();" +
                        "document.execCommand(m,false,null);" +
                        "refreshState();HtmlNotes.onShortcut(m);},true);" +
                        "document.execCommand('styleWithCSS', false, false);" +
                        // 选区富文本快照（剪切/复制用）。返回 null 表示没有选区。
                        "function _sp(){var s=getSelection();if(!s.rangeCount)return null;" +
                        "var d=document.createElement('div');d.appendChild(s.getRangeAt(0).cloneContents());" +
                        "return JSON.stringify({t:s.toString(),h:d.innerHTML});}" +
                        "document.getElementById('ed').innerHTML = ${JSONObject.quote(content)};" +
                        "refreshState();"
                ) {
                    richReady = true
                    richCache = content   // 与刚写入页面的正文一致，保存可直接取用
                }
            }
        }
    }

    private fun loadRichContent(content: String) {
        setupRichWeb(content)
        binding.richWeb.loadDataWithBaseURL(null, NoteHtml.editorPage(NoteHtml.paletteOf(this)), "text/html", "UTF-8", null)
        // 仅在新创便签（projectId == null）时延时自动聚焦并弹键盘；
        // 现有便签打开时不强行把光标拉到末尾或弹键盘，避免破坏用户刚点击定位的位置。
        if (projectId == null) {
            val task = Runnable {
                binding.richWeb.requestFocus()
                binding.richWeb.evaluateJavascript(
                    "var ed=document.getElementById('ed');if(ed){ed.focus();" +
                        "var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);" +
                        "var s=getSelection();s.removeAllRanges();s.addRange(r);}",
                    null
                )
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.richWeb, InputMethodManager.SHOW_IMPLICIT)
                ViewCompat.getWindowInsetsController(binding.richWeb)
                    ?.show(WindowInsetsCompat.Type.ime())
            }
            focusBodyTask = task
            binding.richWeb.postDelayed(task, 350)
        }
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
        if (binding.edTitle.hasFocus()) {
            binding.edTitle.clearFocus()
        }
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
                "insertHTML" ->
                    "if(window._insertHtml){window._insertHtml(${JSONObject.quote(value)});}else{document.execCommand('insertHTML',false,${JSONObject.quote(value)});}refreshState();"
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
        // 撤销是全静默的：正文变了，但没有任何地方告诉用户「刚刚撤销了一步」
        toast(R.string.tip_undone)
    }

    private fun performRedo() {
        if (noteType == Project.TYPE_TEXT) {
            exec("redo")
        } else {
            binding.edContent.redo()
            updateCodeUndoRedo()
        }
        toast(R.string.tip_redone)
    }

    private fun copyAllCode() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", binding.edContent.text))
        toast(R.string.copied)
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

    private fun showColorPicker(
        titleRes: Int,
        colors: Array<Triple<String, String, Boolean>>,
        onSelect: (String) -> Unit,
        onReset: (() -> Unit)? = null
    ) {
        val sheet = BottomSheetDialog(this)
        val pickerBinding = SheetColorPickerBinding.inflate(layoutInflater)
        sheet.setContentView(pickerBinding.root)
        pickerBinding.pickerTitle.setText(titleRes)

        if (onReset != null) {
            pickerBinding.btnResetColor.visibility = View.VISIBLE
            pickerBinding.btnResetColor.setOnClickListener {
                sheet.dismiss()
                onReset()
            }
        } else {
            pickerBinding.btnResetColor.visibility = View.GONE
        }

        class PaletteVH(val b: ItemPaletteColorBinding) : RecyclerView.ViewHolder(b.root)
        pickerBinding.colorRecycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        pickerBinding.colorRecycler.adapter = object : RecyclerView.Adapter<PaletteVH>() {
            override fun getItemCount() = colors.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PaletteVH {
                return PaletteVH(ItemPaletteColorBinding.inflate(layoutInflater, parent, false))
            }
            override fun onBindViewHolder(holder: PaletteVH, position: Int) {
                val (hex, name, isLight) = colors[position]
                val b = holder.b
                val colorVal = Color.parseColor(hex)
                (b.colorCircle.background as? GradientDrawable)?.setColor(colorVal) ?: run {
                    b.colorCircle.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(colorVal)
                        setStroke((1.5f * resources.displayMetrics.density).toInt(), if (isLight) Color.parseColor("#33000000") else Color.TRANSPARENT)
                    }
                }
                b.colorName.text = name
                b.checkMark.imageTintList = android.content.res.ColorStateList.valueOf(
                    if (isLight) Color.parseColor("#1B1B20") else Color.WHITE
                )
                b.root.attachPressScale(0.9f, 80L)
                b.root.setOnClickListener {
                    sheet.dismiss()
                    onSelect(hex)
                }
            }
        }
        sheet.show()
    }

    private fun pickColor() {
        val colors = arrayOf(
            Triple("#1B1B20", "曜石黑", false),
            Triple("#616161", "沉稳灰", false),
            Triple("#E53935", "醒目红", false),
            Triple("#FB8C00", "活力橙", false),
            Triple("#FDD835", "琥珀黄", true),
            Triple("#43A047", "翡翠绿", false),
            Triple("#1E88E5", "晴空蓝", false),
            Triple("#8E24AA", "雅致紫", false),
            Triple("#D81B60", "玫瑰红", false)
        )
        showColorPicker(R.string.pick_font_color, colors, onSelect = { hex ->
            onCommandColor(hex)
        }, onReset = {
            exec("removeFormat", null)
            toast(R.string.rich_clear)
        })
    }

    private fun pickHighlight() {
        val hlColors = arrayOf(
            Triple("#FFF176", "柠檬黄", true),
            Triple("#A5D6A7", "薄荷绿", true),
            Triple("#FFCC80", "落日橙", true),
            Triple("#81D4FA", "清爽蓝", true),
            Triple("#CE93D8", "浅淡紫", true),
            Triple("#F48FB1", "浪漫粉", true),
            Triple("#B0BEC5", "冷雾灰", true)
        )
        showColorPicker(R.string.pick_highlight_color, hlColors, onSelect = { hex ->
            exec("hiliteColor", hex)
            toast(R.string.highlight_applied)
        }, onReset = {
            exec("hiliteColor", "transparent")
            toast(R.string.highlight_cleared)
        })
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
        // 按钮文案本身就是当前字体名，效果也立刻可见，不再多弹一句 toast
        binding.btnFont.text = resources.getStringArray(R.array.font_family_names)[fontCycle]
        exec("fontFamily", fontStacks[fontCycle])
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
                syncSelectionBarState()
                updateMeta()
            }
        }

        @JavascriptInterface
        fun onDirty() {
            runOnUiThread {
                richDraftChanged = true
                showSaveState(getString(R.string.status_editing), onSurfaceVariantColor, sticky = true)
            }
        }

        /**
         * 正文快照。调用点在 JavaBridge 线程，这里不做 runOnUiThread ——
         * 只是往一个 @Volatile 字段里赋值，省掉每次编辑一条主线程消息。
         */
        @JavascriptInterface
        fun onContent(html: String) {
            richCache = html
        }

        /**
         * 选区坐标。回传格式是 `[top, height, collapsed]` 数组的 JSON 文本。
         *
         * 只往 `@Volatile` 字段里写，坐标换算和滚动全部留在主线程的
         * [onSelectionUpdated] 里做 —— 这条通道不持有 Activity 引用、不 post 延迟任务。
         */
        @JavascriptInterface
        fun onSelection(rectJson: String) {
            val r = parseSelRect(rectJson) ?: return
            selRect = r
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                onSelectionUpdated()
            }
        }

        /** 页面滚动方向感知：在浏览模式下向下滚动时收起工具栏，向上滚动时滑出，提供完全沉浸阅读体验 */
        @JavascriptInterface
        fun onScrollDirection(down: Boolean) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dismissSelectionBar()
                if (lastImeVisible != true) {
                    val dock = dockingToolbar()
                    if (dock.visibility == View.VISIBLE) {
                        if (down) {
                            dock.animate().translationY(dock.height.toFloat() + dp(32)).setDuration(220).start()
                        } else {
                            dock.animate().translationY(0f).setDuration(220).start()
                        }
                    }
                }
            }
        }

        /** 用户点到正文上：清除标题框焦点，平滑滑入工具栏并唤起输入法 */
        @JavascriptInterface
        fun onEditorTap() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (binding.edTitle.hasFocus()) {
                    binding.edTitle.clearFocus()
                }
                binding.richWeb.requestFocus()
                dockingToolbar().animate().translationY(0f).setDuration(180).start()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.richWeb, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        /** 用户轻触图片：弹出图片操作面板（查看大图、前后插入正文、替换、对齐、删除），不误弹软键盘 */
        @JavascriptInterface
        fun onImageClicked(imgId: String, src: String) {
            onImageClicked(imgId, src, "center")
        }

        @JavascriptInterface
        fun onImageClicked(imgId: String, src: String, align: String) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showImageActionSheet(imgId, src, align)
            }
        }

        /** 图像开始长按拖拽：触发触觉震动反馈，收起键盘与浮条 */
        @JavascriptInterface
        fun onImageDragStart() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.richWeb.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.richWeb.windowToken, 0)
                dismissSelectionBar()
            }
        }

        /** 图像拖动经过不同组件/段落：轻触震动反馈 (ColorOS Aquamorphic 物理触感) */
        @JavascriptInterface
        fun onHapticTick() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                    binding.richWeb.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
                } else {
                    binding.richWeb.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }
        }

        /** 图像拖动结束：移动成功则触发确认震动与轻提示 */
        @JavascriptInterface
        fun onImageDragEnd(moved: Boolean) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (moved) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        binding.richWeb.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    } else {
                        binding.richWeb.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    toast(R.string.toast_image_moved)
                    markDirty()
                }
            }
        }

        /**
         * 外接键盘快捷键已由页面拦下并执行（Ctrl+B/I/U/Z/Y/A），这里只负责反馈。
         *
         * 撤销/重做是全静默的：正文变了，但没有任何地方告诉用户刚撤销了一步，
         * 所以补一枚轻提示。加粗/斜体/下划线本身有工具栏按钮变亮作为反馈，不重复播报。
         */
        @JavascriptInterface
        fun onShortcut(cmd: String) {
            val res = when (cmd) {
                "undo" -> R.string.tip_undone
                "redo" -> R.string.tip_redone
                else -> return
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tip?.show(res)
            }
        }

        /** 重新把 _ic span 渲染为 <code> 风格 */
        @JavascriptInterface
        fun relayoutCodes() {
            // @JavascriptInterface 运行在 WebView 的 JavaBridge 线程，而 WebView 方法必须在主线程调用。
            // 但主线程队列里的这条可能排到 onDestroy 之后 —— 那时 WebView 已 destroy()，
            // 再 evaluateJavascript 就打到已销毁的实例上。
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.richWeb.evaluateJavascript(
                    "document.querySelectorAll('#ed span._ic').forEach(function(s){" +
                        "var c=document.createElement('code');c.innerHTML=s.innerHTML;" +
                        "s.parentNode.replaceChild(c,s);});",
                    null
                )
            }
        }
    }

    // ---------------- 选区锚定：让「正在改的那段字」永远留在视野里 ----------------

    private fun parseSelRect(json: String): SelRect? = try {
        val a = JSONArray(json)
        SelRect(
            left = a.getInt(0),
            top = a.getInt(1),
            width = a.getInt(2),
            height = a.getInt(3),
            collapsed = a.getInt(4) == 1
        )
    } catch (e: Exception) {
        null
    }

    private fun onSelectionUpdated() {
        if (lastImeVisible == true) {
            evaluateAnchor()
        }
        updateSelectionBar()
    }

    // ---------------- 选区浮条：贴着选区出现，替代系统的选择菜单 ----------------

    private fun setupSelectionBar() {
        val bar = ViewSelectionBarBinding.inflate(layoutInflater)
        selBar = bar
        // isFocusable=false 是关键：浮条一旦可聚焦，点它就会把焦点从 WebView 抢走，
        // 键盘被收起、选区也可能失效，紧接着的所有格式命令都作用不到选区上。
        val popup = PopupWindow(
            bar.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false
        ).apply {
            isFocusable = false
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        selPopup = popup

        // 下划线没有对应的 XML 属性（TextView 的 styleable 里没有 paintFlags），只能在代码里设
        bar.selUnderline.paintFlags = bar.selUnderline.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        bar.selBold.setOnClickListener { exec("bold") }
        bar.selItalic.setOnClickListener { exec("italic") }
        bar.selUnderline.setOnClickListener { exec("underline") }
        bar.selSelectAll.setOnClickListener { evalRich("document.execCommand('selectAll');refreshState();") }
        bar.selCopy.setOnClickListener { copySelection(cut = false) }
        bar.selCut.setOnClickListener { copySelection(cut = true) }
        bar.selMore.setOnClickListener {
            dismissSelectionBar()
        }
    }

    /**
     * 浮条的可见性与坐标。仅在软键盘唤起且有真实选区时贴合出现；
     * 浏览模式下（键盘收起）或选区折叠（光标）时立刻收起，杜绝翻看浏览时频繁弹出浮条打扰阅读。
     */
    private fun updateSelectionBar() {
        val popup = selPopup ?: return
        if (isFinishing || isDestroyed) return
        val rect = selRect
        if (noteType != Project.TYPE_TEXT || rect == null || rect.collapsed || lastImeVisible != true) {
            dismissSelectionBar()
            return
        }
        val barRoot = selBar?.root ?: return
        if (barRoot.width == 0 || barRoot.height == 0) {
            barRoot.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }
        val barW = barRoot.measuredWidth
        val barH = barRoot.measuredHeight

        val rootLoc = IntArray(2).also { binding.root.getLocationOnScreen(it) }
        val webLoc = IntArray(2).also { binding.richWeb.getLocationOnScreen(it) }
        val left = rootLoc[0] + rootPad[0]
        val top = rootLoc[1] + rootPad[1]
        val right = rootLoc[0] + binding.root.width - rootPad[2]
        val bottom = rootLoc[1] + binding.root.height - rootPad[3]

        val selTop = webLoc[1] + rect.top
        val selBottom = selTop + rect.height
        val selCenterX = (webLoc[0] + rect.left + rect.width / 2).toFloat()

        // 优先贴选区上方；上方放不下翻到下方；再放不下夹进可用区。
        var y = selTop - barH - dp(8)
        if (y < top) y = selBottom + dp(8)
        // 浮条不能压在底部工具栏上：工具栏顶部就是可用区的下边界
        val dockTop = currentDockTop()
        val limitBottom = minOf(bottom, dockTop) - dp(8)
        if (limitBottom - barH < top) {
            dismissSelectionBar()
            return
        }
        y = y.coerceIn(top, limitBottom - barH)
        var x = (selCenterX - barW / 2f).toInt()
        x = x.coerceIn(left, maxOf(left, right - barW))

        syncSelectionBarState()
        if (popup.isShowing) popup.update(x, y, -1, -1)
        else popup.showAtLocation(binding.root, Gravity.TOP or Gravity.START, x, y)
        selBarDismissed = false
        // 浮条在场就轮不到系统的菜单，两边都出剪切/复制只会互相打架
        binding.richWeb.suppressSelectionActionMode = true
    }

    /** 只同步 B/I/U 的选中态，不动坐标。撤销重做等不改选区坐标的操作也走这里。 */
    private fun syncSelectionBarState() {
        val bar = selBar ?: return
        if (selBarDismissed) return
        val st = try {
            JSONObject(richStatesJson ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        bar.selBold.isSelected = st.optBoolean("b")
        bar.selItalic.isSelected = st.optBoolean("i")
        bar.selUnderline.isSelected = st.optBoolean("u")
    }

    private fun dismissSelectionBar() {
        binding.richWeb.suppressSelectionActionMode = false
        val popup = selPopup ?: return
        if (selBarDismissed) return
        selBarDismissed = true
        if (popup.isShowing) popup.dismiss()
    }

    /** 底部工具栏当前占用的顶部 y 坐标；没显示时不限制。 */
    private fun currentDockTop(): Int {
        val dock = dockingToolbar()
        if (dock.visibility != View.VISIBLE || dock.height == 0) return Int.MAX_VALUE
        val loc = IntArray(2)
        dock.getLocationOnScreen(loc)
        return loc[1]
    }

    /** 轻提示的下边界：有工具栏就贴在它上方，没有就贴在内容区底部。 */
    private fun tipBottomLimit(): Int {
        val dockTop = currentDockTop()
        if (dockTop != Int.MAX_VALUE) return dockTop
        val loc = IntArray(2)
        binding.root.getLocationOnScreen(loc)
        return loc[1] + binding.root.height - rootPad[3]
    }

    private fun evalRich(js: String) {
        if (isFinishing || isDestroyed || noteType != Project.TYPE_TEXT) return
        binding.richWeb.evaluateJavascript(js, null)
    }

    /**
     * 选区的剪切 / 复制。
     *
     * 不走 `document.execCommand('copy')` —— 那要求「激活的用户手势」，而我们是从
     * 原生按钮经 evaluateJavascript 异步进到 JS 的，手势早已过期，浏览器会直接拒掉。
     * 改为先在 JS 里取出选区的富文本，再由原生写进系统剪贴板。
     */
    private fun copySelection(cut: Boolean) {
        if (isFinishing || isDestroyed || noteType != Project.TYPE_TEXT) return
        val js = buildString {
            if (cut) append("var p=_sp();document.execCommand('delete');refreshState();")
            else append("var p=_sp();")
            append("p;")
        }
        binding.richWeb.evaluateJavascript(js) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val json = parseJsString(raw) ?: return@evaluateJavascript
            val payload = try {
                JSONObject(json)
            } catch (e: Exception) {
                return@evaluateJavascript
            }
            val text = payload.optString("t")
            if (text.isEmpty()) return@evaluateJavascript
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newHtmlText(getString(R.string.app_name), text, payload.optString("h")))
            toast(if (cut) R.string.sb_cut_done else R.string.sb_copied)
            if (cut) dismissSelectionBar()
        }
    }

    /**
     * 面板开合会从底部吃掉正文一块高度，光标原本待着的那一行就可能被挤出视口。
     * 这里在安全区（视口底部减去面板高度、再留 48dp 呼吸位）之外把内容往上顶，
     * 让选区始终落在视口上段。
     *
     * `isFinishing/isDestroyed` 守卫放在入口而不是调用点：所有触发路径
     * （布局回调、桥回调、post 出去的任务）都可能跑在销毁之后。
     */
    private fun evaluateAnchor() {
        if (isFinishing || isDestroyed) return
        if (noteType != Project.TYPE_TEXT) return
        // 软键盘未弹出时处于浏览/阅读状态，绝不自动滚动正文或抢视口
        if (lastImeVisible != true) return
        val rect = selRect ?: return
        val web = binding.richWeb
        val h = web.height
        if (h <= 0) return
        val dockTop = currentDockTop()
        val webLoc = IntArray(2)
        web.getLocationOnScreen(webLoc)
        val visibleWebHeight = if (dockTop != Int.MAX_VALUE && dockTop > webLoc[1]) {
            dockTop - webLoc[1]
        } else {
            h
        }
        val safeBottom = (visibleWebHeight - dp(16)).coerceAtLeast(dp(100))
        val selBottom = rect.top + rect.height
        if (selBottom <= safeBottom) {
            anchorArmed = true
            return
        }
        if (!anchorArmed) return
        anchorArmed = false
        selScrollTo(web.scrollY + (selBottom - (safeBottom - dp(8))))
    }

    /** 平滑滚动到目标 scrollY；中途来了新指令就取消上一条，避免两条动画打架。 */
    private fun selScrollTo(targetY: Int) {
        val web = binding.richWeb
        val from = web.scrollY
        val to = targetY.coerceAtLeast(0)
        if (from == to) {
            anchorArmed = true
            return
        }
        selScrollAnimator?.cancel()
        selScrollAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (isFinishing || isDestroyed) return@addUpdateListener
                web.scrollTo(web.scrollX, it.animatedValue as Int)
            }
            start()
        }
    }

    private fun updateSegments(active: View) {
        listOf(binding.segBody, binding.segTitle, binding.segSubtitle, binding.segHeading).forEach {
            it.isSelected = (it == active)
        }
    }

    private fun toggleAaPanel() {
        val willShow = binding.panelTextBlock.visibility != View.VISIBLE
        (binding.toolbarText.parent as? ViewGroup)?.let { parent ->
            android.transition.TransitionManager.beginDelayedTransition(
                parent,
                android.transition.AutoTransition().apply {
                    interpolator = CouiMotion.EASE_INTERPOLATOR
                    duration = 220
                }
            )
        }
        binding.panelTextBlock.visibility = if (willShow) View.VISIBLE else View.GONE
        binding.btnToggleAa.isSelected = willShow
    }

    private fun visiblePanelHeight(): Int =
        if (binding.panelTextBlock.visibility == View.VISIBLE) binding.panelTextBlock.height else 0

    private fun closePanels() {
        if (binding.panelTextBlock.visibility == View.VISIBLE) {
            (binding.toolbarText.parent as? ViewGroup)?.let { parent ->
                android.transition.TransitionManager.beginDelayedTransition(
                    parent,
                    android.transition.AutoTransition().apply {
                        interpolator = CouiMotion.EASE_INTERPOLATOR
                        duration = 180
                    }
                )
            }
            binding.panelTextBlock.visibility = View.GONE
            binding.btnToggleAa.isSelected = false
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
        // 二级：ColorOS 分段标题 [ 正文 | 大标题 | 中标题 | 小标题 ]
        binding.segBody.isSelected = normalized in listOf("p", "div", "") || (normalized !in listOf("h1", "h2", "h3", "blockquote"))
        binding.segTitle.isSelected = normalized == "h1"
        binding.segSubtitle.isSelected = normalized == "h2"
        binding.segHeading.isSelected = normalized == "h3"
        // 二级：列表/引用/待办
        binding.btnUl.isSelected = st.optBoolean("ul")
        binding.btnOl.isSelected = st.optBoolean("ol")
        binding.btnQuote.isSelected = normalized == "blockquote"
        binding.btnTodo.isSelected = st.optBoolean("todo")
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
                showSaveState(getString(R.string.status_editing), onSurfaceVariantColor, sticky = true)
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
            // 优先用 JS 回传的快照：它同步可取，不会像 evaluateJavascript 那样
            // 被 WebView 的销毁抢在前面（onPause 保存时正是这种情况）。
            richCache?.let { onDone(it); return }
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

    private fun doDone() {
        if (lastImeVisible == true) {
            hideKeyboard()
        } else {
            saveAndExit()
        }
    }

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
        sheet.itemCopy.setOnClickListener {
            dialog.dismiss()
            collectContent { content ->
                if (content != null) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = if (noteType == Project.TYPE_TEXT) htmlToPlain(content) else content
                    cm.setPrimaryClip(ClipData.newPlainText("note", text))
                    toast(R.string.copied)
                }
            }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, binding.edTitle.text.ifBlank { defaultTitle() }))
            .setPositiveButton(R.string.delete) { _, _ ->
                saving = true
                richDraftChanged = false
                htmlDraftChanged = false
                val id = projectId
                projectId = null
                id?.let { store.delete(it) }
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
                    4 -> insertImage()
                    5 -> copyAllCode()
                }
            }
            .show()
    }

    // ---------------- 链接 / 图片插入对话框 ----------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 切换悬浮工具栏的形态。
     * 键盘收起时收成小工具条并收起二级格式面板与选区浮条，方便阅读；键盘弹出时展开。
     */
    private fun updateDockMode(expanded: Boolean) {
        val isText = noteType == Project.TYPE_TEXT
        binding.toolbarText.visibility = if (isText) View.VISIBLE else View.GONE
        binding.toolbarCode.visibility = if (isText) View.GONE else View.VISIBLE
        dockingToolbar().animate().cancel()
        dockingToolbar().translationY = 0f
        if (!expanded) {
            closePanels()
            dismissSelectionBar()
        }
        updateSelectionBar()
    }

    /** 当前形态对应的悬浮工具栏容器（文字便签 / HTML 便签各一个）。 */
    private fun dockingToolbar(): View =
        if (noteType == Project.TYPE_TEXT) binding.toolbarText else binding.toolbarCode

    /** 收起软键盘；同时收起二级排版面板与选区浮条。 */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        closePanels()
        dismissSelectionBar()
    }

    /** 点击胶囊：聚焦正文并唤起键盘，工具栏随键盘一起展开。 */
    private fun focusBody() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        if (noteType == Project.TYPE_TEXT) {
            binding.richWeb.requestFocus()
            binding.richWeb.evaluateJavascript(
                "var ed=document.getElementById('ed');if(ed)ed.focus();", null
            )
            imm?.showSoftInput(binding.richWeb, InputMethodManager.SHOW_IMPLICIT)
            ViewCompat.getWindowInsetsController(binding.richWeb)
                ?.show(WindowInsetsCompat.Type.ime())
        } else {
            binding.edContent.requestFocus()
            imm?.showSoftInput(binding.edContent, InputMethodManager.SHOW_IMPLICIT)
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

    /** 移动端智能图片插入：底部弹窗支持「手机相册（推荐）」与「网络图片」双入口 */
    private fun insertImage() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetInsertImageBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        // 触控缩放微动效
        sheet.cardPickGallery.applyColorOsPressAnim(0.96f)
        sheet.cardInputUrl.applyColorOsPressAnim(0.96f)

        sheet.cardPickGallery.setOnClickListener {
            dialog.dismiss()
            pickImageLauncher.launch("image/*")
        }

        sheet.cardInputUrl.setOnClickListener {
            dialog.dismiss()
            showImageUrlDialog()
        }

        dialog.show()
    }

    /** 处理系统相册选择的本地图片（后台安全采样 + EXIF 角度校正 + Base64 嵌入） */
    private fun handlePickedImage(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dataUri = ImageHelper.processPickedImage(this@EditorActivity, uri)
            withContext(Dispatchers.Main) {
                if (dataUri != null) {
                    val targetReplaceId = pendingReplaceImgId
                    pendingReplaceImgId = null
                    if (targetReplaceId != null && noteType == Project.TYPE_TEXT) {
                        val js = "(function(){" +
                            "var img=document.getElementById(${JSONObject.quote(targetReplaceId)});" +
                            "if(img){img.src=${JSONObject.quote(dataUri)};refreshState();HtmlNotes.onDirty();}" +
                            "})();"
                        binding.richWeb.evaluateJavascript(js, null)
                        markDirty()
                        toast(R.string.toast_image_replaced)
                        return@withContext
                    }

                    if (noteType == Project.TYPE_TEXT) {
                        val imgId = "img_${System.currentTimeMillis()}"
                        val imgHtml = "<p class=\"note-img-container\"><img id=\"$imgId\" src=\"$dataUri\" alt=\"image\" /></p><p><br></p>"
                        exec("insertHTML", imgHtml)
                    } else {
                        val imgHtml = "<img src=\"$dataUri\" alt=\"image\" style=\"max-width:100%; border-radius:12px;\" />\n"
                        insertCodeSnippet(imgHtml)
                    }
                    markDirty()
                    toast(R.string.toast_image_inserted)
                } else {
                    toast(R.string.image_load_failed)
                }
            }
        }
    }

    private fun showImageUrlDialog() {
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
            .setTitle(R.string.insert_image_url)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    if (noteType == Project.TYPE_TEXT) {
                        val imgId = "img_${System.currentTimeMillis()}"
                        val imgHtml = "<p class=\"note-img-container\"><img id=\"$imgId\" src=\"$url\" alt=\"image\" /></p><p><br></p>"
                        exec("insertHTML", imgHtml)
                    } else {
                        val imgHtml = "<img src=\"$url\" alt=\"image\" style=\"max-width:100%; border-radius:12px;\" />\n"
                        insertCodeSnippet(imgHtml)
                    }
                    markDirty()
                    toast(R.string.toast_image_inserted)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 弹出图片交互与排版操作底栏 */
    private fun showImageActionSheet(imgId: String, src: String, align: String = "center") {
        val sheet = SheetImageActionBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet.root)

        // 绑定图片缩略图
        val thumb = ImageHelper.decodeDataUriOrUrl(src)
        if (thumb != null) {
            sheet.imgThumb.setImageBitmap(thumb)
            sheet.imgThumb.imageTintList = null
        }

        // 触控缩放微动效
        listOf(
            sheet.itemActionViewFull,
            sheet.itemActionMoveUp,
            sheet.itemActionMoveDown,
            sheet.itemActionInsertAbove,
            sheet.itemActionInsertBelow,
            sheet.itemActionReplace,
            sheet.itemActionDelete
        ).forEach { it.applyColorOsPressAnim(0.96f) }

        // 对齐方式分段选择器初始化与事件
        fun updateAlignSegment(active: View) {
            listOf(sheet.segAlignCenter, sheet.segAlignLeft, sheet.segAlignRight, sheet.segAlignFull).forEach {
                it.isSelected = (it == active)
            }
        }
        when (align) {
            "left" -> updateAlignSegment(sheet.segAlignLeft)
            "right" -> updateAlignSegment(sheet.segAlignRight)
            "full" -> updateAlignSegment(sheet.segAlignFull)
            else -> updateAlignSegment(sheet.segAlignCenter)
        }

        sheet.segAlignCenter.setOnClickListener {
            updateAlignSegment(sheet.segAlignCenter)
            setImageAlign(imgId, "align-center")
        }
        sheet.segAlignLeft.setOnClickListener {
            updateAlignSegment(sheet.segAlignLeft)
            setImageAlign(imgId, "align-left")
        }
        sheet.segAlignRight.setOnClickListener {
            updateAlignSegment(sheet.segAlignRight)
            setImageAlign(imgId, "align-right")
        }
        sheet.segAlignFull.setOnClickListener {
            updateAlignSegment(sheet.segAlignFull)
            setImageAlign(imgId, "align-full")
        }

        // 1. 查看大图
        sheet.itemActionViewFull.setOnClickListener {
            dialog.dismiss()
            showFullScreenImageViewer(src)
        }

        // 上移一行
        sheet.itemActionMoveUp.setOnClickListener {
            dialog.dismiss()
            val js = "(function(){" +
                "var ed=document.getElementById('ed');" +
                "var img=document.getElementById(${JSONObject.quote(imgId)});" +
                "if(!ed||!img)return false;" +
                "var block=getImageBlock(img,ed);" +
                "if(!block||!block.parentNode)return false;" +
                "var prev=block.previousElementSibling;" +
                "while(prev&&prev.classList.contains('img-drop-indicator')){prev=prev.previousElementSibling;}" +
                "if(prev){" +
                "block.parentNode.insertBefore(block,prev);" +
                "refreshState();HtmlNotes.onDirty();return true;" +
                "}" +
                "return false;" +
                "})();"
            binding.richWeb.evaluateJavascript(js) { res ->
                if (res == "true" || res == "\"true\"") {
                    binding.richWeb.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    toast(R.string.toast_image_moved)
                    markDirty()
                }
            }
        }

        // 下移一行
        sheet.itemActionMoveDown.setOnClickListener {
            dialog.dismiss()
            val js = "(function(){" +
                "var ed=document.getElementById('ed');" +
                "var img=document.getElementById(${JSONObject.quote(imgId)});" +
                "if(!ed||!img)return false;" +
                "var block=getImageBlock(img,ed);" +
                "if(!block||!block.parentNode)return false;" +
                "var next=block.nextElementSibling;" +
                "while(next&&next.classList.contains('img-drop-indicator')){next=next.nextElementSibling;}" +
                "if(next){" +
                "block.parentNode.insertBefore(block,next.nextElementSibling);" +
                "refreshState();HtmlNotes.onDirty();return true;" +
                "}" +
                "return false;" +
                "})();"
            binding.richWeb.evaluateJavascript(js) { res ->
                if (res == "true" || res == "\"true\"") {
                    binding.richWeb.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    toast(R.string.toast_image_moved)
                    markDirty()
                }
            }
        }

        // 2. 在上方换行输入文字
        sheet.itemActionInsertAbove.setOnClickListener {
            dialog.dismiss()
            val js = "(function(){" +
                "var ed=document.getElementById('ed');" +
                "var img=document.getElementById(${JSONObject.quote(imgId)});" +
                "if(!ed||!img)return;" +
                "var block=getImageBlock(img,ed);" +
                "var p=document.createElement('p');p.innerHTML='<br>';" +
                "if(block&&block.parentNode){" +
                "block.parentNode.insertBefore(p,block);" +
                "}else{" +
                "img.parentElement.insertBefore(p,img);" +
                "}" +
                "var r=document.createRange();r.setStart(p,0);r.collapse(true);" +
                "var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
                "ed.focus();" +
                "refreshState();HtmlNotes.onDirty();HtmlNotes.onEditorTap();" +
                "})();"
            binding.richWeb.evaluateJavascript(js, null)
        }

        // 3. 在下方换行输入文字
        sheet.itemActionInsertBelow.setOnClickListener {
            dialog.dismiss()
            val js = "(function(){" +
                "var ed=document.getElementById('ed');" +
                "var img=document.getElementById(${JSONObject.quote(imgId)});" +
                "if(!ed||!img)return;" +
                "var block=getImageBlock(img,ed);" +
                "var p=document.createElement('p');p.innerHTML='<br>';" +
                "if(block&&block.parentNode){" +
                "block.parentNode.insertBefore(p,block.nextElementSibling);" +
                "}else{" +
                "img.parentElement.insertBefore(p,img.nextSibling);" +
                "}" +
                "var r=document.createRange();r.setStart(p,0);r.collapse(true);" +
                "var s=window.getSelection();s.removeAllRanges();s.addRange(r);" +
                "ed.focus();" +
                "refreshState();HtmlNotes.onDirty();HtmlNotes.onEditorTap();" +
                "})();"
            binding.richWeb.evaluateJavascript(js, null)
        }

        // 4. 更换图片
        sheet.itemActionReplace.setOnClickListener {
            dialog.dismiss()
            pendingReplaceImgId = imgId
            pickImageLauncher.launch("image/*")
        }

        // 5. 删除此图片
        sheet.itemActionDelete.setOnClickListener {
            dialog.dismiss()
            val js = "(function(){" +
                "var ed=document.getElementById('ed');" +
                "var img=document.getElementById(${JSONObject.quote(imgId)});" +
                "if(!ed||!img)return;" +
                "var block=getImageBlock(img,ed);" +
                "if(block&&block!==img){" +
                "block.remove();" +
                "}else{" +
                "img.remove();" +
                "}" +
                "refreshState();HtmlNotes.onDirty();" +
                "})();"
            binding.richWeb.evaluateJavascript(js, null)
            markDirty()
            toast(R.string.toast_image_deleted)
        }

        dialog.setOnDismissListener {
            evalRich("document.querySelectorAll('#ed img.selected').forEach(function(el){el.classList.remove('selected');});")
        }

        dialog.show()
    }

    private fun setImageAlign(imgId: String, alignClass: String) {
        val js = "(function(){" +
            "var img=document.getElementById(${JSONObject.quote(imgId)});" +
            "if(!img)return;" +
            "img.classList.remove('align-left','align-center','align-right','align-full');" +
            "img.classList.add(${JSONObject.quote(alignClass)});" +
            "var p=img.parentElement;" +
            "if(p&&p.classList.contains('note-img-container')){" +
            "if(${JSONObject.quote(alignClass)}==='align-left')p.style.textAlign='left';" +
            "else if(${JSONObject.quote(alignClass)}==='align-right')p.style.textAlign='right';" +
            "else p.style.textAlign='center';" +
            "}" +
            "refreshState();HtmlNotes.onDirty();" +
            "})();"
        binding.richWeb.evaluateJavascript(js, null)
        markDirty()
    }

    /** 沉浸式全屏原图查看器 */
    private fun showFullScreenImageViewer(src: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val fullBinding = DialogFullscreenImageBinding.inflate(layoutInflater)
        dialog.setContentView(fullBinding.root)

        val bitmap = ImageHelper.decodeDataUriOrUrl(src)
        if (bitmap != null) {
            fullBinding.imgFullScreen.setImageBitmap(bitmap)
        }

        fullBinding.btnCloseFullscreen.applyColorOsPressAnim(0.9f)
        fullBinding.btnCloseFullscreen.setOnClickListener {
            dialog.dismiss()
        }
        fullBinding.root.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
