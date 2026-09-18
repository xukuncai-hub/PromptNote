package com.htmlnotes.app

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Outline
import android.graphics.Rect
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionValues
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Material 容器变换过渡：首页卡片被点击后，目标页面（预览/编辑器）从卡片
 * 矩形「展开」为整页，返回时淡出。源页面只需把卡片屏幕坐标随 Intent 传出，
 * 目标页在 setContentView 前调 applyEnter() 装换 enter/exit transition。
 *
 * material 1.6 起 MaterialContainerTransform 迁移到了 androidx.transition，
 * 无法赋给 window.enterTransition（平台 android.transition 类型），跨 Activity
 * 场景改用下面的自绘平台 Transition 实现同样的 morph 效果。
 *
 * 走过渡的路径必须跳过 overridePendingTransition——窗口动画与 activity
 * transition 同时生效会互相拉扯。
 */
object NoteTransition {

    private const val EXTRA_START_BOUNDS = "startBounds"

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
     * 目标页面（setContentView 之前调用）：若由卡片启动则启用容器变换进入 +
     * 淡出退出，返回 true；调用方据此跳过 overridePendingTransition。
     */
    fun applyEnter(activity: Activity): Boolean {
        val bounds = activity.intent.getIntArrayExtra(EXTRA_START_BOUNDS) ?: return false
        activity.window.enterTransition = ContainerMorphTransition(
            Rect(bounds[0], bounds[1], bounds[2], bounds[3])
        )
        activity.window.exitTransition = Fade().apply { duration = 200 }
        return true
    }

    /**
     * 平台 Transition：把 android.R.id.content 以左上角为锚点，从起始矩形
     * 非等比缩放 + 平移到全屏并淡入，圆角从卡片半径渐变回直角，
     * 模拟 MaterialContainerTransform 的 morph 效果。
     */
    private class ContainerMorphTransition(private val start: Rect) : Transition() {

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

            val startRadius = 20f * content.resources.displayMetrics.density
            var radius = startRadius
            content.pivotX = 0f
            content.pivotY = 0f
            content.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            content.clipToOutline = true

            return ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 350
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    content.scaleX = lerp(start.width() / w, 1f, f)
                    content.scaleY = lerp(start.height() / h, 1f, f)
                    content.translationX = lerp(start.left.toFloat(), 0f, f)
                    content.translationY = lerp(start.top.toFloat(), 0f, f)
                    // 前 40% 时长内淡入（Material fadeMode=IN 的近似）
                    content.alpha = (f / 0.4f).coerceAtMost(1f)
                    radius = lerp(startRadius, 0f, f)
                    content.invalidateOutline()
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) = Unit
                    override fun onAnimationEnd(animation: Animator) = reset(content, w, h)
                    override fun onAnimationCancel(animation: Animator) = reset(content, w, h)
                    override fun onAnimationRepeat(animation: Animator) = Unit
                })
            }
        }

        private fun lerp(from: Float, to: Float, f: Float) = from + (to - from) * f

        /** 动画结束/取消后必须还原所有临时变换，否则残留会影响后续布局与绘制 */
        private fun reset(content: View, w: Float, h: Float) {
            content.scaleX = 1f
            content.scaleY = 1f
            content.translationX = 0f
            content.translationY = 0f
            content.alpha = 1f
            content.pivotX = w / 2f
            content.pivotY = h / 2f
            content.clipToOutline = false
            content.outlineProvider = ViewOutlineProvider.BACKGROUND
        }
    }
}
