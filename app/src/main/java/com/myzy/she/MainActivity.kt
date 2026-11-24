package com.myzy.she

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        // ============ 默认服务器地址（改这里即可换默认域名）============
        // 由 Tailscale 分配的固定域名 + 电脑上的 server.js 端口 8080
        const val START_URL = "http://desktop-j4flem2.tailae05bb.ts.net:8080"

        private const val PREF_NAME = "she_settings"
        private const val KEY_URL = "server_url"
        private const val LONG_PRESS_MS = 3000L
        private const val TOP_ZONE_PX = 160  // 顶部多少像素内长按才触发设置（避免与消息长按冲突）
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: LinearLayout

    // 文件选择回调：PWA 点"+"选图时，WebChromeClient 把回调存这里，选完图再回传
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 用现代的 Activity Result API 调起系统文件选择器（image/* = 只选图片）
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        val cb = filePathCallback
        if (cb != null) {
            if (uris.isEmpty()) {
                cb.onReceiveValue(null)   // 用户取消选择，必须回传 null，否则下次选图失效
            } else {
                cb.onReceiveValue(uris.toTypedArray())
            }
            filePathCallback = null
        }
    }

    // 供断网页面里的按钮调用
    inner class JsBridge {
        @android.webkit.JavascriptInterface
        fun retry() { runOnUiThread { webView.reload() } }

        @android.webkit.JavascriptInterface
        fun openSettings() { runOnUiThread { showUrlDialog() } }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var lastErrorUrl: String? = null   // 记住出错的地址，供手动重试

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        loadingView = findViewById(R.id.loading_view)
        setupWebView()

        val savedUrl = getPrefs().getString(KEY_URL, START_URL) ?: START_URL
        navigateTo(savedUrl)
        setupLongPressForSettings()
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true                                  // PWA 是 JS 单页
            domStorageEnabled = true                                  // localStorage 存 token / 会话
            databaseEnabled = true
            allowFileAccess = false                                   // 不开放本地文件，安全
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW  // http 页面里加载资源
            mediaPlaybackRequiresUserGesture = false                  // 语音/音频可自动播
            // 注：setAppCacheEnabled 已在新版 Android 移除，无需调用；下面一行即可保证前端更新即时生效
            cacheMode = WebSettings.LOAD_NO_CACHE                     // 页面走网络，保证前端更新即时生效
        }

        webView.addJavascriptInterface(JsBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                // 所有跳转都留在 WebView 内，不弹外部浏览器
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false
                } else {
                    true   // tel: / mailto: 等交给系统
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingView.visibility = View.GONE   // 页面加载完成，移除启动遮罩
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                // 只拦截主页面（帧）的错误；子资源失败不替换整页
                if (request?.isForMainFrame == true) {
                    lastErrorUrl = request.url?.toString()
                    showOfflinePage(error?.description?.toString() ?: "未知错误")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 上一个回调先取消，避免多次选图叠加
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                filePickerLauncher.launch("image/*")   // 只选图片，对应 PWA 的 accept="image/*"
                return true
            }
        }
    }

    /** 加载指定地址，并先做网络检测 */
    private fun navigateTo(url: String) {
        val normalized = normalizeUrl(url)
        lastErrorUrl = normalized
        if (!isNetworkAvailable()) {
            showOfflinePage("当前没有网络连接")
            return
        }
        loadingView.visibility = View.VISIBLE
        webView.loadUrl(normalized)
    }

    /** 补全协议头、去掉空白，避免用户输入错格式 */
    private fun normalizeUrl(input: String): String {
        var s = input.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    /** 顶部区域长按 3 秒 → 打开设置对话框（避开消息列表的长按复制） */
    private fun setupLongPressForSettings() {
        webView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.y <= TOP_ZONE_PX) {
                        longPressRunnable = Runnable { showUrlDialog() }
                        handler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_MOVE -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    longPressRunnable = null
                }
            }
            false   // 不拦截，让 WebView 正常处理滚动/点击
        }
    }

    /** 弹出地址设置对话框，保存到 SharedPreferences 并重新加载 */
    private fun showUrlDialog() {
        val input = EditText(this).apply {
            setText(getPrefs().getString(KEY_URL, START_URL) ?: START_URL)
            hint = getString(R.string.settings_hint)
            isSingleLine = true
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setMessage(R.string.settings_tip)
            .setView(input)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val url = normalizeUrl(input.text.toString())
                getPrefs().edit().putString(KEY_URL, url).apply()
                navigateTo(url)
            }
            .setNeutralButton(R.string.settings_reset) { _, _ ->
                getPrefs().edit().putString(KEY_URL, START_URL).apply()
                navigateTo(START_URL)
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    /** 断网/加载失败的友好页面：说明原因 + 重试 + 改地址 */
    private fun showOfflinePage(reason: String) {
        loadingView.visibility = View.GONE
        val safeReason = reason.replace("\\", "\\\\").replace("\"", "\\\"")
        val html = """
            <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
                   background:#FFF5F6;font-family:-apple-system,sans-serif;color:#8a5a63;padding:24px;text-align:center}
              .card{max-width:320px}
              .face{font-size:64px;color:#ff8fa3;margin-bottom:8px}
              h1{font-size:20px;margin:0 0 12px;color:#7a4a53}
              p{font-size:14px;line-height:1.7;margin:0 0 8px}
              .why{font-size:12px;color:#b08a90;margin-bottom:24px}
              button{width:100%;min-height:44px;margin-top:10px;border:0;border-radius:12px;
                     font-size:15px;background:#ff8fa3;color:#fff}
              button.ghost{background:#fff;color:#ff8fa3;border:1px solid #ff8fa3}
            </style></head><body><div class="card">
              <div class="face">她</div>
              <h1>连不上她</h1>
              <p>检查电脑是否开机、Tailscale 是否连接、<br>server.js（8080 端口）是否在运行</p>
              <p class="why">原因：$safeReason</p>
              <button onclick="Android.retry()">重试连接</button>
              <button class="ghost" onclick="Android.openSettings()">设置服务器地址</button>
            </div></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getPrefs() = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 返回键：页面能后退就后退，否则退出 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 选图回调兜底：Activity 被系统回收时把回调还回去 */
    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }
}
