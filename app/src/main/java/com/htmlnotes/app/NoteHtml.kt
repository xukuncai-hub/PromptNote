package com.htmlnotes.app

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.material.R.attr.colorOnSurface
import com.google.android.material.R.attr.colorOnSurfaceVariant
import com.google.android.material.R.attr.colorPrimary
import com.google.android.material.R.attr.colorSurface
import com.google.android.material.R.attr.colorSurfaceVariant

/**
 * 富文本便签的 HTML 页面构建：编辑器（contenteditable）与预览页（只读）共用同一套排版样式，
 * 颜色取自当前主题色板，深色模式下自动适配。
 */
object NoteHtml {

    data class Palette(
        val bg: String,
        val fg: String,
        val accent: String,
        val sub: String,
        val quoteBg: String,
        /** 选区底色：正文里选中文字时的高亮，跟随主题色。 */
        val selection: String
    )

    fun paletteOf(context: Context): Palette {
        fun hex(color: Int) = String.format("#%06X", 0xFFFFFF and color)

        /**
         * 从当前主题取色，而不是直接读 @color/md_*：
         * 换肤（ThemeHelper.applyStyle）只覆盖主题属性，直接读颜色资源会绕过覆盖，
         * 导致换主题色后 App 外壳变了、正文里的链接/引用/行内代码还是原来的靛蓝。
         * 第二个参数是属性缺失时的兜底值（浅色/深色各一套，正常走不到）。
         */
        fun themeColor(attr: Int, fallbackRes: Int): Int {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            val color = ta.getColor(0, ContextCompat.getColor(context, fallbackRes))
            ta.recycle()
            return color
        }

        val accent = hex(themeColor(colorPrimary, R.color.md_primary))
        return Palette(
            bg = hex(themeColor(colorSurface, R.color.md_surface)),
            fg = hex(themeColor(colorOnSurface, R.color.md_on_surface)),
            accent = accent,
            sub = hex(themeColor(colorOnSurfaceVariant, R.color.md_on_surface_variant)),
            quoteBg = hex(themeColor(colorSurfaceVariant, R.color.md_surface_variant)),
            selection = rgba(accent, 0.28f)
        )
    }

    /** #RRGGBB → rgba(...)，用于选区底色这类需要透明度的场合。 */
    private fun rgba(hexColor: String, alpha: Float): String {
        val v = hexColor.removePrefix("#").toInt(16)
        return "rgba(${(v shr 16) and 0xFF},${(v shr 8) and 0xFF},${v and 0xFF},$alpha)"
    }

