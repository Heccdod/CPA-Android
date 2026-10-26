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

    fun ensureDefaultConfig(dataDir: String, proxyUrl: String, allowLan: Boolean): String = try {
        Cliproxymobile.ensureDefaultConfig(dataDir, proxyUrl, allowLan)
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

    fun updateQuickSettings(dataDir: String, proxyUrl: String, allowLan: Boolean, csv: String): String = try {
        Cliproxymobile.updateQuickSettings(dataDir, proxyUrl, allowLan, csv)
    } catch (t: Throwable) {
        t.message ?: t.javaClass.simpleName
    }
}
