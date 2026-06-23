package ru.saulinfo.cabinet

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.color.DynamicColors
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val startUrl: Uri by lazy { Uri.parse(normalizeUrl(BuildConfig.CABINET_URL)) }
    private val allowedHost: String by lazy { startUrl.host.orEmpty().lowercase(Locale.US) }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        val data = result.data
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        callback.onReceiveValue(uris)
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        errorTitle = findViewById(R.id.errorTitle)
        errorText = findViewById(R.id.errorText)
        findViewById<Button>(R.id.retryButton).setOnClickListener { reloadCabinet() }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_DEBUGGING)
        configureWebView()
        configureBackButton()

        if (savedInstanceState == null) {
            reloadCabinet()
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_AUTO)
        }

        webView.webViewClient = CabinetWebViewClient()
        webView.webChromeClient = CabinetWebChromeClient()
        webView.setDownloadListener(CabinetDownloadListener())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
    }

    private fun configureBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun reloadCabinet() {
        if (!hasNetwork()) {
            showError("Нет подключения", "Проверьте интернет и попробуйте снова.")
            return
        }
        showWebView()
        webView.loadUrl(startUrl.toString())
    }

    private fun hasNetwork(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showWebView() {
        errorView.isVisible = false
        webView.isVisible = true
    }

    private fun showError(title: String, text: String) {
        progress.isVisible = false
        webView.isVisible = false
        errorTitle.text = title
        errorText.text = text
        errorView.isVisible = true
    }

    private fun isAllowedWebUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        val path = uri.path.orEmpty()
        if (BuildConfig.BUILD_TYPE == "release" && scheme != "https") return false
        if (scheme != "https" && scheme != "http") return false
        if (host != allowedHost) return false
        return !path.startsWith("/admin")
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun handleIntentUrl(url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                intent.getStringExtra("browser_fallback_url")?.let { fallback ->
                    val fallbackUri = Uri.parse(fallback)
                    if (isAllowedWebUrl(fallbackUri)) webView.loadUrl(fallback) else openExternal(fallbackUri)
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun normalizeUrl(value: String): String {
        val uri = Uri.parse(value)
        require(uri.scheme == "https" || (BuildConfig.BUILD_TYPE != "release" && uri.scheme == "http")) {
            "cabinetUrl must be HTTPS for release builds"
        }
        return value
    }

    private inner class CabinetWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()

            if (scheme == "intent") return handleIntentUrl(uri.toString())
            if (scheme in setOf("tg", "mailto", "vless", "happ")) return openExternal(uri)
            if (scheme == "https" || scheme == "http") {
                if (isAllowedWebUrl(uri)) return false
                openExternal(uri)
                return true
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progress.isVisible = true
            showWebView()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            progress.isVisible = false
            CookieManager.getInstance().flush()
            injectAndroidHints()
            updateTitleFromApi()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                showError("Страница недоступна", "Не удалось открыть кабинет. Проверьте сеть или домен сборки.")
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            showError("Ошибка SSL", "Сертификат сайта не прошёл проверку. Подключение остановлено.")
        }
    }

    private inner class CabinetWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: WebChromeClient.FileChooserParams,
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback
            return try {
                filePicker.launch(fileChooserParams.createIntent())
                true
            } catch (_: ActivityNotFoundException) {
                this@MainActivity.filePathCallback = null
                false
            }
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private inner class CabinetDownloadListener : DownloadListener {
        override fun onDownloadStart(
            url: String,
            userAgent: String,
            contentDisposition: String,
            mimeType: String,
            contentLength: Long,
        ) {
            val uri = Uri.parse(url)
            if (!isAllowedWebUrl(uri)) {
                openExternal(uri)
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
                return
            }

            val request = DownloadManager.Request(uri)
                .setMimeType(mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType),
                )

            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            request.addRequestHeader("User-Agent", userAgent)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
    }

    private fun injectAndroidHints() {
        val reduceMotion = Settings.Global.getFloat(contentResolver, "animator_duration_scale", 1f) == 0f
        val script = """
            window.SAULINFO_ANDROID = {
              timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
              prefersReducedMotion: $reduceMotion
            };
            document.documentElement.dataset.androidWebview = 'true';
            if ($reduceMotion) {
              document.documentElement.style.setProperty('scroll-behavior', 'auto');
            }
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun updateTitleFromApi() {
        val brandingUrl = startUrl.buildUpon().path("/api/cabinet/branding").build().toString()
        Thread {
            try {
                val connection = URL(brandingUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"
                if (connection.responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val name = JSONObject(body).optString("name").takeIf { it.isNotBlank() }
                    if (name != null) {
                        runOnUiThread {
                            title = name
                            webView.evaluateJavascript("document.title=${JSONObject.quote(name)}", null)
                        }
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                runOnUiThread { title = BuildConfig.APP_DISPLAY_NAME }
            }
        }.start()
    }
}
