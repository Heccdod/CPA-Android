package me.routerfor.cpa

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabsIntent
import java.io.ByteArrayInputStream

/**
 * The management panel WebView: pre-authenticated, sanitised, polyfilled, and routing provider
 * OAuth to Chrome Custom Tabs so the localhost:51121 callback never needs a paste.
 *
 * Used both inline on the home screen and full-bleed in PanelActivity.
 */
@SuppressLint("SetJavaScriptEnabled")
class PanelWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    /** Called once the SPA has actually painted, so the host can drop its loading state. */
    var onPanelReady: (() -> Unit)? = null

    /** Called with a human-readable line when the panel cannot render. */
    var onPanelProblem: ((String) -> Unit)? = null

    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Scrollbar and the stretch/overscroll glow both read as a stray shadow on this page.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = false
        isFocusableInTouchMode = false
        setOnLongClickListener { true }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // The panel's floating header keeps state-based box-shadows after a tap; a WebView that
        // owns focus also gets the platform's focus highlight. Both read as a shadow that never
        // clears, so the view never takes focus and the panel's own :focus-visible style is
        // re-enabled through the injected stylesheet below.
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webViewClient = PanelClient()
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                Log.i(TAG, "${msg.messageLevel()} ${msg.message()} @${msg.lineNumber()}")
                return true
            }
        }
    }

    fun panelUrl(): String = GatewayPort.base() + PanelDocument.PANEL_PATH

    /** Loads the panel if it is not shown yet; returns false when the engine is not up. */
    fun loadIfNeeded(force: Boolean = false): Boolean {
        if (!WebBridgeCompat.isRunning()) return false
        if (force || url == null) loadUrl(panelUrl())
        return true
    }

    private inner class PanelClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            intercept(request.url)

        @Suppress("OVERLOADS_ARE_USELESS")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            intercept(Uri.parse(url))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = request.url
            // The panel bundle carries a Cloudflare RUM beacon that posts back to whatever host serves
            // it; against the loopback engine that is a guaranteed 404 in the log every few seconds.
            if (uri.path?.startsWith("/cdn-cgi/") == true) {
                return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)))
            }
            if (isLoopback(uri) && uri.path == PanelDocument.PANEL_PATH) {
                val stream = PanelDocument.intercepted(uri.toString(), palette())
                if (stream != null) return WebResourceResponse("text/html", "utf-8", stream)
                // The rewrite is a synchronous loopback fetch. When the engine has accepted the
                // socket but stopped answering (the half-dead state the 150 ms probe exists for)
                // it can block this request thread for the whole 8 s + 20 s timeout budget, and
                // onPageFinished then never fires — awaitMount never starts and the screen spins
                // forever with no callback. Report it instead of falling through silently.
                onPanelProblem?.invoke("管理面板没有响应，请在主界面重启网关后重试")
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            Log.w(TAG, "error ${error.errorCode} ${error.description} ${request.url}")
            super.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            Log.w(TAG, "http ${response.statusCode} ${request.url}")
            super.onReceivedHttpError(view, request, response)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Seed only for the panel's own document. This used to run before the check, which
            // meant every page the WebView was navigated to — a help link, a provider's docs, an
            // ad — received localStorage.managementKey on load. The management secret unlocks
            // config writes and credential reads, so leaking it to one third-party origin hands
            // over the whole gateway.
            if (!isPanelUrl(url)) return
            seedSession(view)
            awaitMount(view, 0)
        }
    }

    private fun isPanelUrl(url: String?): Boolean =
        url != null && Uri.parse(url).path == PanelDocument.PANEL_PATH

    /**
     * The panel is a single-page app: the served HTML ships an empty #root and the inlined module
     * script fills it, which on a cold engine start can trail onPageFinished by seconds. Poll for
     * the first mount instead of judging it once, or a slow boot reads as a render failure.
     */
    private fun awaitMount(view: WebView, attempt: Int) {
        if (!isAttachedToWindow) return
        view.evaluateJavascript(
            "(function(){var r=document.getElementById('root');" +
                "return 'root='+(r?r.childNodes.length:'none')})()"
        ) { value ->
            val dom = value ?: ""
            val mounted = dom.contains("root=") &&
                !dom.contains("root=0") && !dom.contains("root=none")
            when {
                mounted -> {
                    Log.i(TAG, "panel mounted after " + (attempt * POLL_MS) + "ms (" + dom + ")")
                    onPanelReady?.invoke()
                }
                attempt < MAX_POLLS -> view.postDelayed({ awaitMount(view, attempt + 1) }, POLL_MS)
                else -> {
                    Log.w(TAG, "panel never mounted (" + dom + ")")
                    onPanelProblem?.invoke("面板脚本没有跑起来，请更新 Android System WebView 后重试")
                }
            }
        }
    }

    /** The palette the panel document is painted with, resolved once per request. */
    private fun palette(): Palette.Colors = ThemeManager.colors(context)

    private fun paletteMode(): Palette.Resolved = palette().resolved

    private fun isLoopback(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host == "127.0.0.1" || host == "localhost"
    }

    /** Pre-seeds the panel's obfuscated auth store so the loopback login screen never appears. */
    private fun seedSession(view: WebView) {
        val host = view.url?.let {
            val u = Uri.parse(it)
            val port = if (u.port > 0) ":${u.port}" else ""
            "${u.scheme}://${u.host}$port"
        } ?: GatewayPort.base()
        view.evaluateJavascript(PanelJs.seedScript(host, WebBridgeCompat.managementKey()), null)
        view.evaluateJavascript(PanelTheme.script(paletteMode()), null)
    }

    /**
     * Decides what a tap inside the panel may reach.
     *
     * Provider OAuth pages and the loopback callback go to a Custom Tab. Everything else that is
     * not the panel itself is handed to the system browser instead of being loaded in place: the
     * WebView holds the management secret in localStorage and the panel document is injected with
     * the app's own scripts, so a third-party page must never share this context. Returning
     * false for those URLs — which is what this did — navigated the WebView to the link.
     */
    private fun intercept(uri: Uri): Boolean {
        val host = uri.host ?: return false
        val scheme = uri.scheme ?: ""
        if (scheme != "http" && scheme != "https") return false
        // Host comparison, not substring: "claude.ai.evil.com" must not match claude.ai.
        val providerAuth = PROVIDER_HOSTS.any { host == it || host.endsWith("." + it) }
        val callback = isLoopback(uri) &&
            (uri.port == OAUTH_CALLBACK_PORT || uri.path?.contains("oauth-callback") == true)
        if (providerAuth || callback) {
            openCustomTab(uri)
            return true
        }
        if (isLoopback(uri) && uri.path == PanelDocument.PANEL_PATH) return false
        // Any other destination leaves the app. doUpdateVisitedHistory would keep the panel's own
        // URL in view.url, but the navigation itself must not happen here.
        openCustomTab(uri)
        return true
    }

    private fun openCustomTab(uri: Uri) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        try {
            intent.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            // The fallback needs the same guard: a device with no browser at all throws here as
            // well, and that path used to be unguarded — a panel tap on such a device crashed.
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (second: ActivityNotFoundException) {
                Log.w(TAG, "no browser to open " + uri.host)
            }
        }
    }

    companion object {
        private const val TAG = "CpaWeb"
        private const val OAUTH_CALLBACK_PORT = 51121
        private const val POLL_MS = 250L
        private const val MAX_POLLS = 60
        private val PROVIDER_HOSTS = listOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "auth.openai.com",
            "claude.ai",
            "console.anthropic.com"
        )
    }
}