    /** 正文区共享 CSS（#ed 容器）。工具栏在下方布局流中不再覆盖正文，底部仅留少量呼吸空间。 */
    private fun contentCss(p: Palette): String =
        "#ed{outline:none;min-height:100%;padding:16px 20px 24px 20px;" +
            "font-family:-apple-system,'Segoe UI',Roboto,sans-serif;font-size:16px;line-height:1.75;" +
            "caret-color:${p.accent};word-break:break-word;" +
            // 去掉 WebView 点按时的灰蓝闪烁
            "-webkit-tap-highlight-color:transparent;}" +
            // 选区底色跟随主题色，否则用的是浏览器默认蓝，跟 App 的主题色是两套色
            "::selection{background:${p.selection};}" +
            // 标题走衬线（编辑风的 display 字），正文保持无衬线以保证长文可读性；
            // 字号严格落在 16/20/24 阶梯上，与原生 UI 的字阶同源
            "#ed h1,#ed h2,#ed h3{font-family:serif,-apple-system,'Segoe UI',Roboto,sans-serif;}" +
            "#ed h1{font-size:24px;margin:.6em 0 .4em;line-height:1.3;font-weight:700;letter-spacing:-0.01em;}" +
            "#ed h2{font-size:20px;margin:.6em 0 .4em;line-height:1.3;font-weight:700;letter-spacing:-0.01em;}" +
            "#ed h3{font-size:16px;margin:.7em 0 .4em;line-height:1.4;font-weight:600;}" +
            "#ed p{margin:.3em 0;}" +
            "#ed blockquote{margin:.6em 0;padding:.6em 1em;border-left:4px solid ${p.accent};" +
            "color:${p.sub};background:${p.quoteBg};border-radius:6px;}" +
            "#ed ul,#ed ol{padding-left:1.5em;margin:.4em 0;}" +
            "#ed a{color:${p.accent};text-decoration:underline;text-underline-offset:2px;}" +
            "#ed code{background:${p.quoteBg};padding:2px 6px;border-radius:4px;" +
            "font-family:Menlo,Consolas,monospace;font-size:0.9em;color:${p.accent};}" +
            "#ed hr{border:none;border-top:1px solid ${p.sub};margin:1.2em 0;opacity:0.4;}" +
            "#ed img{max-width:100%;height:auto;border-radius:8px;margin:.5em 0;box-shadow:0 2px 8px rgba(0,0,0,0.08);}" +
            "#ed pre{white-space:pre-wrap;word-break:break-word;margin:0;}" +
            // 表格排版适配
            "#ed table{width:100%;border-collapse:collapse;margin:.8em 0;font-size:14px;}" +
            "#ed th,#ed td{border:1px solid ${p.sub};padding:6px 10px;text-align:left;}" +
            "#ed th{background:${p.quoteBg};font-weight:600;}" +
            // 待办清单项 CSS (交互复选框/待办项)
            "#ed .task-item{list-style:none;margin:.3em 0;display:flex;align-items:center;}" +
            "#ed input[type='checkbox']{margin-right:8px;width:16px;height:16px;accent-color:${p.accent};cursor:pointer;}" +
            // HTML5 拓展标签兼容 (mark, kbd, details, summary, video, iframe, sub, sup)
            "#ed mark{background:${rgba("#FFEB3B", 0.45f)};color:inherit;padding:1px 4px;border-radius:3px;}" +
            "#ed kbd{background:${p.quoteBg};border:1px solid ${p.sub};border-radius:4px;padding:2px 5px;font-family:monospace;font-size:0.85em;}" +
            "#ed details{background:${p.quoteBg};border-radius:8px;padding:8px 12px;margin:.6em 0;}" +
            "#ed summary{font-weight:600;cursor:pointer;outline:none;padding:2px 0;}" +
            "#ed sub,#ed sup{font-size:75%;line-height:0;position:relative;vertical-align:baseline;}" +
            "#ed sup{top:-0.5em;} #ed sub{bottom:-0.25em;}" +
            "#ed video,#ed iframe{max-width:100%;border-radius:8px;border:none;margin:.6em 0;}"

    private fun head(p: Palette, extra: String = ""): String =
        "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<style>html,body{margin:0;padding:0;min-height:100%;" +
            // 关掉滚动到顶/底时的回弹辉光：它的颜色由 WebView 自己决定，跟主题色无关
            "overscroll-behavior:none;}" +
            "body{background:${p.bg};color:${p.fg};}" + contentCss(p) + extra + "</style></head><body>"

    /** 编辑器整页：#ed 可编辑，空内容时显示占位提示。 */
    fun editorPage(p: Palette, hint: String = "写点什么…"): String =
        head(p, "#ed:empty::before{content:'$hint';color:${p.sub};}") +
            "<div id=\"ed\" contenteditable=\"true\"></div></body></html>"

    /** 预览整页：富文本片段（innerHTML）套入只读 #ed。 */
    fun richPreviewPage(fragment: String, p: Palette): String =
        head(p) + "<div id=\"ed\">$fragment</div></body></html>"

    /** 预览整页：旧版纯文本转义后按 pre 保留换行。 */
    fun plainPreviewPage(text: String, p: Palette): String =
        head(p) + "<div id=\"ed\"><pre>${escape(text)}</pre></div></body></html>"

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
