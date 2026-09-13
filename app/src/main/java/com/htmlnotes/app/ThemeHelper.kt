package com.htmlnotes.app

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/**
 * 主题色（换肤）辅助：预置 8 套 Material 3 主色，运行时通过 applyStyle 切换。
 */
object ThemeHelper {

    const val DEFAULT = 0

    /** 与主题色索引一一对应的覆盖样式。 */
    val overlayStyles = intArrayOf(
        R.style.ThemePromptNoteIndigo,
        R.style.ThemePromptNoteTeal,
        R.style.ThemePromptNoteCoral,
        R.style.ThemePromptNoteOrange,
        R.style.ThemePromptNoteRose,
        R.style.ThemePromptNoteViolet,
        R.style.ThemePromptNoteSky,
        R.style.ThemePromptNoteForest
    )

    /** 设置页展示的主题色名称资源。 */
    val colorNames = intArrayOf(
        R.string.theme_color_indigo,
        R.string.theme_color_teal,
        R.string.theme_color_coral,
        R.string.theme_color_orange,
        R.string.theme_color_rose,
        R.string.theme_color_violet,
        R.string.theme_color_sky,
        R.string.theme_color_forest
    )

    /** 设置页色点预览（浅色主色）。 */
    val previewColors = intArrayOf(
        0xFF4F5BDB.toInt(),
        0xFF00796B.toInt(),
        0xFFB4341F.toInt(),
        0xFF8F4B00.toInt(),
        0xFFB2396D.toInt(),
        0xFF6E4BCC.toInt(),
        0xFF0061A4.toInt(),
        0xFF2E6932.toInt()
    )

    /** 当前选中的主题色索引（越界时回退到默认）。 */
    fun currentIndex(context: Context): Int {
        val idx = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getInt("theme_color", DEFAULT)
        return idx.coerceIn(overlayStyles.indices)
    }

    /** 在 Activity 创建时调用（setContentView 之前）。 */
    fun apply(activity: AppCompatActivity) {
        activity.theme.applyStyle(overlayStyles[currentIndex(activity)], true)
    }
}
