package com.htmlnotes.app

import android.text.Html

/**
 * HTML / 纯文本处理的共享工具。
 *
 * 只在 Adapter 绑定等展示环节调用本文件的函数；数据库里保存的原始 HTML
 * 永远不被改写，剥离/提取都发生在内存副本上。
 *
 * 正则全部预编译为顶层常量：原先这些正则分散在适配器、编辑器和预览页里，
 * 每次调用都重新编译一遍，在列表绑定和摘要生成这类高频路径上开销会累积。
 */

/** 匹配任意 HTML 标签。 */
internal val HTML_TAG_REGEX = Regex("<[^>]*>")

/** 连续空白。 */
internal val WHITESPACE_REGEX = Regex("\\s+")

/** 形如真实标签（以字母开头），用于判断一段文本是否为富文本 HTML。 */
internal val HTML_LIKE_REGEX = Regex("<[a-zA-Z][^>]*>")

/** <style> 或 <script> 整块（含标签本身）。Html.fromHtml 会把块内文本当正文输出，必须先整体剔除。 */
internal val SCRIPT_STYLE_BLOCK_REGEX = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")

/** <title> 标签内容。 */
internal val TITLE_TAG_REGEX = Regex("(?is)<title\\b[^>]*>(.*?)</title\\s*>")

/** 第一个 <h1> 标签内容。 */
internal val H1_TAG_REGEX = Regex("(?is)<h1\\b[^>]*>(.*?)</h1\\s*>")

/** 第一个 <h2> 标签内容。 */
internal val H2_TAG_REGEX = Regex("(?is)<h2\\b[^>]*>(.*?)</h2\\s*>")

/** 粗略判断内容是否包含 HTML 标签。 */
internal fun looksLikeHtml(s: String): Boolean = HTML_LIKE_REGEX.containsMatchIn(s)

/**
 * 剥离 HTML 得到纯文本：先剔除 <style>/<script> 整块（CSS/JS 源码不该出现在
 * 卡片正文或分享文本里），再用系统 Html.fromHtml 解析标签并解码实体
 * （&amp; &lt; 等），最后压缩多余空白。兼容性远好于纯正则方案。
 */
internal fun stripHtml(html: String): String {
    val withoutBlocks = html.replace(SCRIPT_STYLE_BLOCK_REGEX, " ")
    val text = Html.fromHtml(withoutBlocks, Html.FROM_HTML_MODE_COMPACT).toString()
    return text.replace(WHITESPACE_REGEX, " ").trim()
}

/** 兼容旧调用点：原先的 htmlToPlain 语义即「HTML 转纯文本」，现在委托给增强版 stripHtml。 */
internal fun htmlToPlain(html: String): String = stripHtml(html)

/** 内容为空时卡片摘要的占位文案（无障碍描述中会跳过它）。 */
internal const val BLANK_SNIPPET = "（空白）"

/** 从 HTML 或文本中提取卡片摘要。 */
internal fun snippetOf(html: String): String {
    val text = stripHtml(html)
    return text.ifBlank { BLANK_SNIPPET }.take(80)
}

/**
 * 从 HTML 中提取可读标题，供卡片标题使用：
 * 1. 优先取 <title>（文档级标题），其次 <h1>，再 <h2>；取到的片段会再剥离一次标签，
 *    确保 `<!DOCTYPE`、`<html>` 等源码绝不会漏到界面上；
 * 2. 没有标题标签时，回退为剥离后的纯文本前 15 字 + 省略号；
 * 3. 纯文本也为空时返回 null，由调用方显示「无标题笔记」占位。
 */
internal fun extractTitle(html: String): String? {
    val raw = TITLE_TAG_REGEX.find(html)?.groupValues?.get(1)
        ?: H1_TAG_REGEX.find(html)?.groupValues?.get(1)
        ?: H2_TAG_REGEX.find(html)?.groupValues?.get(1)
    if (raw != null) {
        val t = stripHtml(raw)
        if (t.isNotBlank()) return t.take(24)
    }
    val plain = stripHtml(html)
    return if (plain.isBlank()) null else plain.take(15) + "…"
}
