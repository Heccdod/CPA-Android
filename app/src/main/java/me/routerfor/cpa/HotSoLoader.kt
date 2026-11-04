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

    /**
     * True once [loadEngine] has run, so [hotState] can tell "not verified yet" from "verified and
     * it passed".
     *
     * hotState() is called from a render pass on the main thread, and re-running the verification
     * there would SHA-256 a 20 MB library on the UI thread. The verdict already exists: loadEngine()
     * computed it in Application.onCreate, once per process.
     */
    @Volatile
    private var verifiedKnown = false

    /**
     * True when this start had something staged and verification rejected all of it.
     *
     * Kept apart from "nothing staged yet": a kernel downloaded after this process started is
     * also unverified, but waiting for a restart fixes that one. Only a file that was already on
     * disk and still failed the manifest is broken, and only that state is worth telling the user
     * to re-download.
     */
    @Volatile
    private var verifiedRejected = false

    @Synchronized
    fun loadEngine(context: Context) {
        if (loaded) return
        val enabled = AppPrefs.hotUpdateEnabled(context)
        if (!enabled) {
            clearStaged(context)
            verifiedKnown = true
            verifiedRejected = false
            System.loadLibrary(MANAGED_LIB)
            recordLoaded(context, PACKAGED)
            Log.i(TAG, "hot update disabled, loaded packaged kernel libgojni.so")
            loaded = true
            return
        }
        val hot = verifiedHotLibrary(context)
        verifiedKnown = true
        // A staged library that verified clean is never "rejected", whatever happens to the file
        // afterwards; only a start that had something on disk and refused all of it counts.
        verifiedRejected = hot == null && pendingName(context).isNotEmpty()
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
                // The only copy is gone now, so this is the re-download case rather than the
                // restart one.
                verifiedRejected = true
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

    /**
     * The kernel this install is actually in, as one of four strings.
     *
     *   "off"           hot update is switched off; the packaged kernel is the only one that loads
     *   "pending"       something was staged but cannot be loaded — the pending name is gone from
     *                   disk, or verification rejected every candidate. Neither the packaged
     *                   fallback nor a silent skip is visible anywhere else, and the settings
     *                   screen used to report this as "已开启 · 当前用内置内核", the same line a
     *                   fresh install shows before its first download.
     *   "packaged"      verification passed nothing and the packaged kernel is what loaded
     *   "hot:<name>"    a staged library is mapped for this process
     *
     * Derived from the state [loadEngine] already computed; nothing is re-hashed here, because
     * this runs on the main thread while the library is ~20 MB.
     */
    fun hotState(context: Context): String {
        if (!AppPrefs.hotUpdateEnabled(context)) return "off"
        val loadedName = loadedKernel(context)
        val hotLoaded = loadedName.isNotEmpty() && loadedName != PACKAGED
        if (hotLoaded) return "hot:" + loadedName
        // Nothing hot is mapped. "pending" is reserved for the one state the user has to act on:
        // something was on disk at startup and verification threw all of it out. A missing
        // pending.txt means nothing was ever staged; a staged file whose digest is fine but has
        // not been mapped yet is the ordinary "restart to pick it up" case, which
        // restartPending() reports and which is not a defect.
        if (verifiedKnown && verifiedRejected) return "pending"
        // pending.txt names a file that is gone: a restart cannot fix that, so it belongs with
        // "re-download" rather than with "restart". Checked from disk rather than from
        // verifiedRejected so it also holds when the file was removed after this process started.
        val staged = pendingName(context)
        if (staged.isNotEmpty() && !File(hotLibDir(context), staged).isFile) return "pending"
        return "packaged"
    }

    /** The name pending.txt points at, or "" when there is none. */
    private fun pendingName(context: Context): String {
        val pending = File(hotLibDir(context), "pending.txt")
        if (!pending.isFile) return ""
        return runCatching { pending.readText().trim() }.getOrDefault("")
    }

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
        val candidates = dir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
        if (candidates == null || candidates.isEmpty()) {
            Log.w(TAG, "no verified hot kernel: no .so staged in ${dir.absolutePath}")
            return null
        }
        for (file in candidates.sortedByDescending { it.lastModified() }) {
            if (pending.exists() && pending.readText().trim() != file.name) continue
            val actual = runCatching { sha256(file) }.getOrNull()
            if (actual == null || !expected.equals(actual, ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch for ${file.name}, skipping")
                continue
            }
            return file
        }
        // Reached when every candidate was filtered out. The two causes look different to the
        // user but are the same dead end here: pending.txt names a file that is gone, or the
        // digest in sha256.txt belongs to a different build than the .so on disk. Silence at
        // this point is what let a staged kernel rot unnoticed — the app quietly used the
        // packaged one and the settings screen still said "已开启".
        Log.w(
            TAG,
            "no verified hot kernel: " + candidates.size + " candidate(s) " +
                candidates.joinToString { it.name } + " did not match the manifest, pending=" +
                runCatching { pending.readText().trim() }.getOrDefault("")
        )
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
