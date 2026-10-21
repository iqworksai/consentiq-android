package ai.iqworks.consentiq

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal class WebViewTcfHost(
    private val context: Context,
    private val activities: ForegroundActivityTracker?,
) : TcfHost {
    override fun unavailable(): TcfOutcome.Failed? =
        try {
            when {
                // From Android 9 the first process to start WebView locks its data directory; a secondary process must not take it.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Application.getProcessName() != context.applicationInfo.processName ->
                    TcfOutcome.Failed(TCF_UNAVAILABLE, "not the main process")
                !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ->
                    TcfOutcome.Failed(TCF_UNSUPPORTED, "web message listener unsupported")
                else -> null
            }
        } catch (e: RuntimeException) {
            TcfOutcome.Failed(TCF_UNAVAILABLE, "webview unavailable")
        }

    override fun open(
        url: String,
        reopen: Boolean,
        onEvent: (TcfEvent) -> Unit,
    ): TcfPage {
        unavailable()?.let { throw TcfException(it.code, it.message ?: it.code) }
        val origin = originOf(url) ?: throw TcfException(TCF_UNAVAILABLE, "invalid api url")
        val page = WebViewTcfPage(context, activities, url, origin, onEvent)
        try {
            page.load()
        } catch (e: TcfException) {
            throw e
        } catch (e: RuntimeException) {
            page.release()
            throw TcfException(TCF_UNAVAILABLE, "webview setup failed")
        }
        return page
    }
}

