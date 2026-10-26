package me.routerfor.cpa

/**
 * Mirrors Cli-Proxy-API-Management-Center secureStorage obfuscation so the WebView can be
 * pre-authenticated against the loopback gateway without showing a login form.
 */
object PanelJs {

    private const val ENC_PREFIX = "enc::v1::"
    private const val SECRET_SALT = "cli-proxy-api-webui::secure-storage"

    /**
     * Pre-seeds the panel localStorage:
     *  cli-proxy-auth        zustand persisted auth store (xor-obfuscated JSON)
     *  isLoggedIn            makes restoreSession() auto-login on boot
     *  apiBase/managementKey legacy plaintext keys the panel migrates on boot
     *
     * The obfuscation key is xor(salt|location.host|navigator.userAgent) and is rebuilt
     * inside the page, so the payload keeps working when the panel re-reads it.
     */
    fun seedScript(apiBase: String, managementKey: String): String {
        val store = "{" +
            jsQuote("apiBase") + ":" + jsQuote(apiBase) + "," +
            jsQuote("managementKey") + ":" + jsQuote(managementKey) + "," +
            jsQuote("rememberPassword") + ":true," +
            jsQuote("serverVersion") + ":null," +
            jsQuote("serverBuildDate") + ":null}"
        val lines = listOf(
            "(function(){try{",
            "var PREFIX=" + sq(ENC_PREFIX) + ";",
            "var SALT=" + sq(SECRET_SALT) + ";",
            "function b64(b){var s='';for(var i=0;i<b.length;i++){s+=String.fromCharCode(b[i]);}return btoa(s);}",
            "function key(){return new TextEncoder().encode(SALT+'|'+window.location.host+'|'+navigator.userAgent);}",
            "function enc(t){var d=new TextEncoder().encode(t);var k=key();var o=new Uint8Array(d.length);",
            "for(var i=0;i<d.length;i++){o[i]=d[i]^k[i%k.length];}return PREFIX+b64(o);}",
            "localStorage.setItem('cli-proxy-auth',enc(" + sq(store) + "));",
            "localStorage.setItem('isLoggedIn','true');",
            "localStorage.setItem('apiBase'," + sq(apiBase) + ");",
            "localStorage.setItem('managementKey'," + sq(managementKey) + ");",
            "}catch(e){console.warn('cpa seed failed',e);}})();"
        )
        return lines.joinToString("")
    }

    fun defaultSensitiveWordsCsv(): String =
        "Claude Agent SDK, RFC 2119, system-conventions, system_conventions, " +
            "system-directive, system_directive, Hermes Agent, Codex"

    // Character constants by code point so no escaping tricks are needed in Kotlin source.
    private val BS: String = 92.toChar().toString()   // backslash
    private val SQ: String = 39.toChar().toString()   // single quote
    private val DQ: String = 34.toChar().toString()   // double quote

    /** Single-quoted JS string literal. */
    private fun sq(value: String): String =
        SQ + value.replace(BS, BS + BS).replace(SQ, BS + SQ) + SQ

    /** Double-quoted JSON string literal. */
    private fun jsQuote(value: String): String =
        DQ + value.replace(BS, BS + BS).replace(DQ, BS + DQ) + DQ
}