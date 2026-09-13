package com.htmlnotes.app

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText

/**
 * 光标保留：部分系统/ROM 收起键盘时会连带取消输入框焦点，导致光标消失。
 * 用 OnGlobalLayoutListener 检测键盘从「显示→收起」，把焦点恢复到最近聚焦的输入框
 * （不重新弹键盘），兼容所有 Android 版本。
 * 返回 forget 函数：需要主动退出搜索态时调用，避免光标被自动恢复。
 */
object CursorKeeper {

    fun attach(activity: Activity, vararg targets: View): () -> Unit {
        var last: View? = null
        for (t in targets) {
            t.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) last = v }
        }

        val content = activity.findViewById<View>(android.R.id.content)
        var keyboardVisible = false
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null

        listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            content.getWindowVisibleDisplayFrame(rect)
            val screenHeight = content.rootView.height
            val visibleHeight = rect.bottom - rect.top
            // 键盘高度超过屏幕 1/4 视为键盘显示
            val nowVisible = screenHeight - visibleHeight > screenHeight / 4
            if (keyboardVisible && !nowVisible) {
                // 键盘刚收起：稍等系统完成焦点清理，再恢复光标
                val target = last
                if (target != null) {
                    content.postDelayed({
                        if (target.isAttachedToWindow && !target.hasFocus()) {
                            restore(activity, target)
                        }
                    }, 150)
                }
            }
            keyboardVisible = nowVisible
        }
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)

        return {
            last = null
            listener?.let { content.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        }
    }

    private fun restore(activity: Activity, v: View) {
        if (v is EditText) {
            // 恢复焦点但不自动弹键盘
            v.showSoftInputOnFocus = false
            v.requestFocus()
            v.post {
                v.showSoftInputOnFocus = true
                hideIme(activity, v)
            }
        } else if (v is WebView) {
            v.requestFocus()
            v.evaluateJavascript(
                "var ed=document.getElementById('ed');if(ed){ed.focus();" +
                    "var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);" +
                    "var s=getSelection();s.removeAllRanges();s.addRange(r);}",
                null
            )
            v.post { hideIme(activity, v) }
        } else {
            v.requestFocus()
        }
    }

    private fun hideIme(activity: Activity, v: View) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
    }
}
