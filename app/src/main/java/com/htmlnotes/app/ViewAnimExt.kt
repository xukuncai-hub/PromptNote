package com.htmlnotes.app

import android.view.View

/**
 * 轻量触感反馈：对齐 ColorOS (COUI) 物理按压微动效与触感回弹。
 */
fun View.attachPressScale(scale: Float = 0.95f, duration: Long = 100L) {
    applyColorOsPressAnim(scaleDown = scale)
}

/**
 * 空状态 / 无结果提示的显隐：采用 ColorOS 标准缓动曲线 (0.3, 0, 0.1, 1)。
 * 目标状态与当前一致时直接返回，避免每次过滤都重放动画。
 */
fun View.fadeVisibility(visible: Boolean, duration: Long = 220L) {
    if (visible == (visibility == View.VISIBLE)) return
    animate().cancel()
    if (visible) {
        alpha = 0f
        translationY = 8f * resources.displayMetrics.density
        visibility = View.VISIBLE
        animate().alpha(1f).translationY(0f)
            .setDuration(duration)
            .setInterpolator(CouiMotion.EASE_INTERPOLATOR)
            .start()
    } else {
        animate().alpha(0f)
            .setDuration(duration)
            .setInterpolator(CouiMotion.IN_EASE_INTERPOLATOR)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                translationY = 0f
            }
            .start()
    }
}
