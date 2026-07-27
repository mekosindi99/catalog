package com.sonbola.catalog

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sonbola.catalog.ui.theme.SonbolaCatalogTheme

/**
 * Wraps the exact same web app (bundled from index.html) in a WebView, so the Android app is
 * pixel-identical to the website — same CSS, animations, and layout, running as a local asset
 * over file:///android_asset/. Firebase/Cloudinary calls still go out over the network normally;
 * only the page itself is served locally.
 */
class MainActivity : ComponentActivity() {
    private var webViewRef: WebView? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            filePathCallback?.onReceiveValue(uri?.let { arrayOf(it) } ?: arrayOf())
            filePathCallback = null
        }

        // API 29+ (Android 10+) saves go through scoped-storage MediaStore, which needs no
        // runtime permission. Only devices on API 28 and below (WebDownloadBridge's legacy
        // File-based save path) need this dangerous permission actually granted at runtime —
        // declaring it in the manifest alone is not enough and silently fails without this.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val webView = webViewRef
                if (webView != null && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            SonbolaCatalogTheme {
                CatalogWebView(
                    onWebViewCreated = { webViewRef = it },
                    onFileChooserRequested = { callback ->
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = callback
                        fileChooserLauncher.launch("image/*")
                    }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CatalogWebView(
    onWebViewCreated: (WebView) -> Unit,
    onFileChooserRequested: (ValueCallback<Array<Uri>>) -> Unit
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                // Everything this app talks to (Firebase, Cloudinary) is HTTPS-only, so there is
                // no legitimate reason to allow unencrypted HTTP content into this HTTPS-equivalent
                // context — ALWAYS_ALLOW would open the door to man-in-the-middle content injection.
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

                addJavascriptInterface(WebDownloadBridge(context), "AndroidDownloader")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
                    }

                    // Keep the bundled app inside the WebView, but hand any external
                    // http/https link (e.g. the Cloudinary console link in Admin Panel)
                    // to the system browser so the user never gets stranded off-app.
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): Boolean {
                        val url = request.url
                        return if (url.scheme == "http" || url.scheme == "https") {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, url)
                                )
                            }
                            true
                        } else {
                            false
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        onFileChooserRequested(filePathCallback)
                        return true
                    }

                    override fun onPermissionRequest(request: PermissionRequest) {
                        request.grant(request.resources)
                    }
                }

                loadUrl("file:///android_asset/www/index.html")
                onWebViewCreated(this)
            }
        }
    )
}
