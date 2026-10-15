package com.htmlnotes.app

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.webkit.WebView

/**
 * 富文本 WebView：在选区浮条接管剪切/复制/全选时，把系统自己的文字选择菜单挡掉。
 *
 * 为什么要在这一层拦：
 *
 * `setCustomSelectionActionModeCallback` 只在 [TextView] 上存在，WebView 没有公开
 * 任何挂钩（它的 ActionMode 方法都是 @hide）。唯一的公开入口是 [View.startActionMode]，
 * 而 Chromium 的选择菜单最终也是走到这里 —— 在子类里不调 super、直接返回 null，
 * 框架就不会创建那套菜单。返回 null 本身是 [startActionMode] 的合法结果
 * （视图未附着时本来就会返回 null），调用方必须能处理。
 *
 * 只在 [suppressSelectionActionMode] 为真时拦截：选区折叠（只有光标）时放行，
 * 系统的插入模式（粘贴气泡）仍然要工作。
 */
class SelectableWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            requestFocusFromTouch()
        }
        return super.onTouchEvent(event)
    }

    /** 选区浮条是否正在接管剪切/复制/全选。 */
    var suppressSelectionActionMode = false

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        if (suppressSelectionActionMode) null else super.startActionMode(callback)

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        if (suppressSelectionActionMode) null else super.startActionMode(callback, type)
}
