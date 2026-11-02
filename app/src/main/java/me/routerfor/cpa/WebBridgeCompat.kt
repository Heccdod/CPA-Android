package me.routerfor.cpa

import me.routerfor.cliproxy.cliproxymobile.Cliproxymobile

/** Thin, exception-free wrapper around the gomobile generated bindings. */
object WebBridgeCompat {

    fun isRunning(): Boolean = try {
        Cliproxymobile.isRunning()
    } catch (t: Throwable) {
        false
    }

    fun listenAddress(): String = try {
        Cliproxymobile.getListenAddress()
    } catch (t: Throwable) {
        "127.0.0.1:" + GatewayPort.FALLBACK_PORT
    }

    fun managementKey(): String = try {
        Cliproxymobile.getManagementKey()
    } catch (t: Throwable) {
        "cpa-android-local"
    }

    fun lastError(): String? = try {
        Cliproxymobile.getLastError()
    } catch (t: Throwable) {
        null
    }

    /**
     * One of [GatewayStatus.RUNNING] / [GatewayStatus.STARTING] / [GatewayStatus.STOPPED] /
     * [GatewayStatus.ERROR].
     *
     * Read as a single value rather than combining isRunning() with lastError() in the UI: the
     * three facts come from different moments, and a repaint that straddles a stop could show a
     * state that never existed. The engine answers them under one lock and with a live probe.
     */
    fun status(): String = try {
        Cliproxymobile.status()
    } catch (t: Throwable) {
        GatewayStatus.ERROR
    }

    /** Records the user's intent so the engine can tell "stopped" from "still starting". */
    fun setDesiredRunning(want: Boolean) = try {
        Cliproxymobile.setDesiredRunning(want)
    } catch (t: Throwable) {
        // Intent is also recorded by startProxy/stopProxy themselves; a failure here is not
        // worth surfacing because the next start re-asserts it.
    }

    fun desiredRunning(): Boolean = try {
        Cliproxymobile.getDesiredRunning()
    } catch (t: Throwable) {
        false
    }

    /**
     * Seeds filesDir/config.yaml (or brings an existing one up to date) before the engine reads
     * it.
     *
     * [thinkingMode] must be the mode the user picked: the Go side rewrites the payload rule to
     * match, so passing a stale value here would silently undo a settings change on the next
     * start.
     */
    fun ensureDefaultConfig(
        dataDir: String,
        proxyUrl: String,
        allowLan: Boolean,
        thinkingMode: String
    ): String = try {
        Cliproxymobile.ensureDefaultConfig(dataDir, proxyUrl, allowLan, thinkingMode)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    fun startProxy(dataDir: String): String = try {
        Cliproxymobile.startProxy(dataDir)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    fun stopProxy(): String = try {
        Cliproxymobile.stopProxy()
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    fun restartProxy(dataDir: String): String = try {
        Cliproxymobile.restartProxy(dataDir)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    fun updateQuickSettings(
        dataDir: String,
        proxyUrl: String,
        allowLan: Boolean,
        csv: String,
        thinkingMode: String
    ): String = try {
        Cliproxymobile.updateQuickSettings(dataDir, proxyUrl, allowLan, csv, thinkingMode)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    /**
     * The configured OpenAI-compatible providers, as the Go side serialises them.
     *
     * JSON rather than a bound object graph because gobind cannot marshal structs or slices:
     * every export crosses the boundary as a string. An empty result still has to be valid JSON,
     * so a failure in Go comes back as {"providers":[],"error":"..."} instead of blank text.
     */
    fun getCustomProviders(dataDir: String): String = try {
        Cliproxymobile.getCustomProviders(dataDir)
    } catch (t: Throwable) {
        "{\"providers\":[],\"error\":\"" + (t.message ?: t.javaClass.simpleName)
            .replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"
    }

    fun setCustomProviders(dataDir: String, payload: String): String = try {
        Cliproxymobile.setCustomProviders(dataDir, payload)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    /**
     * The key a client (SillyTavern, an SDK, curl) has to present to /v1.
     *
     * This is *not* the management key. The two were the same constant until api-keys became a
     * randomly generated `cpa-android-<hex>`, and the home screen kept showing the management
     * key: copying it into a client produced a 401 that looked like a broken gateway.
     */
    fun clientApiKey(dataDir: String): String = try {
        Cliproxymobile.clientAPIKey(dataDir)
    } catch (t: Throwable) {
        ""
    }

    /**
     * Replaces every entry of access.api-keys that still holds the legacy literal.
     *
     * Nothing migrates on upgrade by itself: the seeded key is only generated when config.yaml
     * does not exist yet, and no settings write ever touches that list. An install that started
     * on v2.0 therefore keeps the string this repository publishes, which is the entire gate on
     * a 0.0.0.0 bind. Returns "" when it succeeded or had nothing to do.
     */
    fun rotateClientApiKey(dataDir: String): String = try {
        Cliproxymobile.rotateClientAPIKey(dataDir)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    /**
     * Applies a remote rule pack through the Go side, which owns the whitelist of keys a remote
     * file may touch. Returns "" on success.
     */
    fun applyRulePack(dataDir: String, text: String): String = try {
        Cliproxymobile.applyRulePack(dataDir, text)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }

    /**
     * Writes the whole document the config editor holds, through the same validate-then-write
     * path every other writer uses. Returns "" on success.
     */
    fun setConfigText(dataDir: String, text: String): String = try {
        Cliproxymobile.setConfigText(dataDir, text)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }
}
