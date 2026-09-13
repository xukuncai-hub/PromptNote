package com.htmlnotes.app

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 光标保留：部分 ROM 收起键盘时会连带取消输入框焦点，导致光标消失。
 * 这里监听键盘收起事件，把焦点恢复到最近聚焦的输入框（不自动重弹键盘）。
 * 返回 forget 函数：需要主动清焦点（如退出搜索态）时调用，避免自动恢复。
 */
object CursorKeeper {

    fun attach(activity: Activity, vararg targets: View): () -> Unit {
        // IME 可见性监听需要 API 30+，低版本保持系统默认行为
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return {}
        var last: View? = null
        for (t in targets) {
            t.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) last = v }
        }
        val root = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            if (!insets.isVisible(WindowInsetsCompat.Type.ime())) {
                val target = last
                if (target != null && !target.hasFocus()) restore(activity, target)
            }
            ViewCompat.onApplyWindowInsets(v, insets)
        }
        return { last = null }
    }

    private fun restore(activity: Activity, v: View) {
        if (v is EditText) {
            // 恢复焦点但禁止自动弹键盘
            v.showSoftInputOnFocus = false
            v.requestFocus()
            v.post { v.showSoftInputOnFocus = true }
        } else {
            v.requestFocus()
            if (v is WebView) {
                v.evaluateJavascript(
                    "var ed=document.getElementById('ed');if(ed){ed.focus();}", null
                )
            }
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            v.post { imm.hideSoftInputFromWindow(v.windowToken, 0) }
        }
    }
}
