package me.routerfor.cpa

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks the loopback management API for a provider OAuth URL without any copy/paste.
 *
 * GET /v8/management/oauth/auth-url?provider=<id>&is_webui=1 makes the Go kernel:
 *  1. build the provider authorization URL with its own redirect_uri
 *     (for Antigravity that is http://localhost:51121/oauth-callback),
 *  2. start a callback forwarder on the loopback port and relay the browser redirect to
 *     /<provider>/callback, and
 *  3. exchange the code and persist the credential into filesDir/auths/.
 * The Android side only has to open the returned URL in a Chrome Custom Tab.
 */
object OauthLauncher {

    private const val BASE = "http://127.0.0.1:8317"

    /** Returns the authorization URL, or null when the gateway is not serving or has no OAuth. */
    fun requestAuthUrl(provider: String = "antigravity"): String? = runCatching {
        val endpoint = BASE + "/v8/management/oauth/auth-url?provider=" + provider + "&is_webui=1"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "Bearer " + WebBridgeCompat.managementKey())
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) {
                return@runCatching null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("url").takeIf { it.isNotEmpty() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
