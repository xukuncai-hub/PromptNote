package com.htmlnotes.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.htmlnotes.app.databinding.ActivityMainBinding
import java.nio.charset.Charset

/** 搜索框输入防抖窗口：连续输入时只跑最后一次过滤。 */
private const val SEARCH_DEBOUNCE_MS = 300L

/** 列表滚动多少像素才判定为「有意滚动」，避免手指微抖时 FAB 反复收放。 */
private const val FAB_SCROLL_THRESHOLD = 6

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProjectStore
    private lateinit var adapter: ProjectAdapter
    private var allItems: List<Project> = emptyList()
    private var isGrid: Boolean = true
    private var lastThemeColor = ThemeHelper.DEFAULT
    private val searchHandler = Handler(Looper.getMainLooper())

    /**
     * CursorKeeper 的注销函数。退出搜索态时调用（此后光标不再被自动清除），
     * onDestroy 也要兜底调一次 —— 否则它注册的全局布局监听会一直留到 Activity 销毁。
     */
    private var cursorKeeperDetach: (() -> Unit)? = null
    private val searchTask = Runnable { applyFilter() }
    /** 便签目录扫描 + JSON 解析在后台线程进行，期间合并多余的刷新请求。 */
    private var loading = false
    private var pendingRefresh = false
    /** 首次后台载入完成前不显示空列表提示，避免冷启动闪现「暂无便签」。 */
    private var loadedOnce = false
    /** FAB 当前是否处于收起态（随列表滚动让位）。 */
    private var fabHidden = false

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val rawText = decodeText(bytes)
                    if (rawText.isNotBlank()) {
                        val isHtml = looksLikeHtml(rawText)
                        val noteType = if (isHtml) Project.TYPE_HTML else Project.TYPE_TEXT
                        val extracted = if (isHtml) extractTitle(rawText) else null
                        val title = extracted ?: getString(R.string.imported_note)
                        store.create(title, rawText, noteType)
                        refresh()
                    }
                }
            }
        }

    /** 编码自动识别：优先 UTF-8，遇到乱码字符则回退到 GBK（兼容旧版 Windows 导出的 HTML 文件） */
    private fun decodeText(bytes: ByteArray): String {
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (!utf8.contains('\uFFFD')) utf8 else String(bytes, Charset.forName("GBK"))
        } catch (e: Exception) {
            String(bytes, Charset.defaultCharset())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        lastThemeColor = ThemeHelper.currentIndex(this)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge：toolbar 背景伸入状态栏，列表/FAB 避让导航栏与刘海
        binding.toolbar.applySystemBarInsets(top = true)
        binding.recycler.applySystemBarInsets(left = true, right = true, bottom = true)
        binding.fabNew.applySystemBarInsets(right = true, bottom = true, asMargin = true)

        store = ProjectStore(this)
        isGrid = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("grid", true)
        adapter = ProjectAdapter(emptyList()) { project, action, card ->
            when (action) {
                ProjectAdapter.Action.OPEN -> openPreview(project, card)
                ProjectAdapter.Action.EDIT -> openEditor(project, card = card)
                ProjectAdapter.Action.DELETE -> confirmDelete(project)
                ProjectAdapter.Action.PIN -> showNoteMenu(project)
            }
        }
        binding.recycler.layoutManager = GridLayoutManager(this, if (isGrid) 2 else 1)
        binding.recycler.adapter = adapter
        // 条目增删/移动的过渡时长略放长，配合 DiffUtil 的增量更新更顺滑
        binding.recycler.itemAnimator = DefaultItemAnimator().apply {
            addDuration = 200
            removeDuration = 180
            moveDuration = 220
            changeDuration = 160
        }
        // FAB 随列表滚动让位：下滑收起、上滑或回到顶部展开
        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                when {
                    dy > FAB_SCROLL_THRESHOLD -> setFabHidden(true)
                    dy < -FAB_SCROLL_THRESHOLD -> setFabHidden(false)
                    !rv.canScrollVertically(-1) -> setFabHidden(false)
                }
            }
        })

        binding.fabNew.setOnClickListener { showTypeChooser() }
        binding.btnEmptyCreate.setOnClickListener { showTypeChooser() }
        binding.fabNew.attachPressScale(0.92f, 100L)
        binding.btnEmptyCreate.attachPressScale(0.95f, 100L)
        binding.searchEdit.doAfterTextChanged {
            searchHandler.removeCallbacks(searchTask)
            searchHandler.postDelayed(searchTask, SEARCH_DEBOUNCE_MS)
        }

        // 新建按钮弹出动画（弹性放大）
        binding.fabNew.post {
            binding.fabNew.scaleX = 0.5f
            binding.fabNew.scaleY = 0.5f
            binding.fabNew.alpha = 0f
            binding.fabNew.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                .start()
        }

        binding.toolbar.inflateMenu(R.menu.menu_main)
        updateToggleIcon()

        // 返回键：搜索框聚焦时先退出搜索态（清空搜索词、收起键盘），再次按返回退出应用
        val forgetSearch = CursorKeeper.attach(this, binding.searchEdit)
        cursorKeeperDetach = forgetSearch
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchEdit.hasFocus()) {
                    forgetSearch()
                    binding.searchEdit.text?.clear()
                    binding.searchEdit.clearFocus()
                    binding.root.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(binding.searchEdit.windowToken, 0)
                } else {
                    finish()
                }
            }
        })

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> {
                    importLauncher.launch(arrayOf("text/html", "text/plain", "*/*"))
                    true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
                    true
                }
                R.id.action_toggle_layout -> {
                    toggleLayout()
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 设置页修改主题色后返回，重建以应用新色
        val themeColor = ThemeHelper.currentIndex(this)
        if (themeColor != lastThemeColor) {
            lastThemeColor = themeColor
            recreate()
            return
        }
        // 同步设置中的默认视图
        val grid = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("grid", true)
        if (grid != isGrid) {
            isGrid = grid
            (binding.recycler.layoutManager as GridLayoutManager).spanCount = if (isGrid) 2 else 1
            updateToggleIcon()
        }
        refresh()
    }

    override fun onDestroy() {
        searchHandler.removeCallbacks(searchTask)
        super.onDestroy()
    }

    /** 后台线程读取便签文件，回主线程刷新列表；加载期间的重复请求合并为最后一次。 */
    private fun refresh() {
        if (loading) {
            pendingRefresh = true
            return
        }
        loading = true
        Thread {
            val items = store.list()
            runOnUiThread {
                loading = false
                if (!isFinishing && !isDestroyed) {
                    val wasEmpty = allItems.isEmpty()
                    allItems = items
                    loadedOnce = true
                    applyFilter()
                    // 卡片错落入场动画只在首次载入时播放：每次 onResume 都重放会让从编辑器返回时整个列表跳动
                    if (wasEmpty && allItems.isNotEmpty()) binding.recycler.scheduleLayoutAnimation()
                }
                if (pendingRefresh) {
                    pendingRefresh = false
                    refresh()
                }
            }
        }.start()
    }

    private fun applyFilter() {
        val query = binding.searchEdit.text?.toString()?.trim().orEmpty()
        val filtered = if (query.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.content.contains(query, ignoreCase = true)
            }
        }
        adapter.submit(filtered)
        binding.emptyLayout.fadeVisibility(allItems.isEmpty() && loadedOnce)
        binding.noResult.fadeVisibility(allItems.isNotEmpty() && filtered.isEmpty())
        // 内容被删到不足以滚动时列表会被拉回顶部，此时务必让 FAB 重新可见
        binding.recycler.post {
            if (!binding.recycler.canScrollVertically(-1)) setFabHidden(false)
        }
    }

    /** 收起 / 展开新建按钮：收起时同时禁用点击，避免点到看不见的按钮。 */
    private fun setFabHidden(hidden: Boolean) {
        if (fabHidden == hidden) return
        fabHidden = hidden
        binding.fabNew.isClickable = !hidden
        binding.fabNew.animate()
            .alpha(if (hidden) 0f else 1f)
            .scaleX(if (hidden) 0.6f else 1f)
            .scaleY(if (hidden) 0.6f else 1f)
            .setDuration(180)
            .start()
    }

    /** 切换列表 / 双列网格视图，并记住用户偏好。 */
    private fun toggleLayout() {
        isGrid = !isGrid
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("grid", isGrid).apply()
        (binding.recycler.layoutManager as GridLayoutManager).spanCount = if (isGrid) 2 else 1
        binding.recycler.scheduleLayoutAnimation()
        updateToggleIcon()
    }

    /** 图标显示的是切换目标视图：双列时显示列表图标，列表时显示双列图标。 */
    private fun updateToggleIcon() {
        binding.toolbar.menu.findItem(R.id.action_toggle_layout)
            ?.setIcon(if (isGrid) R.drawable.ic_list else R.drawable.ic_grid)
    }

    /** 文字便签打开即编辑（所见即所得），HTML 便签打开预览。card 非空时走容器变换过渡。 */
    private fun openPreview(p: Project, card: android.view.View?) {
        if (p.type == Project.TYPE_TEXT) {
            openEditor(p, card = card)
        } else {
            startActivity(
                Intent(this, PreviewActivity::class.java).apply {
                    putExtra("id", p.id)
                    if (card != null) NoteTransition.putStartBounds(this, card)
                }
            )
            if (card == null) overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        }
    }

    private fun showTypeChooser() {
        val options = arrayOf(getString(R.string.text_note), getString(R.string.html_note))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_type_title)
            .setItems(options) { _, which ->
                openEditor(
                    null,
                    if (which == 0) Project.TYPE_TEXT else Project.TYPE_HTML
                )
            }
            .show()
    }

    private fun openEditor(p: Project?, type: String = Project.TYPE_HTML, card: android.view.View? = null) {
        startActivity(
            Intent(this, EditorActivity::class.java).apply {
                if (p != null) putExtra("id", p.id) else putExtra("type", type)
                if (card != null) NoteTransition.putStartBounds(this, card)
            }
        )
        if (card == null) overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    /** 长按菜单：置顶 / 取消置顶、删除。 */
    private fun showNoteMenu(p: Project) {
        val items = arrayOf(
            getString(if (p.pinned) R.string.unpin else R.string.pin),
            getString(R.string.delete)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(p.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        store.togglePin(p.id)
                        refresh()
                    }
                    1 -> confirmDelete(p)
                }
            }
            .show()
    }

    private fun confirmDelete(p: Project) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, p.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(p.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
