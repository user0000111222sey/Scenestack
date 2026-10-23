package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private var textToSpeech: TextToSpeech? = null
  private var isTtsReady: Boolean = false

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

  inner class WebAppBridge(private val context: Context, private val webView: WebView) {

    @JavascriptInterface
    fun showToast(message: String) {
      (context as? ComponentActivity)?.runOnUiThread {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
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
