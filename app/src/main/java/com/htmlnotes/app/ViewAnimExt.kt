package com.htmlnotes.app

import android.view.MotionEvent
import android.view.View

/**
 * 轻量触感反馈：为按钮附加"按下缩小、松开回弹"动画，
 * 让每个可点元素都有明确的按动感知。
 */
fun View.attachPressScale(scale: Float = 0.88f, duration: Long = 110L) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(scale).scaleY(scale).setDuration(duration).start()
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(duration)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                    .start()
                false
            }
            else -> false
        }
    }
}