private class WebViewTcfPage(
    private val appContext: Context,
    private val activities: ForegroundActivityTracker?,
    private val pageUrl: String,
    private val origin: String,
    private val onEvent: (TcfEvent) -> Unit,
) : TcfPage {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wrapper = MutableContextWrapper(themed(appContext, isNight(appContext)))
    private lateinit var view: TcfWebView
    private var dialog: Dialog? = null
    private var dialogActivity: Activity? = null
    private var pageIsDark: Boolean? = null
    private var presented = false
    private var dismissed = false
    private var released = false
    private val onResumed: (Activity) -> Unit = { if (awaitingActivity()) showIn(it) }
    private val presentWatchdog = Runnable { if (awaitingActivity()) fail("no activity to present in") }
    private val onDestroyed: (Activity) -> Unit = { detachFrom(it) }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    fun load() {
        view =
            try {
                TcfWebView(wrapper)
            } catch (e: RuntimeException) {
                throw TcfException(TCF_UNAVAILABLE, "webview unavailable")
            }
        try {
            view.setBackgroundColor(backgroundColor(isNight(appContext)))
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.webViewClient = Client()
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame && message.type == WebMessageCompat.TYPE_STRING) parseTcfEvent(message.data)?.let(::onMessage)
            }
        } catch (e: RuntimeException) {
            view.destroy()
            throw TcfException(TCF_UNAVAILABLE, "webview setup failed")
        }
        activities?.addResumeListener(onResumed)
        activities?.addDestroyListener(onDestroyed)
        attachHidden()
        view.loadUrl(pageUrl)
    }

    private fun awaitingActivity(): Boolean = presented && !dismissed && !released && dialog == null

    private fun watchForActivity() {
        mainHandler.removeCallbacks(presentWatchdog)
        mainHandler.postDelayed(presentWatchdog, PRESENT_TIMEOUT_MS)
    }

    override fun start(script: String) {
        if (!released) view.evaluateJavascript(script, null)
    }

    override fun present() {
        if (released || presented) return
        presented = true
        if (activities == null) {
            fail("no activity to present in")
            return
        }
        val current = activities.current
        if (current != null) showIn(current) else watchForActivity()
    }

    override fun dismiss() {
        if (released || dismissed) return
        dismissed = true
        mainHandler.removeCallbacks(presentWatchdog)
        dismissDialog()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(presentWatchdog)
        activities?.removeResumeListener(onResumed)
        activities?.removeDestroyListener(onDestroyed)
        dismissDialog()
        if (!::view.isInitialized) return
        (view.parent as? ViewGroup)?.removeView(view)
        wrapper.setBaseContext(appContext)
        mainHandler.post { view.destroy() }
    }

    private fun onMessage(event: TcfEvent.Page) {
        if (released) return
        if (event.message == TcfMessage.Ready) probeColorScheme()
        onEvent(event)
    }

    private fun fail(reason: String) {
        if (!released) onEvent(TcfEvent.Failed(reason))
    }

    private fun onBack() {
        if (!released && dialog != null) onEvent(TcfEvent.Back(interacted = view.touched))
    }

    private fun probeColorScheme() {
        view.evaluateJavascript(COLOR_SCHEME_SCRIPT) { result ->
            if (released || (result != "true" && result != "false")) return@evaluateJavascript
            pageIsDark = result == "true"
            if (dialog == null) view.setBackgroundColor(backgroundColor(result == "true"))
        }
    }

    private fun attachHidden() {
        val decor = activities?.current?.window?.decorView as? ViewGroup ?: return
        view.alpha = HIDDEN_ALPHA
        view.interactive = false
        decor.addView(view, FrameLayout.LayoutParams(1, 1))
    }

    private fun showIn(activity: Activity) {
        val dark = pageIsDark ?: isNight(activity)
        val background = backgroundColor(dark)
        val theme = themeFor(dark)
        try {
            (view.parent as? ViewGroup)?.removeView(view)
            wrapper.setBaseContext(ContextThemeWrapper(activity, theme))
            val container = FrameLayout(activity)
            container.setBackgroundColor(background)
            container.setOnApplyWindowInsetsListener(SystemBarPadding)
            view.setBackgroundColor(background)
            view.alpha = 1f
            view.interactive = true
            container.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

            val next = TcfDialog(activity, theme, ::onBack)
            next.setCancelable(false)
            next.setCanceledOnTouchOutside(false)
            next.setContentView(container, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            next.window?.let { configureWindow(it, background, dark) }
            dialog = next
            dialogActivity = activity
            next.show()
            mainHandler.removeCallbacks(presentWatchdog)
            next.window?.let { applyBarAppearance(it, dark) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                next.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
            }
            container.requestApplyInsets()
            view.requestFocus()
        } catch (e: RuntimeException) {
            dismissDialog()
            fail("present failed")
        }
    }

    private fun detachFrom(activity: Activity) {
        if (released) return
        if (dialogActivity === activity) {
            dismissDialog()
            (view.parent as? ViewGroup)?.removeView(view)
            wrapper.setBaseContext(themed(appContext, pageIsDark ?: isNight(appContext)))
            val next = activities?.current?.takeUnless { it === activity }
            if (next != null) showIn(next) else watchForActivity()
            return
        }
        val decor = activity.window?.peekDecorView() as? ViewGroup ?: return
        if (view.parent === decor) decor.removeView(view)
    }

    private fun dismissDialog() {
        val current = dialog ?: return
        dialog = null
        dialogActivity = null
        runCatching { current.dismiss() }
    }

    private fun openExternally(url: String) {
        val activity = dialogActivity ?: return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            return when (tcfNavigation(url, request.isForMainFrame, pageUrl, shown = dialog != null)) {
                TcfNavigation.ALLOW -> false
                TcfNavigation.EXTERNAL -> {
                    openExternally(url)
                    true
                }
                TcfNavigation.BLOCK -> {
                    if (request.isForMainFrame && !presented) fail("navigation blocked")
                    true
                }
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) fail("load failed")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame) fail("http ${errorResponse.statusCode}")
        }

        // Returning true keeps a crashed or killed renderer from taking the host app down with it.
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            fail("renderer gone")
            return true
        }
    }

    private class TcfDialog(
        context: Context,
        theme: Int,
        private val onBack: () -> Unit,
    ) : Dialog(context, theme) {
        @Deprecated("Deprecated in Java")
        override fun onBackPressed() {
            onBack()
        }
    }

    @SuppressLint("ViewConstructor")
    private class TcfWebView(
        context: Context,
    ) : WebView(context) {
        var touched = false
            private set

        var interactive = true
            set(value) {
                field = value
                isFocusable = value
                isFocusableInTouchMode = value
                descendantFocusability = if (value) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
                importantForAccessibility = if (value) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }

        // While prewarming, the 1x1 view sits on top of the app's content and must never swallow a tap meant for the app.
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!interactive) return false
            if (event.actionMasked == MotionEvent.ACTION_UP) touched = true
            return super.dispatchTouchEvent(event)
        }
    }

    private object SystemBarPadding : View.OnApplyWindowInsetsListener {
        @Suppress("DEPRECATION")
        override fun onApplyWindowInsets(
            v: View,
            insets: WindowInsets,
        ): WindowInsets {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                return WindowInsets.CONSUMED
            }
            var left = insets.systemWindowInsetLeft
            var top = insets.systemWindowInsetTop
            var right = insets.systemWindowInsetRight
            var bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let {
                    left = maxOf(left, it.safeInsetLeft)
                    top = maxOf(top, it.safeInsetTop)
                    right = maxOf(right, it.safeInsetRight)
                    bottom = maxOf(bottom, it.safeInsetBottom)
                }
            }
            v.setPadding(left, top, right, bottom)
            return insets.consumeSystemWindowInsets()
        }
    }

    private companion object {
        const val BRIDGE_NAME = "ConsentIQAndroid"
        const val COLOR_SCHEME_SCRIPT = "window.matchMedia('(prefers-color-scheme: dark)').matches"
        const val HIDDEN_ALPHA = 0.01f
        const val PRESENT_TIMEOUT_MS = 60_000L
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        // The hosted page's own light and dark backgrounds, so nothing flashes around or before it.
        val LIGHT_BACKGROUND = Color.rgb(0xFF, 0xFF, 0xFF)
        val DARK_BACKGROUND = Color.rgb(0x11, 0x18, 0x27)

        fun backgroundColor(dark: Boolean): Int = if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND

        fun themeFor(dark: Boolean): Int =
            if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar

        fun themed(
            context: Context,
            dark: Boolean,
        ): Context = ContextThemeWrapper(context, themeFor(dark))

        fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        @Suppress("DEPRECATION")
        fun configureWindow(
            window: Window,
            background: Int,
            dark: Boolean,
        ) {
            window.setBackgroundDrawable(ColorDrawable(background))
            window.setLayout(MATCH_PARENT, MATCH_PARENT)
            window.setWindowAnimations(android.R.style.Animation_Dialog)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes =
                    window.attributes.apply {
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
            } else {
                var flags =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                if (!dark) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (!dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                window.decorView.systemUiVisibility = flags
            }
            applyBarAppearance(window, dark)
        }

        fun applyBarAppearance(
            window: Window,
            dark: Boolean,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        }
    }
}
