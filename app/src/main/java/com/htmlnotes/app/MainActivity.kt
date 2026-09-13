package com.htmlnotes.app

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.htmlnotes.app.databinding.ActivityMainBinding
import java.nio.charset.Charset

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ProjectStore
    private lateinit var adapter: ProjectAdapter
    private var allItems: List<Project> = emptyList()
    private var isGrid: Boolean = true
    private var lastThemeColor = ThemeHelper.DEFAULT

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charset.forName("UTF-8"))
                    ?.use { it.readText() }
                if (!text.isNullOrEmpty()) {
                    store.create("导入的笔记", text)
                    refresh()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        lastThemeColor = ThemeHelper.currentIndex(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ProjectStore(this)
        isGrid = getSharedPreferences("prefs", MODE_PRIVATE).getBoolean("grid", true)
        adapter = ProjectAdapter(emptyList()) { project, action ->
            when (action) {
                ProjectAdapter.Action.OPEN -> openPreview(project)
                ProjectAdapter.Action.EDIT -> openEditor(project)
                ProjectAdapter.Action.DELETE -> confirmDelete(project)
                ProjectAdapter.Action.PIN -> showNoteMenu(project)
            }
        }
        binding.recycler.layoutManager = GridLayoutManager(this, if (isGrid) 2 else 1)
        binding.recycler.adapter = adapter

        binding.fabNew.setOnClickListener { showTypeChooser() }
        binding.btnEmptyCreate.setOnClickListener { showTypeChooser() }
        binding.searchEdit.doAfterTextChanged { applyFilter() }

        binding.toolbar.inflateMenu(R.menu.menu_main)
        updateToggleIcon()

        // 返回键：搜索框聚焦时先退出搜索态（清空搜索词、收起键盘），再次按返回退出应用
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchEdit.hasFocus()) {
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

    private fun refresh() {
        allItems = store.list()
        applyFilter()
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
        binding.emptyLayout.visibility =
            if (allItems.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.noResult.visibility =
            if (allItems.isNotEmpty() && filtered.isEmpty()) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    /** 切换列表 / 双列网格视图，并记住用户偏好。 */
    private fun toggleLayout() {
        isGrid = !isGrid
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putBoolean("grid", isGrid).apply()
        (binding.recycler.layoutManager as GridLayoutManager).spanCount = if (isGrid) 2 else 1
        updateToggleIcon()
    }

    /** 图标显示的是切换目标视图：双列时显示列表图标，列表时显示双列图标。 */
    private fun updateToggleIcon() {
        binding.toolbar.menu.findItem(R.id.action_toggle_layout)
            ?.setIcon(if (isGrid) R.drawable.ic_list else R.drawable.ic_grid)
    }

    /** 文字便签打开即编辑（所见即所得），HTML 便签打开预览。 */
    private fun openPreview(p: Project) {
        if (p.type == Project.TYPE_TEXT) {
            openEditor(p)
        } else {
            startActivity(Intent(this, PreviewActivity::class.java).putExtra("id", p.id))
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

    private fun openEditor(p: Project?, type: String = Project.TYPE_HTML) {
        startActivity(
            Intent(this, EditorActivity::class.java).apply {
                if (p != null) putExtra("id", p.id) else putExtra("type", type)
            }
        )
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
        androidx.appcompat.app.AlertDialog.Builder(this)
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
