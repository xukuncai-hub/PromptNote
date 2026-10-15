package com.htmlnotes.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.StringRes
import com.google.android.material.color.MaterialColors

/**
 * 编辑器内的轻提示：贴在底部工具栏上方的一枚胶囊，220ms 淡入、1.1s 后消失。
 *
 * 为什么不直接用 [android.widget.Toast]：编辑时键盘是抬起的，Toast 走系统窗口、
 * 位置固定在屏幕底部，正好被键盘盖住，用户什么都看不见。
 *
 * [bottomLimit] 是可用区域的下边界（工具栏顶部），由调用方在每次显示时重新给出 ——
 * 键盘起落、工具栏胶囊↔完整条切换都会改变它。
 */
class EditorTip(private val host: View, private val bottomLimit: () -> Int) {

    private val density = host.resources.displayMetrics.density

    private val text = TextView(host.context).apply {
        setBackgroundResource(R.drawable.bg_editor_tip)
        setTextColor(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceInverse)
        )
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        alpha = 0f
    }

    private val popup = PopupWindow(
        text, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false
    ).apply {
        isFocusable = false
        isOutsideTouchable = false
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        elevation = dp(8).toFloat()
    }

    private var dismissTask: Runnable? = null

    fun show(@StringRes res: Int) = showText(host.context.getText(res))

    fun showText(s: CharSequence) {
        if (!host.isAttachedToWindow) return
        text.text = s
        text.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = text.measuredWidth
        val h = text.measuredHeight

        val root = IntArray(2).also { host.getLocationOnScreen(it) }
        val x = (root[0] + host.width / 2 - w / 2)
            .coerceIn(root[0] + dp(8), root[0] + host.width - w - dp(8))
        val y = (bottomLimit() - h - dp(10)).coerceAtLeast(root[1] + dp(8))

        if (popup.isShowing) popup.update(x, y, -1, -1)
        else popup.showAtLocation(host, Gravity.TOP or Gravity.START, x, y)

        text.animate().cancel()
        text.alpha = 0f
        text.animate().alpha(1f).setDuration(220).start()

        dismissTask?.let { host.removeCallbacks(it) }
        val task = Runnable { hide() }
        dismissTask = task
        host.postDelayed(task, 1100)
    }

    private fun hide() {
        if (!popup.isShowing) return
        text.animate().cancel()
        text.animate().alpha(0f).setDuration(160).withEndAction {
            try {
                if (popup.isShowing) popup.dismiss()
            } catch (e: Exception) {
                // ignore if activity destroyed
            }
        }.start()
    }

    /** Activity 销毁时调：撤掉延迟任务，否则它会跑到销毁之后去弹窗口。 */
    fun dispose() {
        dismissTask?.let { host.removeCallbacks(it) }
        dismissTask = null
        hide()
    }

    private fun dp(v: Int) = (v * density).toInt()
}
