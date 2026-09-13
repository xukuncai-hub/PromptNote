package com.htmlnotes.app

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
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
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowTheme.setOnClickListener { showThemeChooser() }
        binding.rowDefaultView.setOnClickListener { showViewChooser() }
        binding.rowClear.setOnClickListener { confirmClear() }
        binding.rowEmail.setOnClickListener { contactAuthor() }
        binding.rowGithub.setOnClickListener { openGithub() }

        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.versionValue.text = info.versionName
        } catch (e: Exception) {
            binding.versionValue.text = "1.0"
        }
        refreshValues()
    }

    private fun refreshValues() {
        binding.themeValue.text = when (prefs.getInt("theme", App.THEME_SYSTEM)) {
            App.THEME_LIGHT -> getString(R.string.theme_light)
            App.THEME_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
        binding.viewValue.text =
            getString(if (prefs.getBoolean("grid", true)) R.string.view_grid else R.string.view_list)
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
