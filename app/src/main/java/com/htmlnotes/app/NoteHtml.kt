package com.htmlnotes.app

import android.content.Context
import androidx.core.content.ContextCompat

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
        val quoteBg: String
    )

    fun paletteOf(context: Context): Palette {
        fun hex(res: Int) = String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(context, res))
        return Palette(
            bg = hex(R.color.md_surface),
            fg = hex(R.color.md_on_surface),
            accent = hex(R.color.md_primary),
            sub = hex(R.color.md_on_surface_variant),
            quoteBg = hex(R.color.md_surface_variant)
        )
    }

    /** 正文区共享 CSS（#ed 容器）。工具栏在下方布局流中不再覆盖正文，底部仅留少量呼吸空间。 */
    private fun contentCss(p: Palette): String =
        "#ed{outline:none;min-height:100%;padding:16px 20px 24px 20px;" +
            "font-family:-apple-system,'Segoe UI',Roboto,sans-serif;font-size:16px;line-height:1.7;" +
            "caret-color:#FFC107;word-break:break-word;}" +
            "#ed h1{font-size:26px;margin:.6em 0 .4em;line-height:1.3;font-weight:700;}" +
            "#ed h2{font-size:22px;margin:.6em 0 .4em;line-height:1.3;font-weight:700;}" +
            "#ed h3{font-size:19px;margin:.6em 0 .4em;line-height:1.3;font-weight:600;}" +
            "#ed p{margin:.3em 0;}" +
            "#ed blockquote{margin:.5em 0;padding:.5em .9em;border-left:3px solid ${p.accent};" +
            "color:${p.sub};background:${p.quoteBg};border-radius:6px;}" +
            "#ed ul,#ed ol{padding-left:1.5em;margin:.4em 0;}" +
            "#ed a{color:${p.accent};text-decoration:underline;}" +
            "#ed code{background:${p.quoteBg};padding:1px 5px;border-radius:4px;" +
            "font-family:Menlo,Consolas,monospace;font-size:0.9em;color:${p.accent};}" +
            "#ed hr{border:none;border-top:1px solid ${p.sub};margin:1em 0;}" +
            "#ed img{max-width:100%;height:auto;border-radius:6px;margin:.4em 0;}" +
            "#ed pre{white-space:pre-wrap;word-break:break-word;margin:0;}"

    private fun head(p: Palette, extra: String = ""): String =
        "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<style>html,body{margin:0;padding:0;min-height:100%;}" +
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
