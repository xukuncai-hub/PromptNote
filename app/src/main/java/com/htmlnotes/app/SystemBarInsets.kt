package com.htmlnotes.app

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams

/**
 * Edge-to-edge：把系统栏 / 刘海区 insets 补到视图自身的 padding 或外边距上。
 * 基准值在注册时捕获，insets 多次分发时始终从基准值重算 padding，避免逐次累加。
 * 监听器原样返回 insets，不影响兄弟视图继续收到分发。
 */
fun View.applySystemBarInsets(
    left: Boolean = false,
    top: Boolean = false,
    right: Boolean = false,
    bottom: Boolean = false,
    asMargin: Boolean = false
) {
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    val baseL = paddingLeft
    val baseT = paddingTop
    val baseR = paddingRight
    val baseB = paddingBottom
    val baseMargins = if (asMargin) {
        (layoutParams as? ViewGroup.MarginLayoutParams)
            ?.let { intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
    } else null
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val i = insets.getInsets(types)
        v.setPadding(
            baseL + if (left) i.left else 0,
            baseT + if (top) i.top else 0,
            baseR + if (right) i.right else 0,
            baseB + if (bottom) i.bottom else 0
        )
        if (asMargin) {
            val b = baseMargins ?: return@setOnApplyWindowInsetsListener insets
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = b[0] + if (left) i.left else 0
                topMargin = b[1] + if (top) i.top else 0
                rightMargin = b[2] + if (right) i.right else 0
                bottomMargin = b[3] + if (bottom) i.bottom else 0
            }
        }
        insets
    }
}
