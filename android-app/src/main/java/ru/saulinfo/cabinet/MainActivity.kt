package ru.saulinfo.cabinet

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.webkit.JavascriptInterface
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.color.DynamicColors
import com.google.firebase.messaging.FirebaseMessaging
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
    private val appApiKey: String by lazy { BuildConfig.ANDROID_APP_API_KEY.trim() }
    private var updateDialogShown = false
    private var updateDownloadId = -1L
    private var updateReceiverRegistered = false
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == updateDownloadId) {
                openDownloadedUpdate(id)
            }
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        val data = result.data
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        callback.onReceiveValue(uris)
        filePathCallback = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            NotificationChannels.ensure(this)
        } else {
            showNotificationSettingsDialog()
        }
    }
    private var notificationSettingsDialogShown = false

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
        registerUpdateDownloadReceiver()
        NotificationChannels.ensure(this)
        ensureNotificationsEnabled()
        fetchFcmToken()
        checkGitHubUpdate()

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
        if (updateReceiverRegistered) {
            unregisterReceiver(updateDownloadReceiver)
            updateReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        webView.postDelayed({ ensureNotificationsEnabled() }, 800)
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
        webView.addJavascriptInterface(AndroidPushBridge(), "SaulInfoAndroidPush")
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

    private fun registerUpdateDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateDownloadReceiver, filter)
        }
        updateReceiverRegistered = true
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showNotificationSettingsDialog()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        webView.post {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                AlertDialog.Builder(this)
                    .setTitle("Уведомления")
                    .setMessage("Разрешите уведомления, чтобы получать рассылки и важные сообщения из кабинета.")
                    .setPositiveButton("Разрешить") { _, _ ->
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    .setNegativeButton("Позже", null)
                    .show()
            } else {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun ensureNotificationsEnabled() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        requestNotificationPermission()
    }

    private fun showNotificationSettingsDialog() {
        if (notificationSettingsDialogShown || isFinishing) return
        notificationSettingsDialogShown = true
        AlertDialog.Builder(this)
            .setTitle("Уведомления выключены")
            .setMessage("Android не дал приложению разрешение на уведомления. Откройте настройки приложения и включите уведомления вручную.")
            .setPositiveButton("Открыть настройки") { _, _ -> openNotificationSettings() }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun fetchFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    PushTokenRegistrar.saveFcmToken(this, token)
                    PushTokenRegistrar.subscribeToBroadcastTopic()
                    PushTokenRegistrar.registerSavedToken(this)
                }
            }
        } catch (_: Exception) {
            // Firebase push is active only when google-services.json is provided for the installation.
        }
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
            injectPushRegistration()
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

    private fun injectPushRegistration() {
        val script = """
            (function() {
              try {
                var token = sessionStorage.getItem('access_token') || localStorage.getItem('access_token') || '';
                if (token && window.SaulInfoAndroidPush && window.SaulInfoAndroidPush.registerAccessToken) {
                  window.SaulInfoAndroidPush.registerAccessToken(token);
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private inner class AndroidPushBridge {
        @JavascriptInterface
        fun registerAccessToken(accessToken: String) {
            if (accessToken.isBlank()) return
            PushTokenRegistrar.saveAccessToken(this@MainActivity, accessToken)
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        PushTokenRegistrar.subscribeToBroadcastTopic()
                        PushTokenRegistrar.register(this@MainActivity, accessToken, token)
                    }
                }
            } catch (_: Exception) {
                PushTokenRegistrar.registerSavedToken(this@MainActivity)
            }
        }
    }

    private fun updateTitleFromApi() {
        val apiUrl = if (appApiKey.isNotBlank()) {
            startUrl.buildUpon().path("/api/cabinet/mobile/config").build().toString()
        } else {
            startUrl.buildUpon().path("/api/cabinet/branding").build().toString()
        }
        Thread {
            try {
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"
                if (appApiKey.isNotBlank()) {
                    connection.setRequestProperty("X-SaulInfo-App-Key", appApiKey)
                }
                if (connection.responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val name = if (appApiKey.isNotBlank()) {
                        json.optString("app_name").takeIf { it.isNotBlank() }
                            ?: json.optJSONObject("branding")?.optString("name")?.takeIf { it.isNotBlank() }
                    } else {
                        json.optString("name").takeIf { it.isNotBlank() }
                    }
                    if (appApiKey.isNotBlank()) {
                        val latestVersionCode = json.optInt("latest_version_code", BuildConfig.VERSION_CODE)
                        val latestVersionName = json.optString("latest_version_name").takeIf { it.isNotBlank() }
                        val apkUrl = json.optString("apk_url").takeIf { it.startsWith("https://") }
                        val updateRequired = json.optBoolean("update_required", false)
                        val updateNotes = json.optString("update_notes").takeIf { it.isNotBlank() }
                        if (latestVersionCode > BuildConfig.VERSION_CODE && apkUrl != null) {
                            runOnUiThread {
                                showAppUpdateDialog(latestVersionName, updateNotes, apkUrl, updateRequired)
                            }
                        }
                    }
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

    private fun checkGitHubUpdate() {
        val repo = BuildConfig.ANDROID_RELEASE_REPO.trim().trim('/')
        if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return
        Thread {
            try {
                val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "SaulInfo-Android-Updater")
                if (connection.responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val release = JSONObject(body)
                    val latestVersionCode = releaseVersionCode(release)
                    val latestVersionName = release.optString("tag_name").trim().trimStart('v', 'V').takeIf { it.isNotBlank() }
                        ?: release.optString("name").trim().takeIf { it.isNotBlank() }
                    val notes = release.optString("body").trim().takeIf { it.isNotBlank() }
                    val apkUrl = releaseApkUrl(release)
                    if (latestVersionCode > BuildConfig.VERSION_CODE && apkUrl != null) {
                        runOnUiThread {
                            showAppUpdateDialog(latestVersionName, notes, apkUrl, false)
                        }
                    }
                }
                connection.disconnect()
            } catch (_: Exception) {
                // Update checks are best-effort; the cabinet itself must continue to open.
            }
        }.start()
    }

    private fun releaseVersionCode(release: JSONObject): Int {
        val text = listOf(
            release.optString("tag_name"),
            release.optString("name"),
            release.optString("body"),
        ).joinToString("\n")
        Regex("""(?i)(?:versionCode|version_code|version-code)\s*[:=]\s*(\d+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it.coerceAtLeast(1) }
        val tag = release.optString("tag_name").trim().trimStart('v', 'V')
        tag.toIntOrNull()?.let { return it.coerceAtLeast(1) }
        val semver = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(tag)
        if (semver != null) {
            val major = semver.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minor = semver.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val patch = semver.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
        }
        return BuildConfig.VERSION_CODE
    }

    private fun releaseApkUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        val preferredName = BuildConfig.ANDROID_RELEASE_ASSET_NAME.trim()
        var fallback: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            val url = asset.optString("browser_download_url").trim()
            if (!url.startsWith("https://")) continue
            if (preferredName.isNotBlank() && name == preferredName) return url
            if (fallback == null && name.lowercase(Locale.US).endsWith(".apk")) fallback = url
        }
        return fallback
    }

    private fun showAppUpdateDialog(
        versionName: String?,
        notes: String?,
        apkUrl: String,
        required: Boolean,
    ) {
        if (updateDialogShown || isFinishing) return
        updateDialogShown = true
        val message = buildString {
            append("Доступна новая версия приложения")
            versionName?.let { append(" ").append(it) }
            append(".")
            if (!notes.isNullOrBlank()) {
                append("\n\n").append(notes)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Новая версия приложения")
            .setMessage(message)
            .setPositiveButton("Скачать APK") { _, _ -> downloadUpdateApk(apkUrl) }
            .apply {
                if (!required) {
                    setNegativeButton("Позже", null)
                }
            }
            .setCancelable(!required)
            .show()
    }

    private fun downloadUpdateApk(apkUrl: String) {
        val uri = Uri.parse(apkUrl)
        val request = DownloadManager.Request(uri)
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("${BuildConfig.APP_DISPLAY_NAME}.apk")
            .setDescription("Загрузка новой версии приложения")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                URLUtil.guessFileName(apkUrl, null, "application/vnd.android.package-archive"),
            )
        updateDownloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    }

    private fun openDownloadedUpdate(downloadId: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openExternal(uri)
        }
    }
}
