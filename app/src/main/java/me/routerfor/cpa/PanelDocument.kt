package me.routerfor.cpa

import android.util.Log
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the loopback panel document once, applies [PanelSanitizer] (drop relay-station
 * promotion) and [PanelPolyfill] (ES2022 shims for old system WebViews), and hands the result
 * to the WebView through shouldInterceptRequest.
 *
 * Doing both rewrites in one place keeps the WebView from ever seeing the unpatched document,
 * which matters because the sanitised strings are inside the inlined module script.
 */
object PanelDocument {

    private const val TAG = "CpaPanel"
    const val PANEL_PATH = "/management.html"

    fun intercepted(url: String, colors: Palette.Colors): ByteArrayInputStream? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Accept", "text/html")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            null
        } else {
            val raw = conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            conn.disconnect()
            val clean = PanelSanitizer.strip(raw)
            val skinned = PanelSkin.inject(clean, colors.resolved, colors)
            val patched = PanelPolyfill.inject(skinned)
            Log.i(
                TAG,
                "panel raw=${raw.length} clean=${clean.length} patched=${patched.length} " +
                    "sponsor=${PanelSanitizer.hasSponsorContent(clean)} theme=${colors.resolved}"
            )
            ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "panel rewrite failed, serving the raw document", t)
        null
    }
}