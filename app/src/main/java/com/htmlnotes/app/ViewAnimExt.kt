package com.htmlnotes.app

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 轻量触感反馈：为按钮与卡片附加"按下微缩、松开弹回"动画，
 * 让每个可点元素都有明确的按动感知。
 */
fun View.attachPressScale(scale: Float = 0.95f, duration: Long = 100L) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(scale).scaleY(scale).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                v.animate().scaleX(1f).scaleY(1f).setDuration(duration + 40L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .start()
                false
            }
            else -> false
        }
    }
}

/**
 * 空状态 / 无结果提示的显隐：出现时淡入 + 轻微上浮，消失时淡出后置 GONE。
 * 目标状态与当前一致时直接返回，避免每次过滤都重放动画。
 */
fun View.fadeVisibility(visible: Boolean, duration: Long = 220L) {
    if (visible == (visibility == View.VISIBLE)) return
    animate().cancel()
    if (visible) {
        alpha = 0f
        translationY = 10f * resources.displayMetrics.density
        visibility = View.VISIBLE
        animate().alpha(1f).translationY(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .start()
    } else {
        animate().alpha(0f)
            .setDuration(duration)
            .withEndAction {
                visibility = View.GONE
                alpha = 1f
                translationY = 0f
            }
            .start()
    }
}
