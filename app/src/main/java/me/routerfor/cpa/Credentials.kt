package me.routerfor.cpa

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Account inventory for the home screen, read from the kernel's own management API.
 *
 * Reading the credential JSON files directly was the earlier approach and could not tell an
 * account's enabled/disabled state, which is exactly what the aggregation switch needs.
 * GET /v8/management/credentials returns files:[{name, auth_index, provider, email, disabled,
 * status, project_id}]. auth_index is what the quota proxy needs, so it is carried on the entry.
 */
object Credentials {

    data class Entry(
        val name: String,
        val provider: String,
        val email: String,
        val disabled: Boolean,
        val status: String,
        val authIndex: String = "",
        val projectId: String? = null
    ) {
        val providerLabel: String get() = Provider.labelOf(provider)
    }

    private const val BASE = "http://127.0.0.1:8317"

    fun list(): List<Entry> = runCatching {
        val body = request("GET", "/v8/management/credentials", null) ?: return emptyList()
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        (0 until files.length()).mapNotNull { i ->
            files.optJSONObject(i)?.let { o ->
                Entry(
                    name = o.optString("name"),
                    provider = o.optString("provider").ifEmpty { o.optString("type") },
                    email = o.optString("email").ifEmpty { o.optString("account") }.ifEmpty { "—" },
                    disabled = o.optBoolean("disabled", false),
                    status = o.optString("status"),
                    authIndex = o.optString("auth_index"),
                    projectId = o.optString("project_id").ifEmpty { null }
                )
            }
        }.filter { it.name.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** Flips one account on/off; PATCH /credentials/status takes {name, disabled}. */
    fun setEnabled(name: String, enabled: Boolean): Boolean = runCatching {
        val payload = JSONObject().put("name", name).put("disabled", !enabled).toString()
        request("PATCH", "/v8/management/credentials/status", payload) != null
    }.getOrDefault(false)

    /** Groups accounts by provider id, preserving the enum order. */
    fun grouped(entries: List<Entry>): List<Pair<Provider, List<Entry>>> =
        Provider.entries.mapNotNull { p ->
            val mine = entries.filter { it.provider.equals(p.id, ignoreCase = true) }
            if (mine.isEmpty()) null else p to mine
        }

    private fun request(method: String, path: String, body: String?): String? {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.setRequestProperty("Authorization", "Bearer " + WebBridgeCompat.managementKey())
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
