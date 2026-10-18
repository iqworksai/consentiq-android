package ai.iqworks.consentiq

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
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
import android.widget.FrameLayout
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
    baseUrl: String,
    private val activities: ForegroundActivityTracker?,
) : CaptchaTokenProvider {
    private val pageUrl = captchaPageUrl(baseUrl)
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var page: Page? = null

    init {
        activities?.addDestroyListener(::detachFrom)
    }

    override suspend fun token(): String =
        mutex.withLock {
            val token = withTimeoutOrNull(TIMEOUT_MS) { withContext(Dispatchers.Main.immediate) { requestToken() } }
            if (token == null) {
                withContext(Dispatchers.Main.immediate) { page?.takeUnless { it.ready.isCompleted }?.let { fail(it, "timeout") } }
                throw CaptchaException("timeout")
            }
            token
        }

    private suspend fun requestToken(): String {
        val current = currentPage()
        attach(current.view)
        current.ready.await()
        val result = CompletableDeferred<String>()
        current.pending = result
        try {
            current.view.evaluateJavascript(EXECUTE_SCRIPT, null)
            return result.await()
        } finally {
            if (current.pending === result) current.pending = null
        }
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
    }

    @SuppressLint("ViewConstructor")
    private class HiddenWebView(
        context: Context,
    ) : WebView(context) {
        init {
            alpha = HIDDEN_ALPHA
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            isLongClickable = false
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        // The 1x1 view sits on top of the app's content; it must never swallow a tap meant for the app.
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val BRIDGE_NAME = "ConsentIQAndroid"
        const val EXECUTE_SCRIPT = "window.consentiqExecute()"
        const val HIDDEN_ALPHA = 0.01f
    }
}
