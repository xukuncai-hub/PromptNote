package com.htmlnotes.app

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.animation.PathInterpolatorCompat

/**
 * ColorOS (COUI) 标志性动效与物理插值体系：
 * 提取自 OPPO 官方 ColorOS 便签 (v16.7.2) 反编译核心参数。
 */
object CouiMotion {

    /** ColorOS 官方通用标准缓动 (0.3, 0, 0.1, 1)，用于页面转场、展开折叠与视图进出 */
    val EASE_INTERPOLATOR: Interpolator = PathInterpolatorCompat.create(0.3f, 0.0f, 0.1f, 1.0f)

    /** ColorOS 官方平移缓动 (0.3, 0, 0.5, 1)，用于手势跟随与滑动平移 */
    val MOVE_EASE_INTERPOLATOR: Interpolator = PathInterpolatorCompat.create(0.3f, 0.0f, 0.5f, 1.0f)

    /** ColorOS 官方进场蓄力 (0.1, 0, 0.1, 1) */
    val IN_EASE_INTERPOLATOR: Interpolator = PathInterpolatorCompat.create(0.1f, 0.0f, 0.1f, 1.0f)

    /** ColorOS 官方微回弹退出 (0.0, 0, 0.2, 1) */
    val OUT_EASE_INTERPOLATOR: Interpolator = PathInterpolatorCompat.create(0.0f, 0.0f, 0.2f, 1.0f)

    /** ColorOS 弹性物理回弹插值器 */
    val SPRING_BOUNCE_INTERPOLATOR: Interpolator = OvershootInterpolator(1.35f)
}

/**
 * ColorOS 标志性卡片/按键触控微动效 (PressAnimView)：
 * - 按下时轻微压缩至 0.96f，伴随清脆微触感 (Haptic)
 * - 抬起/移出时弹性回弹至 1.0f
 * - 返回 false，绝对不拦截事件流，完全兼容 RecyclerView 滑动与点击长按。
 */
fun View.applyColorOsPressAnim(scaleDown: Float = 0.96f, haptic: Boolean = true) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (haptic) {
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                v.animate().cancel()
                v.animate()
                    .scaleX(scaleDown)
                    .scaleY(scaleDown)
                    .setDuration(110L)
                    .setInterpolator(CouiMotion.EASE_INTERPOLATOR)
                    .start()
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().cancel()
                v.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(240L)
                    .setInterpolator(CouiMotion.SPRING_BOUNCE_INTERPOLATOR)
                    .start()
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val x = event.x
                val y = event.y
                if (x < 0 || x > v.width || y < 0 || y > v.height) {
                    if (v.scaleX != 1.0f) {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(160L)
                            .setInterpolator(CouiMotion.EASE_INTERPOLATOR)
                            .start()
                    }
                }
                false
            }
            else -> false
        }
    }
}

/**
 * ColorOS 胶囊气泡微浮显隐：带轻微 Y 轴位移与 COUI 缓动的渐显
 */
fun View.couiFade(visible: Boolean, duration: Long = 200L, translateYDp: Float = 6f) {
    if (visible == (visibility == View.VISIBLE && alpha > 0.9f)) return
    animate().cancel()
    if (visible) {
        alpha = 0f
        val offset = translateYDp * resources.displayMetrics.density
        translationY = offset
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(CouiMotion.EASE_INTERPOLATOR)
            .start()
    } else {
        animate()
            .alpha(0f)
            .translationY(-translateYDp * resources.displayMetrics.density * 0.5f)
            .setDuration(duration)
            .setInterpolator(CouiMotion.IN_EASE_INTERPOLATOR)
            .withEndAction {
                visibility = View.GONE
                translationY = 0f
            }
            .start()
    }
}
