package com.htmlnotes.app

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionValues
import android.view.View
import android.view.ViewGroup
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * 首页卡片 → 目标页面（预览/编辑器）的进入过渡：把起始卡片的位置作为锚点，
 * 整页等比放大 + 淡入，视觉上从被点的那张卡片「长出来」。返回时淡出。
 *
 * 这里刻意不做 Material 那种把卡片矩形拉伸成全屏的容器变换：
 * 卡片与屏幕的宽高比相差很大，按两个方向分别缩放会把整页文字和图标压扁变形，
 * 观感很差；等比缩放没有形变，同时仍然保留「从卡片展开」的方向感。
 *
 * 走过渡的路径必须跳过 overridePendingTransition——窗口动画与 activity
 * transition 同时生效会互相拉扯。
 */
object NoteTransition {

    private const val EXTRA_START_BOUNDS = "startBounds"

    /** 过渡时长：足够看清是从卡片展开的，又不至于让人等。 */
    private const val DURATION = 240L

    /** 起始缩放比例：留一点点放大空间，同时把画面位移控制在无感范围内。 */
    private const val MIN_SCALE = 0.94f

    /** 源页面：记录卡片在屏幕上的矩形（IntArray 传递，免 Parcelable 兼容问题）。 */
    fun putStartBounds(intent: Intent, card: View) {
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        intent.putExtra(
            EXTRA_START_BOUNDS,
            intArrayOf(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
        )
    }

    /**
     * 目标页面（setContentView 之前调用）：若由卡片启动则启用卡片锚点的进入 +
     * 淡出退出，返回 true；调用方据此跳过 overridePendingTransition。
     */
    fun applyEnter(activity: Activity): Boolean {
        val bounds = activity.intent.getIntArrayExtra(EXTRA_START_BOUNDS) ?: return false
        activity.window.enterTransition = CardOriginEnterTransition(
            Rect(bounds[0], bounds[1], bounds[2], bounds[3])
        )
        activity.window.exitTransition = Fade().apply { duration = 180 }
        return true
    }

    /**
     * 平台 Transition：以卡片中心为锚点，把 android.R.id.content 等比放大 +
     * 淡入到最终位置。等比缩放保证过渡中内容不形变。
     */
    private class CardOriginEnterTransition(private val start: Rect) : Transition() {

        override fun captureStartValues(transitionValues: TransitionValues) = Unit
        override fun captureEndValues(transitionValues: TransitionValues) = Unit

        override fun createAnimator(
            sceneRoot: ViewGroup,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator? {
            val content = sceneRoot.findViewById<View>(android.R.id.content) ?: return null
            val w = content.width.toFloat()
            val h = content.height.toFloat()
            if (w <= 0f || h <= 0f) return null

            // 卡片屏幕坐标换算到 content 自身坐标系：content 不一定与屏幕左上角重合
            val loc = IntArray(2)
            content.getLocationOnScreen(loc)
            content.pivotX = (start.centerX() - loc[0]).toFloat().coerceIn(0f, w)
            content.pivotY = (start.centerY() - loc[1]).toFloat().coerceIn(0f, h)

            return ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DURATION
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    val scale = MIN_SCALE + (1f - MIN_SCALE) * f
                    content.scaleX = scale
                    content.scaleY = scale
                    // 前半程淡入：页面在放大的同时显影
                    content.alpha = (f / 0.55f).coerceAtMost(1f)
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) = Unit
                    override fun onAnimationEnd(animation: Animator) = reset(content, w, h)
                    override fun onAnimationCancel(animation: Animator) = reset(content, w, h)
                    override fun onAnimationRepeat(animation: Animator) = Unit
                })
            }
        }

        /** 动画结束/取消后必须还原所有临时变换，否则残留会影响后续布局与绘制 */
        private fun reset(content: View, w: Float, h: Float) {
            content.scaleX = 1f
            content.scaleY = 1f
            content.alpha = 1f
            content.pivotX = w / 2f
            content.pivotY = h / 2f
        }
    }
}