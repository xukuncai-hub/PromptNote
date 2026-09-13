package com.htmlnotes.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.htmlnotes.app.databinding.ActivityPreviewBinding

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private var currentContent: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("id")
        val project = id?.let { ProjectStore(this).get(it) }
        if (project == null) {
            finish()
            return
        }
        currentContent = project.content

        binding.toolbar.title = project.title
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_preview)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    binding.webView.reload()
                    true
                }
                R.id.action_copy -> {
                    copyToClipboard(currentContent)
                    true
                }
                R.id.action_share -> {
                    shareHtml(currentContent)
                    true
                }
                else -> false
            }
        }

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // 移动端响应式适配，避免页面被截断
            useWideViewPort = true
            loadWithOverviewMode = true
            // 允许页面加载 http 资源（混合内容）
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        binding.webView.webViewClient = WebViewClient()
        // 文字便签：富文本 HTML 直接渲染；旧版纯文本则包装排版后显示
        val content = if (project.type == Project.TYPE_TEXT && !looksLikeHtml(project.content)) {
            renderTextPage(project.content)
        } else {
            project.content
        }
        // 使用 https 基址加载，相对路径的资源(如 css/js/图片)也能正常解析
        binding.webView.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/assets/",
            content,
            "text/html",
            "UTF-8",
            null
        )
    }

    /** 粗略判断内容是否是富文本 HTML（含标签）。 */
    private fun looksLikeHtml(s: String): Boolean =
        Regex("<[a-zA-Z][^>]*>").containsMatchIn(s)

    /** 把普通文本包装成简洁的网页排版显示。 */
    private fun renderTextPage(text: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" " +
            "content=\"width=device-width, initial-scale=1\"><style>" +
            "body{margin:0;padding:20px 18px;font-family:-apple-system,'Segoe UI',Roboto,sans-serif;" +
            "color:#1b1b20;background:#fafafc;font-size:16px;line-height:1.7;}" +
            "pre{white-space:pre-wrap;word-break:break-word;font-family:inherit;margin:0;}" +
            "</style></head><body><pre>$escaped</pre></body></html>"
    }

    private fun copyToClipboard(html: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("html", html))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareHtml(html: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_TEXT, html)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
