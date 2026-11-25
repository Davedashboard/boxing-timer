package com.davedawson.roundtimer

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.speech.tts.UtteranceProgressListener
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt
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

    // ---- audio focus: lets the timer lower ("duck") or pause Spotify etc. while it talks / rings ----
    private val main = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var focus: AudioFocusRequest? = null
    private var focusMode = ""
    private val releaseFocus = Runnable { abandonFocus() }
    private val speechAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Take focus now; if holdMs > 0, give it back after that long (extended by later calls). */
    private fun grabFocus(mode: String, holdMs: Long) {
        if (mode == "off") return
        main.removeCallbacks(releaseFocus)
        if (focus == null || focusMode != mode) {
            abandonFocus()
            val gain = if (mode == "pause") AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                       else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            val req = AudioFocusRequest.Builder(gain)
                .setAudioAttributes(speechAttrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audio.requestAudioFocus(req)
            focus = req
            focusMode = mode
        }
        if (holdMs > 0) main.postDelayed(releaseFocus, holdMs)
    }

    private fun abandonFocus() {
        main.removeCallbacks(releaseFocus)
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        focusMode = ""
    }

    // ---- boosted voice: TTS is rendered to a file and played with a LoudnessEnhancer ----
    private var player: MediaPlayer? = null
    private var booster: LoudnessEnhancer? = null
    private var uttSeq = 0
    private val pending = HashMap<String, Pair<Int, String>>() // utterance id -> (volume %, music mode)

    private fun stopVoice() {
        try { booster?.release() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        booster = null
        player = null
    }

    private fun playVoiceFile(file: File, volPct: Int, mode: String) {
        stopVoice()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(speechAttrs)
            mp.setDataSource(file.path)
            mp.prepare()
            if (volPct < 100) {
                val v = volPct / 100f
                mp.setVolume(v, v)
            } else if (volPct > 100) {
                // +6 dB at 200%, about +9.5 dB at 300%
                val mB = (2000 * log10(volPct / 100.0)).roundToInt()
                booster = LoudnessEnhancer(mp.audioSessionId).apply { setTargetGain(mB); enabled = true }
            }
            mp.setOnCompletionListener {
                stopVoice()
                file.delete()
                main.removeCallbacks(releaseFocus)
                main.postDelayed(releaseFocus, 400)
            }
            player = mp
            grabFocus(mode, 0)
            mp.start()
            main.postDelayed(releaseFocus, (mp.duration.coerceAtLeast(0) + 3000).toLong()) // safety net
        } catch (e: Exception) {
            stopVoice()
            file.delete()
            abandonFocus()
        }
    }

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
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) {
                    val f = File(cacheDir, "$id.wav")
                    val job = synchronized(pending) { pending.remove(id) }
                    if (job == null) { f.delete(); return }
                    main.post { playVoiceFile(f, job.first, job.second) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) {
                    synchronized(pending) { pending.remove(id) }
                    main.post { abandonFocus() }
                }
            })
            ttsReady = true
        }
    }

    override fun onDestroy() {
        stopVoice()
        abandonFocus()
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
        fun speak(text: String, volPct: Int, mode: String) {
            if (!ttsReady) return
            val vol = volPct.coerceIn(10, 300)
            main.post {
                // start lowering the music straight away so it has faded by the time the voice starts
                grabFocus(mode, 4000)
                val id = "say${++uttSeq}"
                synchronized(pending) { pending.clear(); pending[id] = vol to mode }
                val f = File(cacheDir, "$id.wav")
                val r = tts?.synthesizeToFile(text, Bundle(), f, id)
                if (r != TextToSpeech.SUCCESS) {
                    // fallback: speak directly (no boost above 100%)
                    synchronized(pending) { pending.remove(id) }
                    val b = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (vol / 100f).coerceAtMost(1f)) }
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, b, "direct")
                }
            }
        }

        /** Lower / pause other apps' music for the next ms milliseconds (bells, clapper, beeps). */
        @JavascriptInterface
        fun duck(ms: Int, mode: String) {
            main.post { if (player == null) grabFocus(mode, ms.toLong().coerceIn(200, 10000)) }
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
