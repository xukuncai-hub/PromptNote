package com.htmlnotes.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(themeMode())
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        /** 将设置里的主题值映射为 AppCompat 夜间模式。 */
        fun themeModeOf(value: Int): Int = when (value) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    private fun themeMode(): Int {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        return themeModeOf(prefs.getInt("theme", THEME_SYSTEM))
    }
}
