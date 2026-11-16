package ai.iqworks.consentiq

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class WebViewCaptchaProvider(
    private val context: Context,
    private val pageUrl: String,
    private val activities: ForegroundActivityTracker?,
) : CaptchaTokenProvider {
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var page: Page? = null
    private var dialog: Dialog? = null

    init {
        activities?.addDestroyListener(::detachFrom)
    }

    override suspend fun token(): String =
        mutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                try {
                    requestToken()
                } finally {
                    conceal()
                }
            }
        }

    // Turnstile signals nothing when it wants a click, so a token this late is taken to need one.
    private suspend fun requestToken(): String {
        val current = currentPage()
        attach(current.view)
        if (withTimeoutOrNull(TIMEOUT_MS) { current.ready.await() } == null) {
            fail(current, "timeout")
            throw CaptchaException("timeout")
        }
        val result = CompletableDeferred<String>()
        current.pending = result
        val reveal = Runnable { reveal(current, result) }
        try {
            current.view.evaluateJavascript(EXECUTE_SCRIPT, null)
            mainHandler.postDelayed(reveal, REVEAL_DELAY_MS)
            return withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                ?: if (current.revealed) {
                    withTimeoutOrNull(INTERACTION_TIMEOUT_MS - TIMEOUT_MS) { result.await() } ?: throw CaptchaException("timeout")
                } else {
                    throw CaptchaException("timeout")
                }
        } finally {
            mainHandler.removeCallbacks(reveal)
            if (current.pending === result) current.pending = null
        }
    }

    // Shows the challenge over the app with a Cancel button, for a visitor Cloudflare wants to click.
    private fun reveal(
        source: Page,
        result: CompletableDeferred<String>,
    ) {
        if (source.revealed || result.isCompleted || page !== source) return
        val activity = activities?.current ?: return
        source.revealed = true
        val view = source.view as? HiddenWebView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        view.interactive = true
        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, CHALLENGE_HEIGHT_DP)))
                addView(
                    Button(activity).apply {
                        setText(android.R.string.cancel)
                        setOnClickListener { result.completeExceptionally(CaptchaException("cancelled")) }
                    },
                )
            }
        dialog =
            Dialog(activity).apply {
                setContentView(content)
                setOnCancelListener { result.completeExceptionally(CaptchaException("cancelled")) }
                show()
            }
    }

    private fun conceal() {
        val shown = dialog ?: return
        dialog = null
        val view = page?.view as? HiddenWebView
        if (view != null) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.interactive = false
            page?.revealed = false
        }
        runCatching { shown.dismiss() }
        view?.let { attach(it) }
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun currentPage(): Page {
        page?.let { return it }
        val origin = originOf(pageUrl) ?: throw CaptchaException("invalid api url")
        // From Android 9 the first process to start WebView locks its data directory; a secondary process must not take it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Application.getProcessName() != context.applicationInfo.processName) {
            throw CaptchaException("not the main process")
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw CaptchaException("web message listener unsupported")
        }
        val view =
            try {
                HiddenWebView(context)
            } catch (e: RuntimeException) {
                throw CaptchaException("webview unavailable")
            }
        val created = Page(view)
        try {
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.webViewClient = Client(pageUrl, { created.ready.isCompleted }) { fail(created, it) }
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, setOf(origin)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame && message.type == WebMessageCompat.TYPE_STRING) onMessage(created, parseCaptchaMessage(message.data))
            }
        } catch (e: RuntimeException) {
            view.destroy()
            throw CaptchaException("webview setup failed")
        }
        page = created
        attach(view)
        view.loadUrl(pageUrl)
        return created
    }

    private fun onMessage(
        source: Page,
        message: CaptchaMessage?,
    ) {
        if (page !== source) return
        when (message) {
            CaptchaMessage.Ready -> source.ready.complete(Unit)
            CaptchaMessage.Interactive -> source.pending?.let { reveal(source, it) }
            is CaptchaMessage.Token -> source.pending?.complete(message.token)
            is CaptchaMessage.Error ->
                if (source.ready.isCompleted) {
                    source.pending?.completeExceptionally(CaptchaException("turnstile error ${message.code}"))
                } else {
                    fail(source, "turnstile error ${message.code}")
                }
            null -> Unit
        }
    }

    private fun fail(
        source: Page,
        reason: String,
    ) {
        val failure = CaptchaException(reason)
        source.ready.completeExceptionally(failure)
        source.pending?.completeExceptionally(failure)
        if (page === source) page = null
        if (source.discarded) return
        source.discarded = true
        (source.view.parent as? ViewGroup)?.removeView(source.view)
        mainHandler.post { source.view.destroy() }
    }

    private fun attach(view: WebView) {
        val decor = activities?.current?.window?.decorView as? ViewGroup ?: return
        if (view.parent === decor) return
        (view.parent as? ViewGroup)?.removeView(view)
        decor.addView(view, FrameLayout.LayoutParams(1, 1))
    }

    private fun detachFrom(activity: Activity) {
        val view = page?.view ?: return
        val decor = activity.window?.peekDecorView() as? ViewGroup ?: return
        if (view.parent === decor) decor.removeView(view)
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private class Client(
        private val pageUrl: String,
        private val isReady: () -> Boolean,
        private val onFailure: (reason: String) -> Unit,
    ) : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val allowed = isAllowedCaptchaNavigation(request.url.toString(), request.isForMainFrame, pageUrl)
            if (!allowed && request.isForMainFrame && !isReady()) onFailure("navigation blocked")
            return !allowed
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (request.isForMainFrame) onFailure("load failed")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame) onFailure("http ${errorResponse.statusCode}")
        }

        // Returning true keeps a crashed or killed renderer from taking the host app down with it.
        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            onFailure("renderer gone")
            return true
        }
    }

    private class Page(
        val view: WebView,
    ) {
        val ready = CompletableDeferred<Unit>()
        var pending: CompletableDeferred<String>? = null
        var discarded = false
        var revealed = false
    }

    @SuppressLint("ViewConstructor")
    private class HiddenWebView(
        context: Context,
    ) : WebView(context) {
        var interactive = false
            set(value) {
                field = value
                alpha = if (value) 1f else HIDDEN_ALPHA
                isFocusable = value
                isFocusableInTouchMode = value
                descendantFocusability = if (value) FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
                importantForAccessibility =
                    if (value) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }

        init {
            interactive = false
            isClickable = false
            isLongClickable = false
        }

        // Hidden, the 1x1 view sits on top of the app's content; it must never swallow a tap meant for the app.
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = interactive && super.dispatchTouchEvent(event)
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val REVEAL_DELAY_MS = 3_000L
        const val INTERACTION_TIMEOUT_MS = 120_000L
        const val CHALLENGE_HEIGHT_DP = 100
        const val BRIDGE_NAME = "ConsentIQAndroid"
        const val EXECUTE_SCRIPT = "window.consentiqExecute()"
        const val HIDDEN_ALPHA = 0.01f
    }
}

private fun dp(
    context: Context,
    value: Int,
): Int = (value * context.resources.displayMetrics.density).toInt()
