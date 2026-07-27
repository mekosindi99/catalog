package com.sonbola.catalog

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.util.Base64
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONArray
import java.io.File

/**
 * Native save path for the web app's file exports. Plain WebView cannot honor the page's two
 * download mechanisms — showDirectoryPicker (desktop-Chrome-only) and blob-anchor clicks (silently
 * dropped, blob: URLs never reach WebView's download callback) — so the page hands the bytes to
 * this bridge instead whenever it detects it is running inside the Android app.
 *
 * Images land in Pictures/SonbolaCatalog (visible in the phone's Gallery app, matching the web
 * app's "Gallery download" naming); everything else (ZIP, PDF, SVG) goes to Downloads.
 */
class WebDownloadBridge(private val context: Context) {

    @JavascriptInterface
    fun saveBase64File(base64Data: String, filename: String, mimeType: String, quiet: Boolean) {
        val safeName = filename.ifBlank { "download" }
        val resolvedMime = mimeType.ifBlank { "application/octet-stream" }
        runCatching {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val savedPath = save(safeName, resolvedMime, bytes)
            if (!quiet) showToast("تم الحفظ: $savedPath")
        }.onFailure { e ->
            showToast("فشل الحفظ: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onDownloadError(message: String) {
        showToast("فشل التنزيل: $message")
    }

    /**
     * Sends several images at once to WhatsApp (or any app) via Android's native multi-image
     * share sheet — the reliable way to hand multiple files to another app at once; clipboard
     * copy/paste of several images isn't consistently supported by receiving apps.
     * [imagesJson] is a JSON array of {base64, filename} objects built by the page.
     */
    @JavascriptInterface
    fun shareImages(imagesJson: String) {
        runCatching {
            val array = JSONArray(imagesJson)
            if (array.length() == 0) {
                showToast("ما فيه صور محدّدة للمشاركة")
                return
            }

            val cacheDir = File(context.cacheDir, "shared_images").apply {
                deleteRecursively()
                mkdirs()
            }

            val uris = ArrayList<Uri>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val bytes = Base64.decode(item.getString("base64"), Base64.DEFAULT)
                val filename = item.optString("filename", "image_$i.jpg").ifBlank { "image_$i.jpg" }
                val file = File(cacheDir, filename)
                file.writeBytes(bytes)
                uris.add(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
            }

            val sendIntent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uris[0])
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
            }
            sendIntent.type = "image/*"
            sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(Intent.createChooser(sendIntent, "مشاركة الصور"))
        }.onFailure { e ->
            showToast("فشل تجهيز المشاركة: ${e.message}")
        }
    }

    private fun save(filename: String, mimeType: String, bytes: ByteArray): String {
        val isImage = mimeType.startsWith("image/")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isImage) saveViaMediaStore(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                relativePath = Environment.DIRECTORY_PICTURES + "/SonbolaCatalog",
                displayPathPrefix = "Pictures/SonbolaCatalog",
                filename = filename, mimeType = mimeType, bytes = bytes
            ) else saveViaMediaStore(
                collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                relativePath = Environment.DIRECTORY_DOWNLOADS,
                displayPathPrefix = "Downloads",
                filename = filename, mimeType = mimeType, bytes = bytes
            )
        } else {
            val baseDir = if (isImage) {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SonbolaCatalog")
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
            if (!baseDir.exists()) baseDir.mkdirs()
            val file = File(baseDir, filename)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }

    private fun saveViaMediaStore(
        collection: Uri,
        relativePath: String,
        displayPathPrefix: String,
        filename: String,
        mimeType: String,
        bytes: ByteArray
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("تعذّر إنشاء الملف")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("تعذّر فتح الملف للكتابة")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return "$displayPathPrefix/$filename"
    }

    private fun showToast(message: String) {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Safety net injected after every page load: catches any blob/data download anchor the page
 * still fires (programmatic `a.click()` included, via the prototype patch) and routes it to
 * [WebDownloadBridge]. The primary path is the page's own `saveBlobToDevice` helper, which
 * talks to the bridge directly.
 */
const val DOWNLOAD_HOOK_JS = """
(function() {
  if (window.__androidDownloadHookInstalled) return;
  window.__androidDownloadHookInstalled = true;

  function sendToAndroid(href, filename) {
    fetch(href).then(function(res) { return res.blob(); }).then(function(blob) {
      var reader = new FileReader();
      reader.onloadend = function() {
        var dataUrl = reader.result || '';
        var comma = dataUrl.indexOf(',');
        var semi = dataUrl.indexOf(';');
        var meta = semi > 5 ? dataUrl.substring(5, semi) : '';
        var base64 = comma >= 0 ? dataUrl.substring(comma + 1) : '';
        window.AndroidDownloader.saveBase64File(base64, filename, meta || blob.type || 'application/octet-stream', false);
      };
      reader.onerror = function() {
        window.AndroidDownloader.onDownloadError('FileReader failed');
      };
      reader.readAsDataURL(blob);
    }).catch(function(err) {
      window.AndroidDownloader.onDownloadError(String(err));
    });
  }

  function shouldIntercept(a) {
    if (!window.AndroidDownloader || !a || !a.hasAttribute || !a.hasAttribute('download')) return false;
    var href = a.getAttribute('href') || a.href || '';
    return href.indexOf('blob:') === 0 || href.indexOf('data:') === 0;
  }

  var originalClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function() {
    if (shouldIntercept(this)) {
      var href = this.getAttribute('href') || this.href || '';
      sendToAndroid(href, this.getAttribute('download') || 'download');
      return;
    }
    return originalClick.apply(this, arguments);
  };

  document.addEventListener('click', function(e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (!shouldIntercept(a)) return;
    e.preventDefault();
    e.stopPropagation();
    var href = a.getAttribute('href') || a.href || '';
    sendToAndroid(href, a.getAttribute('download') || 'download');
  }, true);
})();
"""
