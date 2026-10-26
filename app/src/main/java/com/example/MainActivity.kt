package com.example

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private var textToSpeech: TextToSpeech? = null
  private var isTtsReady: Boolean = false

  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  private val filePickerLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == RESULT_OK && result.data != null) {
      val intent = result.data!!
      val uris: Array<Uri>? = when {
        intent.clipData != null -> {
          val clip = intent.clipData!!
          Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
        }
        intent.data != null -> arrayOf(intent.data!!)
        else -> null
      }
      filePathCallback?.onReceiveValue(uris)
    } else {
      filePathCallback?.onReceiveValue(null)
    }
    filePathCallback = null
  }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Initialize native Android TextToSpeech for Screenplay Table Reads
    textToSpeech = TextToSpeech(this) { status ->
      if (status == TextToSpeech.SUCCESS) {
        val result = textToSpeech?.setLanguage(Locale.US)
        isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
      }
    }
    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) {
        runOnUiThread {
          webView.evaluateJavascript("if(window.onNativeTtsStart) window.onNativeTtsStart('${utteranceId ?: ""}');", null)
        }
      }
      override fun onDone(utteranceId: String?) {
        runOnUiThread {
          webView.evaluateJavascript("if(window.onNativeTtsDone) window.onNativeTtsDone('${utteranceId ?: ""}');", null)
        }
      }
      override fun onError(utteranceId: String?) {
        runOnUiThread {
          webView.evaluateJavascript("if(window.onNativeTtsError) window.onNativeTtsError('${utteranceId ?: ""}');", null)
        }
      }
    })

    // Seamless matching background for status bar & navigation bar
    window.statusBarColor = 0xFF0E0E10.toInt()
    window.navigationBarColor = 0xFF0E0E10.toInt()

    webView = WebView(this).apply {
      setBackgroundColor(0xFF0E0E10.toInt())
      settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        loadWithOverviewMode = true
        useWideViewPort = true
        displayZoomControls = false
        builtInZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
      }

      webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
          return super.onConsoleMessage(consoleMessage)
        }

        override fun onShowFileChooser(
          webView: WebView?,
          filePathCallback: ValueCallback<Array<Uri>>?,
          fileChooserParams: FileChooserParams?
        ): Boolean {
          this@MainActivity.filePathCallback?.onReceiveValue(null)
          this@MainActivity.filePathCallback = filePathCallback

          val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
          }
          val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()
          if (!acceptTypes.isNullOrEmpty()) {
            if (acceptTypes.size == 1) {
              intent.type = acceptTypes[0]
            } else {
              intent.type = "*/*"
              intent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
            }
          }
          try {
            filePickerLauncher.launch(intent)
            return true
          } catch (e: Exception) {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = null
            return false
          }
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
          AlertDialog.Builder(this@MainActivity)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setOnCancelListener { result?.cancel() }
            .show()
          return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
          AlertDialog.Builder(this@MainActivity)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
            .setOnCancelListener { result?.cancel() }
            .show()
          return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
          val input = EditText(this@MainActivity).apply {
            setText(defaultValue ?: "")
          }
          AlertDialog.Builder(this@MainActivity)
            .setMessage(message ?: "")
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
            .setOnCancelListener { result?.cancel() }
            .show()
          return true
        }
      }

      setDownloadListener { url, _, _, mimeType, _ ->
        try {
          if (url.startsWith("data:")) {
            val commaIdx = url.indexOf(",")
            if (commaIdx != -1) {
              val header = url.substring(0, commaIdx)
              val base64 = url.substring(commaIdx + 1)
              val mime = if (header.contains(";")) header.substringAfter("data:").substringBefore(";") else (mimeType ?: "application/octet-stream")
              val ext = when {
                mime.contains("png") -> "png"
                mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                mime.contains("json") -> "json"
                mime.contains("fountain") -> "fountain"
                else -> "txt"
              }
              saveFileToDownloads("download_${System.currentTimeMillis()}.$ext", mime, base64)
            }
          } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
          }
        } catch (e: Exception) {
          Toast.makeText(this@MainActivity, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
      }

      webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
          view: WebView?,
          request: WebResourceRequest?
        ): Boolean {
          val url = request?.url?.toString() ?: return false
          if (url.startsWith("file:///android_asset/")) {
            return false
          }
          return try {
            val intent = Intent(Intent.ACTION_VIEW, request.url)
            startActivity(intent)
            true
          } catch (_: Exception) {
            false
          }
        }
      }

      addJavascriptInterface(WebAppBridge(this@MainActivity, this), "AndroidBridge")
      loadUrl("file:///android_asset/index.html")
    }

    setContentView(webView)

    // Ensure NOTHING is ever rendered under the notch, status bar, or gesture navigation bar
    ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
      val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
      val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
      val cutoutInsets = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
      val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
      val density = resources.displayMetrics.density

      val topPx = maxOf(statusBarInsets.top, cutoutInsets.top)
      val bottomPx = navBarInsets.bottom
      val leftPx = cutoutInsets.left
      val rightPx = cutoutInsets.right

      val topDp = (topPx / density).toInt()
      val bottomDp = (bottomPx / density).toInt()
      val leftDp = (leftPx / density).toInt()
      val rightDp = (rightPx / density).toInt()
      val imeDp = (imeInsets.bottom / density).toInt()

      // Guarantee minimum 48dp top inset for notch and camera cutout clearance
      val safeTopDp = maxOf(topDp, 48)
      val safeBottomDp = maxOf(bottomDp, 24)

      // Seamless edge-to-edge canvas with zero hardware letterboxing
      view.setPadding(0, 0, 0, 0)

      webView.evaluateJavascript(
        "(function() {" +
          "  document.documentElement.style.setProperty('--sat', '${safeTopDp}px');" +
          "  document.documentElement.style.setProperty('--sab', '${safeBottomDp}px');" +
          "  document.documentElement.style.setProperty('--sal', '${leftDp}px');" +
          "  document.documentElement.style.setProperty('--sar', '${rightDp}px');" +
          "  document.documentElement.style.setProperty('--native-ime', '${imeDp}px');" +
          "  if (window.handleNativeKeyboardHeight) { window.handleNativeKeyboardHeight(${imeDp}); }" +
          "})();",
        null
      )
      insets
    }

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        webView.evaluateJavascript(
          "(function() { if (window.sceneStackHandleBack) { return window.sceneStackHandleBack(); } return false; })()"
        ) { result ->
          if (result == "true" || result == "\"true\"") {
            // Handled inside webview
          } else if (webView.canGoBack()) {
            webView.goBack()
          } else {
            finish()
          }
        }
      }
    })
  }

  override fun onDestroy() {
    try {
      textToSpeech?.stop()
      textToSpeech?.shutdown()
    } catch (_: Exception) {}
    webView.destroy()
    super.onDestroy()
  }

  fun saveFileToDownloads(filename: String, mimeType: String, base64Data: String) {
    try {
      val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
      val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
          put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          contentResolver.openOutputStream(uri)?.use { os ->
            os.write(bytes)
          }
          runOnUiThread {
            Toast.makeText(this, "Saved $filename to Downloads", Toast.LENGTH_SHORT).show()
          }
        } else {
          runOnUiThread {
            Toast.makeText(this, "Failed to create download entry", Toast.LENGTH_SHORT).show()
          }
        }
      } else {
        @Suppress("DEPRECATION")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, filename)
        file.outputStream().use { it.write(bytes) }
        runOnUiThread {
          Toast.makeText(this, "Saved $filename to Downloads", Toast.LENGTH_SHORT).show()
        }
      }
    } catch (e: Exception) {
      runOnUiThread {
        Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_LONG).show()
      }
    }
  }

  fun shareFileBytes(filename: String, mimeType: String, base64Data: String) {
    try {
      val cleanBase64 = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
      val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
      val sharedDir = File(cacheDir, "shared").apply { mkdirs() }
      val file = File(sharedDir, filename)
      file.outputStream().use { it.write(bytes) }
      val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
      val sendIntent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_STREAM, uri)
        type = mimeType
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      startActivity(Intent.createChooser(sendIntent, "Share $filename"))
    } catch (e: Exception) {
      runOnUiThread {
        Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  inner class WebAppBridge(private val context: Context, private val webView: WebView) {

    @JavascriptInterface
    fun showToast(message: String) {
      (context as? ComponentActivity)?.runOnUiThread {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    }

    @JavascriptInterface
    fun saveFile(filename: String, mimeType: String, base64Data: String) {
      (context as? MainActivity)?.saveFileToDownloads(filename, mimeType, base64Data)
    }

    @JavascriptInterface
    fun shareFile(filename: String, mimeType: String, base64Data: String) {
      (context as? MainActivity)?.shareFileBytes(filename, mimeType, base64Data)
    }

    @JavascriptInterface
    fun isTtsAvailable(): Boolean {
      return isTtsReady && textToSpeech != null
    }

    @JavascriptInterface
    fun speakText(text: String, pitch: Float, speechRate: Float, utteranceId: String) {
      (context as? ComponentActivity)?.runOnUiThread {
        try {
          textToSpeech?.setPitch(pitch)
          textToSpeech?.setSpeechRate(speechRate)
          textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (_: Exception) {}
      }
    }

    @JavascriptInterface
    fun stopTts() {
      (context as? ComponentActivity)?.runOnUiThread {
        try {
          textToSpeech?.stop()
        } catch (_: Exception) {}
      }
    }

    @JavascriptInterface
    fun printDocument(title: String) {
      (context as? ComponentActivity)?.runOnUiThread {
        try {
          val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
          val printAdapter = webView.createPrintDocumentAdapter(title)
          val printAttributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.NA_LETTER)
            .setResolution(PrintAttributes.Resolution("screenplay", "print", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
          printManager?.print(title, printAdapter, printAttributes)
        } catch (e: Exception) {
          Toast.makeText(context, "Print error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
      }
    }

    @JavascriptInterface
    fun shareText(title: String, text: String) {
      (context as? ComponentActivity)?.runOnUiThread {
        val sendIntent = Intent().apply {
          action = Intent.ACTION_SEND
          putExtra(Intent.EXTRA_TITLE, title)
          putExtra(Intent.EXTRA_TEXT, text)
          type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, title)
        context.startActivity(shareIntent)
      }
    }
  }
}
