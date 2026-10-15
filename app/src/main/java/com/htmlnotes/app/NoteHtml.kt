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

    /** 正文区共享 CSS（#ed 容器）。对齐 ColorOS 16 官方 TipTap 排版体系与色彩标准。 */
    private fun contentCss(p: Palette): String =
        "#ed{outline:none;min-height:100%;padding:16px 20px 45vh 20px;" +
            "font-family:-apple-system,'Segoe UI',Roboto,'OPPO Sans 4.0 SC',sans-serif;font-size:16px;line-height:1.72;" +
            "caret-color:${p.accent};word-break:break-word;" +
            // 去掉 WebView 点按时的灰蓝闪烁
            "-webkit-tap-highlight-color:transparent;}" +
            // 选区底色跟随主题色，采用微透明度
            "::selection{background:${p.selection};}" +
            // ColorOS 标题与排版字阶 (24 / 20 / 16)
            "#ed h1,#ed h2,#ed h3{font-family:-apple-system,'Segoe UI',Roboto,'OPPO Sans 4.0 SC',sans-serif;}" +
            "#ed h1{font-size:24px;margin:22px 0 12px;line-height:1.35;font-weight:700;letter-spacing:-0.01em;}" +
            "#ed h2{font-size:20px;margin:18px 0 10px;line-height:1.4;font-weight:600;letter-spacing:-0.01em;}" +
            "#ed h3{font-size:16px;margin:14px 0 8px;line-height:1.45;font-weight:600;}" +
            // 空段落防塌陷与正文行距
            "#ed p:empty::before{content:'\\200B';}" +
            "#ed p{margin:8px 0;min-height:1.6em;line-height:1.72;}" +
            // ColorOS 标志性圆角引用块
            "#ed blockquote{margin:12px 0;padding:10px 14px;border-left:3.5px solid ${p.accent};" +
            "color:${p.sub};background:${p.quoteBg};border-radius:8px;font-style:normal;}" +
            "#ed ul,#ed ol{padding-left:1.6em;margin:8px 0;}" +
            "#ed li{margin:4px 0;line-height:1.65;}" +
            "#ed a{color:${p.accent};text-decoration:underline;text-underline-offset:2px;}" +
            "#ed code{background:${p.quoteBg};padding:2px 6px;border-radius:4px;" +
            "font-family:Menlo,Consolas,monospace;font-size:0.9em;color:${p.accent};}" +
            "#ed hr{border:none;border-top:0.66px solid ${p.sub};margin:16px 0;opacity:0.35;}" +
            // 图片专属现代排版样式（居中、限制超长竖图高度、圆角与高亮选中反馈）
            "#ed img{display:block;max-width:100%;max-height:400px;width:auto;height:auto;margin:14px auto;" +
            "border-radius:12px;box-shadow:0 3px 12px rgba(0,0,0,0.08);cursor:pointer;object-fit:contain;" +
            "transition:outline 0.18s ease,transform 0.18s cubic-bezier(0.3,0,0.1,1),box-shadow 0.18s ease;" +
            "-webkit-user-select:none;user-select:none;}" +
            "#ed img.selected{outline:3.5px solid ${p.accent};outline-offset:3px;transform:scale(0.985);box-shadow:0 4px 16px rgba(0,0,0,0.16);}" +
            // 图片对齐模式：居中 / 靠左 / 靠右 / 铺满
            "#ed img.align-left{margin-left:0;margin-right:auto;}" +
            "#ed img.align-center{margin-left:auto;margin-right:auto;}" +
            "#ed img.align-right{margin-left:auto;margin-right:0;}" +
            "#ed img.align-full{width:100%!important;max-width:100%!important;margin:14px 0;border-radius:10px;max-height:520px;object-fit:cover;}" +
            // 图片拖动排序视觉反馈
            "#ed img.drag-source{opacity:0.28!important;outline:2.5px dashed ${p.accent}!important;outline-offset:2px;transform:scale(0.96);}" +
            ".img-drag-avatar{position:fixed;z-index:99999;pointer-events:none;max-width:240px;max-height:160px;object-fit:contain;border-radius:12px;border:2.5px solid ${p.accent};box-shadow:0 12px 32px rgba(0,0,0,0.32),0 4px 12px rgba(0,0,0,0.16);opacity:0.92;transform:translate(-50%,-50%) scale(1.04);transition:transform 0.08s ease;background:${p.bg};}" +
            ".img-drop-indicator{height:4px;background:${p.accent};border-radius:2px;margin:10px 0;box-shadow:0 0 10px ${p.accent};pointer-events:none;animation:pulseDropLine 1.2s infinite ease-in-out;}" +
            "@keyframes pulseDropLine{0%,100%{opacity:0.75;transform:scaleY(1);}50%{opacity:1;transform:scaleY(1.4);}}" +
            "#ed .note-img-container{text-align:center;margin:12px 0;}" +
            "#ed pre{white-space:pre-wrap;word-break:break-word;margin:12px 0;background:${p.quoteBg};padding:12px 14px;border-radius:8px;}" +
            // ColorOS 风格表格 (0.66px 细边框 + 圆角)
            "#ed table{width:100%;border-collapse:separate;border-spacing:0;margin:12px 0;font-size:14px;border:0.66px solid rgba(0,0,0,0.12);border-radius:6px;overflow:hidden;}" +
            "#ed th,#ed td{border:0.66px solid rgba(0,0,0,0.1);padding:8px 12px;text-align:left;}" +
            "#ed th{background:${p.quoteBg};font-weight:600;}" +
            // ColorOS 16 官方 18px 待办复选框交互与穿透删除线
            "#ed .task-item,#ed p:has(> input[type='checkbox']),#ed div:has(> input[type='checkbox']){" +
            "display:flex;align-items:center;margin:6px 0;min-height:28px;list-style:none;}" +
            "#ed input[type='checkbox']{margin-right:10px;width:18px;height:18px;border-radius:5px;accent-color:${p.accent};cursor:pointer;flex-shrink:0;vertical-align:-2px;}" +
            "#ed input[type='checkbox']:checked ~ *{text-decoration:line-through;opacity:0.48;transition:opacity 0.18s ease;}" +
            // ColorOS 官方 4 色荧光马克笔高亮体系
            "#ed mark{background:#FFE22766;color:inherit;padding:1px 4px;border-radius:3px;}" +
            "#ed mark.hl-yellow{background:#FFE22766;}" +
            "#ed mark.hl-red{background:#FFC1C766;}" +
            "#ed mark.hl-blue{background:#BBE7FF66;}" +
            "#ed mark.hl-green{background:#B6EDBF66;}" +
            "#ed kbd{background:${p.quoteBg};border:1px solid ${p.sub};border-radius:4px;padding:2px 5px;font-family:monospace;font-size:0.85em;}" +
            "#ed details{background:${p.quoteBg};border-radius:8px;padding:8px 12px;margin:8px 0;}" +
            "#ed summary{font-weight:600;cursor:pointer;outline:none;padding:2px 0;}" +
            "#ed sub,#ed sup{font-size:75%;line-height:0;position:relative;vertical-align:baseline;}" +
            "#ed sup{top:-0.5em;} #ed sub{bottom:-0.25em;}" +
            "#ed video,#ed iframe{max-width:100%;border-radius:8px;border:none;margin:8px 0;}"

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
