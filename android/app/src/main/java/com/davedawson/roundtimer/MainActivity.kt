package com.davedawson.roundtimer

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the screen on while the app is open, and let the volume keys control media volume
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        tts = TextToSpeech(this, this)

        // Serve the bundled timer from a proper https origin so localStorage works
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.userAgentString = web.settings.userAgentString + " RoundTimerApp"
        web.addJavascriptInterface(Bridge(), "AndroidApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                // YouTube how-to links etc. open in the YouTube app / browser
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: ActivityNotFoundException) {
                }
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__back ? window.__back() : false)") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale.UK)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.ENGLISH)
            }
            ttsReady = true
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            legacyVibrator()
        }

    @Suppress("DEPRECATION")
    private fun legacyVibrator(): Vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    /** Called from the web page as window.AndroidApp.* */
    inner class Bridge {
        @JavascriptInterface
        fun speak(text: String) {
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "roundtimer")
        }

        @JavascriptInterface
        fun vibrate(pattern: String) {
            val p = pattern.split(",").mapNotNull { it.trim().toLongOrNull() }
            if (p.isEmpty()) return
            val effect = if (p.size == 1) {
                VibrationEffect.createOneShot(p[0], VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createWaveform((listOf(0L) + p).toLongArray(), -1)
            }
            try {
                vibrator().vibrate(effect)
            } catch (e: Exception) {
            }
        }
    }
}
