package com.htmlnotes.app

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 光标跟随键盘：键盘显示时正常输入；键盘收起时清除输入框/富文本焦点，让光标消失。
 * 用 OnGlobalLayoutListener 检测键盘从「显示→收起」，收起后 blur 掉最近聚焦的目标。
 * 返回 detach 函数：需要主动退出搜索态（不再自动清除焦点）时调用。
 */
object CursorKeeper {

    fun attach(activity: Activity, vararg targets: View): () -> Unit {
        var last: View? = null
        for (t in targets) {
            t.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) last = v }
        }

        val content = activity.findViewById<View>(android.R.id.content)
        var keyboardVisible = false
        var pending: Runnable? = null

        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val nowVisible = isKeyboardVisible(content)
            if (keyboardVisible && !nowVisible) {
                // 键盘刚收起：等系统稳定后，若输入框仍持有焦点则清除，让光标消失
                val target = last
                if (target != null) {
                    val task = Runnable {
                        // 若收起后键盘又弹回（如切换输入框），则不打断
                        if (target.hasFocus() && !isKeyboardVisible(content)) {
                            hideCursor(target)
                        }
                    }
                    pending = task
                    content.postDelayed(task, 120)
                }
            }
            keyboardVisible = nowVisible
        }
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)

        return {
            last = null
            // 还没跑的延迟任务必须先撤掉：hideCursor 会对 WebView 调
            // evaluateJavascript，而调用方（EditorActivity.onDestroy）紧接着
            // 就会 destroy 掉那个 WebView。
            pending?.let { content.removeCallbacks(it) }
            pending = null
            val vto = content.viewTreeObserver
            if (vto.isAlive) vto.removeOnGlobalLayoutListener(listener)
        }
    }

    /**
     * 用 IME insets 判断键盘可见性。
     *
     * 原实现靠 getWindowVisibleDisplayFrame 的「可见高度骤减」来推断，但该 API 在 Android 15 的
     * 边到边窗口下已不再随键盘收缩，读数会在 1/4 阈值附近反复抖动 —— 「键盘刚收起」于是被反复触发，
     * WebView 被 blur() 收起键盘、用户一碰又弹出，来回打架，表现就是界面和工具栏抽搐。
     */
    private fun isKeyboardVisible(content: View): Boolean {
        val insets = ViewCompat.getRootWindowInsets(content) ?: return false
        return insets.isVisible(WindowInsetsCompat.Type.ime())
    }

    private fun hideCursor(v: View) {
        if (v is EditText) {
            v.clearFocus()
        } else if (v is WebView) {
            v.evaluateJavascript(
                "var ed=document.getElementById('ed');if(ed&&document.activeElement===ed){ed.blur();}",
                null
            )
        } else {
            v.clearFocus()
        }
    }
}