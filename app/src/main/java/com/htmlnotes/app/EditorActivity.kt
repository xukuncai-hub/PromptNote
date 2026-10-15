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
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
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
import com.htmlnotes.app.databinding.ViewSelectionBarBinding
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

    /**
     * CursorKeeper 的注销函数。必须持有并在 onDestroy 调用：它会摘掉全局布局监听，
     * 并撤掉那个 120ms 的延迟 blur —— 延迟任务会对 WebView 调 evaluateJavascript，
     * 而 onDestroy 紧接着就 destroy 掉它。
     */
    private var cursorKeeperDetach: (() -> Unit)? = null

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

        // ---- 系统栏 + 键盘 insets：统一用 WindowInsets 计算底部 padding ----
        // 用 IME insets 而非 getWindowVisibleDisplayFrame（后者在 Android 15 边到边下不可靠，
        // 曾导致键盘弹出时底部 padding 失效、悬浮工具栏被键盘盖住）
        // 记录当前生效的四边 padding。View.setPadding 不比较旧值、每次都无条件 requestLayout，
        // 而 insets 的分发远比键盘动画本身频繁，不加这层判断会让整棵树多做很多次无用布局。
        var padL = -1
        var padT = -1
        var padR = -1
        var padB = -1
        /** 最近一次收到的 insets：键盘动画结束时用它把最终 padding 一次落地。 */
        var lastInsets: WindowInsetsCompat? = null

        /** 键盘可见性驱动工具栏形态：收起时胶囊、弹出时展开。 */
        fun syncDockMode(insets: WindowInsetsCompat) {
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (imeVisible != lastImeVisible) {
                lastImeVisible = imeVisible
                updateDockMode(imeVisible)
            }
        }

        /** 静态 insets（首帧 / 旋转 / 导航栏变化 / 键盘动画收尾）：把 padding 落到根布局。 */
        fun applyInsets(insets: WindowInsetsCompat) {
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            syncDockMode(insets)
            val l = sys.left
            val t = sys.top
            val r = sys.right
            val b = maxOf(sys.bottom, ime.bottom)
            if (l == padL && t == padT && r == padR && b == padB) return
            padL = l
            padT = t
            padR = r
            padB = b
            rootPad[0] = l
            rootPad[1] = t
            rootPad[2] = r
            rootPad[3] = b
            binding.root.setPadding(l, t, r, b)
            // 键盘起落会同时改变正文和工具栏的位置，浮条得跟着重算
            updateSelectionBar()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            lastInsets = insets
            applyInsets(insets)
            insets
        }

        /*
         * 键盘动画期间只平移悬浮工具栏，不逐帧改 root padding。
         *
         * setPadding 会无条件 requestLayout，逐帧改 padding 等于每帧让整棵树重新测量布局，
         * WebView / 代码编辑器还要跟着重排内容 —— 这才是工具栏跟随键盘「不丝滑」的主因。
         * 改成只动 translationY（纯绘制属性，不触发布局），键盘收放全程顺滑；
         * 动画结束时再一次性落地最终 padding，并同一帧内把平移归零，落点不变、看不到跳动。
         */
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    lastInsets = insets
                    syncDockMode(insets)
                    val sysBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
                    val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    // 工具栏要停在 max(导航栏, 键盘) 之上；它当前按 padB 让位，
                    // 差额用平移补齐。键盘弹出与收起两个方向都成立。
                    val base = if (padB < 0) sysBottom else padB
                    dockingToolbar().translationY = (base - maxOf(sysBottom, imeBottom)).toFloat()
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    // 先同步落地最终 padding，再清平移：两者在同一帧内换手，位置连续
                    lastInsets?.let { applyInsets(it) }
                    dockingToolbar().translationY = 0f
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

        // 全部格式按钮附加按压缩放触感
        listOf(
            binding.btnBack, binding.btnUndo, binding.btnRedo,
            binding.btnMore, binding.btnDone, binding.btnPreview,
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
        ).forEach { it.attachPressScale(0.92f, 90L) }

        binding.edTitle.doAfterTextChanged {
            markDirty()
            updateMeta()
        }

        // ---- 文字模式格式工具栏（直观单层平铺，1-Tap 即达） ----
        binding.btnTB.setOnClickListener { exec("bold", null) }
        binding.btnTI.setOnClickListener { exec("italic", null) }
        binding.btnTU.setOnClickListener { exec("underline", null) }
        binding.btnTS.setOnClickListener { exec("strikeThrough", null) }
        binding.btnTS.paintFlags = binding.btnTS.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG

        binding.segTitle.setOnClickListener { exec("formatBlock", "<h1>") }
        binding.segSubtitle.setOnClickListener { exec("formatBlock", "<h2>") }
        binding.segHeading.setOnClickListener { exec("formatBlock", "<h3>") }
        binding.segBody.setOnClickListener { exec("formatBlock", "<p>") }

        binding.btnTodo.setOnClickListener {
            exec("insertHTML", "<div><input type=\"checkbox\"> &nbsp;</div>")
            toast(R.string.todo_menu)
        }
        binding.btnUl.setOnClickListener { exec("insertUnorderedList", null) }
        binding.btnOl.setOnClickListener { exec("insertOrderedList", null) }
        binding.btnQuote.setOnClickListener { exec("formatBlock", "<blockquote>") }

        binding.btnAlignL.setOnClickListener { exec("justifyLeft", null) }
        binding.btnAlignC.setOnClickListener { exec("justifyCenter", null) }
        binding.btnAlignR.setOnClickListener { exec("justifyRight", null) }

        binding.btnColor.setOnClickListener { pickColor() }
        binding.btnHl.setOnClickListener { exec("hiliteColor", "#FFEB3B") }
        binding.btnSize.setOnClickListener { pickSize() }
        binding.btnFont.setOnClickListener { cycleFontFamily() }

        binding.btnLink.setOnClickListener { insertLink() }
        binding.btnImage.setOnClickListener { insertImage() }
        binding.btnCode.setOnClickListener { exec("inlineCode", null) }
        binding.btnHr.setOnClickListener { exec("insertHorizontalRule", null); toast(R.string.toast_hr) }
        binding.btnOutdent.setOnClickListener { exec("outdent", null) }
        binding.btnIndent.setOnClickListener { exec("indent", null) }
        binding.btnClear.setOnClickListener { exec("removeFormat", null); toast(R.string.rich_clear) }
        binding.btnIndent.setOnClickListener { exec("indent", null) }
        binding.btnCode.setOnClickListener { exec("inlineCode", null) }
        binding.btnLink.setOnClickListener { insertLink() }
        binding.btnImage.setOnClickListener { insertImage() }
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
        binding.btnSnImage.setOnClickListener {
            val sn = "<img src=\"https://\" alt=\"\">"
            insertCodeSnippet(sn, sn.indexOf("https://"), "https://".length); toast(R.string.toast_image)
        }
        binding.btnSnUl.setOnClickListener { insertCodeSnippet("<ul>\n  <li></li>\n</ul>\n"); toast(R.string.rich_ul) }
        binding.btnSnHr.setOnClickListener { insertCodeSnippet("<hr>\n"); toast(R.string.toast_hr) }
        binding.btnSnTodo.setOnClickListener { insertCodeSnippet("☐ "); toast(R.string.todo_menu) }
        binding.btnSnCheck.setOnClickListener { insertCodeSnippet("☑ "); toast(R.string.check_list) }
        binding.btnCopyAll.setOnClickListener { copyAllCode() }

        cursorKeeperDetach = CursorKeeper.attach(this, binding.edTitle, binding.edContent, binding.richWeb)
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
        showSaveState(getString(R.string.unsaved), onSurfaceVariantColor, sticky = true)
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
        binding.saveState.animate().alpha(1f).setDuration(160).start()
        if (!sticky) {
            binding.saveState.postDelayed(hideSaveState, 2400)
        }
    }

    private val hideSaveState = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
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
        // 摘掉 CursorKeeper 的全局布局监听与它挂起的延迟 blur（会碰 WebView）
        cursorKeeperDetach?.invoke()
        cursorKeeperDetach = null
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
                        // 正文自己滚、以及原生主动滚，都会让选区的视口坐标变化：
                        // 不订阅的话滚动之后原生拿到的还是旧坐标，浮条和锚定全都会错位。
                        "window.addEventListener('scroll',refreshState,true);" +
                        // 点正文：收起二级面板（原生收到后会再判一次锚定）
                        "ed.addEventListener('click',function(){HtmlNotes.onEditorTap();});" +
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
                showSaveState(getString(R.string.unsaved), onSurfaceVariantColor, sticky = true)
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

        /** 用户点到正文上：把标题框焦点清除（让标题光标消失），并收起面板。 */
        @JavascriptInterface
        fun onEditorTap() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (binding.edTitle.hasFocus()) {
                    binding.edTitle.clearFocus()
                }
                closePanels()
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
        evaluateAnchor()
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
     * 浮条的可见性与坐标。选区折叠（只有光标）时立刻收起 —— 此时该由系统的
     * insertion 模式接管（粘贴气泡），两套并存只会互相打架。
     */
    private fun updateSelectionBar() {
        val popup = selPopup ?: return
        if (isFinishing || isDestroyed) return
        val rect = selRect
        if (noteType != Project.TYPE_TEXT || rect == null || rect.collapsed) {
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
        val rect = selRect ?: return
        val web = binding.richWeb
        val h = web.height
        if (h <= 0) return
        val bottomMargin = if (visiblePanelHeight() > 0) dp(48) else 0
        val safeBottom = h - bottomMargin
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

    private fun visiblePanelHeight(): Int = 0

    private fun closePanels() {}

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
            else -> -1
        }
        val segments = listOf(
            binding.segTitle, binding.segSubtitle, binding.segHeading, binding.segBody
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
     * 停在键盘正上方。
     */
    private fun updateDockMode(expanded: Boolean) {
        val isText = noteType == Project.TYPE_TEXT
        binding.toolbarText.visibility = if (isText) View.VISIBLE else View.GONE
        binding.toolbarCode.visibility = if (isText) View.GONE else View.VISIBLE
        updateSelectionBar()
    }

    /** 当前形态对应的悬浮工具栏容器（文字便签 / HTML 便签各一个）。 */
    private fun dockingToolbar(): View =
        if (noteType == Project.TYPE_TEXT) binding.toolbarText else binding.toolbarCode

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
