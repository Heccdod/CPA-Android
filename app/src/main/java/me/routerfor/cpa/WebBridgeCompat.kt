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
        "127.0.0.1:8317"
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
}
