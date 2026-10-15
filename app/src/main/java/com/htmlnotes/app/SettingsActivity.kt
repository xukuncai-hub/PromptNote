package com.htmlnotes.app

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.htmlnotes.app.databinding.ActivitySettingsBinding

/**
 * 设置页：主题 / 默认视图 / 清空数据 / 关于。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // Edge-to-edge：顶栏避让状态栏，滚动内容避让导航栏（可滚动进 bars 下方）
        binding.topBar.applySystemBarInsets(top = true)
        binding.scroll.applySystemBarInsets(left = true, right = true, bottom = true)

        binding.btnBack.setOnClickListener { finishWithAnim() }
        binding.btnBack.attachPressScale(0.88f, 100L)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishWithAnim()
            }
        })
        binding.rowTheme.setOnClickListener { showThemeChooser() }
        binding.rowThemeColor.setOnClickListener { showThemeColorChooser() }
        binding.rowDefaultView.setOnClickListener { showViewChooser() }
        binding.rowClear.setOnClickListener { confirmClear() }
        binding.rowEmail.setOnClickListener { contactAuthor() }
        binding.rowGithub.setOnClickListener { openGithub() }

        listOf(
            binding.rowTheme, binding.rowThemeColor, binding.rowDefaultView,
            binding.rowClear, binding.rowEmail, binding.rowGithub
        ).forEach { it.attachPressScale(0.98f, 80L) }

        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.versionValue.text = info.versionName
        } catch (e: Exception) {
            binding.versionValue.text = "1.0"
        }
        refreshValues()
    }

    private fun finishWithAnim() {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    private fun refreshValues() {
        binding.themeValue.text = when (prefs.getInt("theme", App.THEME_SYSTEM)) {
            App.THEME_LIGHT -> getString(R.string.theme_light)
            App.THEME_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
        val colorIdx = ThemeHelper.currentIndex(this)
        binding.themeColorValue.text = getString(ThemeHelper.colorNames[colorIdx])
        binding.themeColorDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                binding.themeColorDot, com.google.android.material.R.attr.colorPrimary
            )
        )
        binding.viewValue.text =
            getString(if (prefs.getBoolean("grid", true)) R.string.view_grid else R.string.view_list)

        val store = ProjectStore(this)
        val allNotes = store.list()
        val totalCount = allNotes.size
        val textCount = allNotes.count { it.type == Project.TYPE_TEXT }
        val htmlCount = allNotes.count { it.type == Project.TYPE_HTML }
        val pinnedCount = allNotes.count { it.pinned }
        binding.notesCountValue.text = "${totalCount}篇 (${textCount}文本 · ${htmlCount}HTML · ${pinnedCount}置顶)"

        val bytes = store.storageSize()
        binding.storageSizeValue.text = formatStorageSize(bytes)
    }

    private fun formatStorageSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    /** 主题色选择：以精美色彩网格底部弹窗展示，点击即时切换生效。 */
    private fun showThemeColorChooser() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetBinding = com.htmlnotes.app.databinding.SheetThemeColorBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        val current = ThemeHelper.currentIndex(this)
        sheetBinding.colorRecycler.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 4)
        sheetBinding.colorRecycler.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun getItemCount() = ThemeHelper.overlayStyles.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val itemBinding = com.htmlnotes.app.databinding.ItemThemeColorBinding.inflate(layoutInflater, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemBinding.root) {}
            }
            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val b = com.htmlnotes.app.databinding.ItemThemeColorBinding.bind(holder.itemView)
                val colorVal = androidx.core.content.ContextCompat.getColor(this@SettingsActivity, ThemeHelper.primaryColors[position])
                (b.colorCircle.background as? android.graphics.drawable.GradientDrawable)?.setColor(colorVal) ?: run {
                    b.colorCircle.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(colorVal)
                    }
                }
                b.colorName.text = getString(ThemeHelper.colorNames[position])
                b.checkMark.visibility = if (position == current) android.view.View.VISIBLE else android.view.View.GONE
                b.root.attachPressScale(0.92f, 80L)
                b.root.setOnClickListener {
                    sheet.dismiss()
                    prefs.edit().putInt("theme_color", position).apply()
                    recreate()
                }
            }
        }
        sheet.show()
    }

    private fun showThemeChooser() {
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val current = prefs.getInt("theme", App.THEME_SYSTEM)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putInt("theme", which).apply()
                AppCompatDelegate.setDefaultNightMode(App.themeModeOf(which))
                refreshValues()
                dialog.dismiss()
            }
            .show()
    }

    private fun showViewChooser() {
        val labels = arrayOf(getString(R.string.view_grid), getString(R.string.view_list))
        val current = if (prefs.getBoolean("grid", true)) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_default_view)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putBoolean("grid", which == 0).apply()
                refreshValues()
                dialog.dismiss()
            }
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear)
            .setMessage(R.string.settings_clear_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                ProjectStore(this).clearAll()
                Toast.makeText(this, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                refreshValues()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 打开邮件客户端联系作者。 */
    private fun contactAuthor() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_SENDTO,
            android.net.Uri.parse("mailto:" + getString(R.string.author_email))
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.author_email, Toast.LENGTH_SHORT).show()
        }
    }

    /** 浏览器打开作者 GitHub 主页。 */
    private fun openGithub() {
        startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://" + getString(R.string.author_github))
            )
        )
    }
}
