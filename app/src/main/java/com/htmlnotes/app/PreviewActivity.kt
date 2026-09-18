package com.htmlnotes.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.htmlnotes.app.databinding.ActivityPreviewBinding

class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private var currentContent: String = ""
    /** 由首页卡片以容器变换过渡进入：此时跳过 overridePendingTransition，并等内容延后到动画结束再加载。 */
    private var containerTransition = false
    /** 页面加载失败后是否已显示提示，避免多个错误重复弹出。 */
    private var loadErrorShown = false
    /** 页面是否已通过 onPageFinished（用于区分加载中失败与加载完成后资源失败）。 */
    private var pageFinished = false

    /** 子资源失败的非阻断提示每轮加载只弹一次，避免多个坏脚本刷屏。 */
    private var scriptErrorShown = false

    /** JS 错误桥：捕获页面脚本资源加载失败，回传原生层给出可见提示。 */
    private inner class ErrorBridge {
        @JavascriptInterface
        fun onResourceError(detail: String) {
            // 子资源失败 ≠ 整页失败：只给非阻断提示，不盖全屏错误层
            // （错误事件多发生在 onPageFinished 之前，不能等页面加载完再提示）
            runOnUiThread {
                if (scriptErrorShown || loadErrorShown) return@runOnUiThread
                scriptErrorShown = true
                Toast.makeText(
                    this@PreviewActivity,
                    getString(R.string.preview_script_failed) + "\n" + detail,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.apply(this)
        containerTransition = NoteTransition.applyEnter(this)
        enableEdgeToEdge()
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Edge-to-edge：toolbar 背景伸入状态栏；页面容器（而非 WebView 本体）避让
        // 导航栏——WebView 的 padding 区域会画出其自身背景色，深色模式下会露白边
        binding.toolbar.applySystemBarInsets(top = true)
        binding.webContainer.applySystemBarInsets(left = true, right = true, bottom = true)

        val id = intent.getStringExtra("id")
        val project = id?.let { ProjectStore(this).get(it) }
        if (project == null) {
            finish()
            return
        }
        currentContent = project.content

        binding.toolbar.title = project.title
        binding.toolbar.setNavigationOnClickListener { finishWithAnim() }
        binding.toolbar.inflateMenu(R.menu.menu_preview)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    retryLoad()
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

        // 返回键 / 返回手势：优先回退 WebView 内部历史，否则关闭页面
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finishWithAnim()
                }
            }
        })

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
        binding.webView.addJavascriptInterface(ErrorBridge(), "HtmlNotePreview")
        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished = true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 只有主框架加载失败才提示（子资源失败交给 JS 捕获，避免误报）
                if (request?.isForMainFrame == true) {
                    runOnUiThread {
                        val detail = error?.description?.toString() ?: ""
                        showLoadError(detail)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (pageFinished) return
                runOnUiThread { showLoadError(description ?: "") }
            }
        }

        val palette = NoteHtml.paletteOf(this)
        // 文字便签：富文本片段套编辑器同款主题样式；旧版纯文本转义后排版显示。
        // HTML 便签：原样渲染用户代码（含 <script> 动画、CDN 资源）。
        val content = when {
            project.type == Project.TYPE_TEXT && looksLikeHtml(project.content) ->
                NoteHtml.richPreviewPage(project.content, palette)
            project.type == Project.TYPE_TEXT ->
                NoteHtml.plainPreviewPage(project.content, palette)
            else -> project.content
        }
        // 使用 https 基址加载，相对路径的资源(如 css/js/图片)也能正常解析
        var pageLoaded = false
        val loadPage = {
            if (!pageLoaded) {
                pageLoaded = true
                binding.webView.loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/assets/",
                    injectErrorCapture(content),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
        if (containerTransition) {
            // 容器变换期间 WebView 会逐帧重排：等动画结束再加载内容，保证过渡顺滑。
            // 系统动画被关（animationScale=0）时 onTransitionEnd 不会来，用超时兜底
            window.enterTransition?.addListener(object : android.transition.Transition.TransitionListener {
                override fun onTransitionStart(transition: android.transition.Transition) {}
                override fun onTransitionEnd(transition: android.transition.Transition) {
                    transition.removeListener(this)
                    loadPage()
                }
                override fun onTransitionCancel(transition: android.transition.Transition) {
                    transition.removeListener(this)
                    loadPage()
                }
                override fun onTransitionPause(transition: android.transition.Transition) {}
                override fun onTransitionResume(transition: android.transition.Transition) {}
            })
            binding.webView.postDelayed(loadPage, 800)
        } else {
            loadPage()
        }

        binding.btnRetry.setOnClickListener { retryLoad() }
    }

    /**
     * 在页面加载前预注入全局错误捕获（onPageFinished 注入太晚，CDN 脚本
     * 可能在此之前就已失败）：外部 <script> 资源加载失败时回传原生层，
     * 弹非阻断 Toast 提示。只拦截资源加载错误，不影响页面自身业务脚本。
     *
     * 捕获脚本必须插在文档最前（head 之前），因为 script 资源错误发生在
     * 解析到该 script 的瞬间——若监听器注册得比坏脚本晚，事件就丢了。
     */
    private fun injectErrorCapture(content: String): String {
        val capture =
            "<script>window.addEventListener('error',function(e){" +
                "if(e&&e.target&&e.target.tagName==='SCRIPT'){" +
                "try{HtmlNotePreview.onResourceError((e.target.src||'script').substring(0,120));" +
                "}catch(x){}}},true);</script>"
        val lower = content.lowercase()
        val headStart = lower.indexOf("<head")
        if (headStart >= 0) {
            // 插到 <head ...> 结束标签之后
            val headEnd = content.indexOf(">", headStart)
            return if (headEnd >= 0) {
                content.substring(0, headEnd + 1) + capture + content.substring(headEnd + 1)
            } else {
                capture + content
            }
        }
        return capture + content
    }

    /** 重新加载：隐藏错误层并刷新页面。 */
    private fun retryLoad() {
        loadErrorShown = false
        scriptErrorShown = false
        pageFinished = false
        binding.errorOverlay.visibility = View.GONE
        binding.webView.reload()
    }

    private fun showLoadError(detail: String) {
        if (loadErrorShown) return
        loadErrorShown = true
        binding.errorTitle.text = getString(R.string.preview_load_failed)
        binding.errorDetail.text = if (detail.isBlank()) {
            getString(R.string.preview_load_failed_hint)
        } else {
            getString(R.string.preview_load_failed_hint) + "\n\n" + detail
        }
        binding.errorOverlay.visibility = View.VISIBLE
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

    private fun finishWithAnim() {
        finish()
        if (!containerTransition) overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        // WebView 持有 Activity 的 Context，且开启了 JS，不销毁会造成内存泄漏
        binding.webView.stopLoading()
        binding.webView.webChromeClient = null
        binding.webView.destroy()
        super.onDestroy()
    }
}
