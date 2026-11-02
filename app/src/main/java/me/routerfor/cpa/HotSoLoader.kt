package me.routerfor.cpa

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * L3 kernel hot-load, gated by AppPrefs.hotUpdateEnabled (default OFF).
 *
 * When the switch is off the packaged libgojni.so is the only kernel that can ever load, and
 * anything already staged in getDir("hotlibs") is deleted on start so a previously downloaded
 * kernel cannot silently come back after the user turns the feature off.
 *
 * When the switch is on, a staged .so is verified against its SHA-256 manifest and loaded with
 * System.load() before go.Seq is touched. Only the first load in the process counts: once
 * go.Seq is initialised the library cannot be swapped, so this must run in Application.onCreate.
 */
object HotSoLoader {

    private const val TAG = "CpaHotSo"
    private const val MANAGED_LIB = "gojni"
    private const val PACKAGED = "packaged"
    private const val LOADED = "loaded.txt"

    @Volatile
    private var loaded = false

    @Synchronized
    fun loadEngine(context: Context) {
        if (loaded) return
        val enabled = AppPrefs.hotUpdateEnabled(context)
        if (!enabled) {
            clearStaged(context)
            System.loadLibrary(MANAGED_LIB)
            recordLoaded(context, PACKAGED)
            Log.i(TAG, "hot update disabled, loaded packaged kernel libgojni.so")
            loaded = true
            return
        }
        val hot = verifiedHotLibrary(context)
        if (hot != null) {
            try {
                System.load(hot.absolutePath)
                recordLoaded(context, hot.name)
                Log.i(TAG, "loaded hot-updated kernel ${hot.name}")
                loaded = true
                return
            } catch (t: Throwable) {
                Log.w(TAG, "hot kernel rejected (${t.message}), falling back to the packaged one")
                hot.delete()
            }
        }
        System.loadLibrary(MANAGED_LIB)
        recordLoaded(context, PACKAGED)
        Log.i(TAG, "loaded packaged kernel libgojni.so")
        loaded = true
    }

    /**
     * Notes which kernel this process actually mapped.
     *
     * A staged .so does nothing on its own: System.load() resolves a name once per process and
     * `loaded` latches in Application.onCreate, so a freshly downloaded kernel is inert until the
     * process is replaced. Without this record the UI could only say "有已下载内核", which reads as
     * "already running it" and sent people looking for a restart that cannot possibly help.
     */
    private fun recordLoaded(context: Context, name: String) {
        runCatching { File(hotLibDir(context), LOADED).writeText(name) }
    }

    /** The kernel mapped by this process, or "" when [loadEngine] has not run. */
    fun loadedKernel(context: Context): String {
        val file = File(hotLibDir(context), LOADED)
        if (!file.isFile) return ""
        return runCatching { file.readText().trim() }.getOrDefault("")
    }

    /**
     * True when a newer kernel is staged and only a process restart can pick it up.
     *
     * Compares the pending name against the one that was actually loaded, so the state clears
     * itself after the restart instead of nagging forever.
     */
    fun restartPending(context: Context): Boolean {
        if (!AppPrefs.hotUpdateEnabled(context)) return false
        val pending = File(hotLibDir(context), "pending.txt")
        if (!pending.isFile) return false
        val name = runCatching { pending.readText().trim() }.getOrDefault("")
        if (name.isEmpty()) return false
        if (!File(hotLibDir(context), name).isFile) return false
        return name != loadedKernel(context)
    }

    /** Drops staged kernels; called when the switch is turned off and on every cold start with it off. */
    fun clearStaged(context: Context) {
        val dir = hotLibDir(context)
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    fun hasStaged(context: Context): Boolean =
        hotLibDir(context).listFiles()?.any { it.isFile && it.name.endsWith(".so") } == true

    fun hotLibDir(context: Context): File = context.getDir("hotlibs", Context.MODE_PRIVATE)

    /**
     * Newest staged .so whose bytes match the recorded SHA-256 manifest, or null.
     *
     * Fail closed. The manifest used to be optional (`expected.isNotEmpty() && ...`), so a
     * missing sha256.txt meant every candidate passed and an unverified download went straight
     * into System.load(). Only a library with a recorded digest may load now; a staged file
     * without one is deleted rather than left to confuse the next start.
     */
    fun verifiedHotLibrary(context: Context): File? {
        val dir = hotLibDir(context)
        if (!dir.isDirectory) return null
        val pending = File(dir, "pending.txt")
        val expected = readManifest(context)
        if (expected.isEmpty()) {
            Log.w(TAG, "no SHA-256 manifest; refusing to load any staged kernel")
            return null
        }
        val candidates = dir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: return null
        for (file in candidates.sortedByDescending { it.lastModified() }) {
            if (pending.exists() && pending.readText().trim() != file.name) continue
            val actual = runCatching { sha256(file) }.getOrNull()
            if (actual == null || !expected.equals(actual, ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch for ${file.name}, skipping")
                continue
            }
            return file
        }
        return null
    }

    private fun readManifest(context: Context): String {
        val manifest = File(hotLibDir(context), "sha256.txt")
        if (!manifest.isFile) return ""
        return manifest.readText().trim()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
