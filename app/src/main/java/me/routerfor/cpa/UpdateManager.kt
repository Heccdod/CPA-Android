package me.routerfor.cpa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Two update channels, both opt-in.
 *
 * The shipped sources point at Heccdod/CPA-Android (see BuildConfig.UPDATE_REPO / RULES_URL).
 * Nothing is fetched automatically: every network call starts from an explicit tap in 设置,
 * and L3 kernel loading additionally requires AppPrefs.hotUpdateEnabled.
 *
 * L2 (rules): pull a remote rule pack and hand it to the Go side, which owns the list of keys a
 *             remote file is allowed to touch. The engine's watcher hot-reloads the result.
 * L3 (kernel/APK): read the GitHub Releases API; a newer libgojni.so is staged into
 *             getDir("hotlibs") only when hot update is enabled, and an .apk is handed to the
 *             system installer through FileProvider.
 *
 * The two channels read different endpoints, and that is not incidental: CI publishes upstream
 * builds as pre-releases, and GitHub's /releases/latest excludes pre-releases by definition.
 * Reading only /releases/latest meant the kernel channel could never see a kernel, however many
 * the CI published.
 */
class UpdateManager(private val context: Context) {

    fun syncRules(onDone: (ok: Boolean, message: String) -> Unit) {
        io.execute {
            val result = runCatching {
                val url = BuildConfig.RULES_URL
                if (url.isEmpty()) {
                    return@runCatching "未配置规则地址" to false
                }
                val body = httpGet(url, accept = "application/yaml, text/yaml, text/plain, */*")
                if (body.isBlank()) {
                    return@runCatching "远程规则为空，已保留本地配置" to false
                }
                // The merge happens in Go, not here. Two reasons:
                //  · the whitelist of mergeable keys has to sit next to the YAML writer that
                //    enforces the other invariants, or the two drift apart;
                //  · writing config.yaml from Kotlin means writing it a second way. The old code
                //    wrote a temp file and rename()d it over the target, which swaps the inode
                //    and permanently kills the engine's fsnotify watch — after one rule sync
                //    every later settings change silently stopped taking effect.
                val error = WebBridgeCompat.applyRulePack(context.filesDir.absolutePath, body)
                if (error.isNotEmpty()) {
                    return@runCatching "规则未应用：$error" to false
                }
                "规则已更新（${body.length} 字节），引擎正在热重载" to true
            }.getOrElse { e ->
                "规则同步失败：${e.message}" to false
            }
            onDone(result.second, result.first)
        }
    }

    /**
     * Checks both channels and reports what it found.
     */
    fun checkForUpdates(onDone: (message: String) -> Unit) {
        io.execute {
            val message = runCatching {
                val repo = BuildConfig.UPDATE_REPO
                if (repo.isEmpty()) {
                    return@runCatching "未配置更新仓库"
                }
                val notes = StringBuilder()
                checkAppRelease(repo, notes)
                checkKernelRelease(repo, notes)
                if (notes.isEmpty()) {
                    val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    "已是最新版本（$current）"
                } else {
                    notes.toString().trim()
                }
            }.getOrElse { e ->
                "检查更新失败：${e.message}"
            }
            onDone(message)
        }
    }

    /** App APK channel: /releases/latest returns the newest non-pre-release, which is ours. */
    private fun checkAppRelease(repo: String, notes: StringBuilder) {
        runCatching {
            val json = httpGet(
                "https://api.github.com/repos/$repo/releases/latest",
                accept = "application/vnd.github+json"
            )
            val release = JSONObject(json)
            val tag = release.optString("tag_name")
            val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            if (tag.isEmpty()) return@runCatching
            // Release tags are conventionally "v2.1.0" while versionName is "2.1.0";
            // comparing them verbatim would report an update on every single check.
            if (tag.removePrefix("v") == current.removePrefix("v")) return@runCatching
            val assets = release.optJSONArray("assets") ?: return@runCatching
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                // A release ships both a signed and a debug APK. Installing the debug one over
                // the release one fails the signature check, so only the release asset is
                // offered here.
                if (name.endsWith(".apk") && !name.contains("debug", ignoreCase = true)) {
                    notes.append("发现新版本 $tag（本地 $current）\n")
                    notes.append("发现安装包：$name\n")
                    // The APK is not digest-checked, and it does not need to be: the installer
                    // verifies the signature against the installed app before replacing it, so a
                    // tampered download fails there rather than installing.
                    downloadAndInstall(asset.optString("browser_download_url"), name, notes)
                    return@runCatching
                }
            }
            notes.append("发现新版本 $tag（本地 $current），但没有可安装的 APK\n")
        }.onFailure { notes.append("应用更新检查失败：${it.message}\n") }
    }

    /**
     * Kernel channel: scans the release list for the newest upstream-sync pre-release.
     *
     * Pre-releases are the whole point — marking them as such is what keeps /releases/latest
     * pinned to the hand-cut versioned release, so this channel has to look for them explicitly.
     */
    private fun checkKernelRelease(repo: String, notes: StringBuilder) {
        if (!AppPrefs.hotUpdateEnabled(context)) {
            // Still report that a kernel exists, so the switch is discoverable rather than the
            // channel looking dead.
            runCatching { newestKernelRelease(repo) }.getOrNull()?.let {
                notes.append("发现新内核（${it.first}），但「内核热更新」已关闭，已跳过\n")
            }
            return
        }
        runCatching {
            val found = newestKernelRelease(repo) ?: return@runCatching
            val tag = found.first
            val target = File(hotLibDir(), "libgojni-$tag.so")
            val bytes = httpGetBytes(found.second, limit = MAX_KERNEL_BYTES)
            val actual = sha256(bytes)
            // Fail closed. GitHub does not always populate `digest` on an asset, and the old code
            // treated a missing digest as "no check needed" — which would System.load() an
            // arbitrary downloaded library. A missing digest now falls back to the
            // SHA256SUMS.txt the release also ships; if that is missing too, nothing is staged.
            //
            // Both values come from the same origin, so this catches corruption, not an attacker
            // who can already answer as github.com. That is why the app no longer trusts
            // user-installed CAs outside loopback (see res/xml/network_security_config.xml).
            val expected = found.third.removePrefix("sha256:").ifEmpty {
                readSumsDigest(repo, tag)
            }
            if (expected.isEmpty()) {
                notes.append("内核 $tag 缺少 SHA-256 校验值，已拒绝安装\n")
                return@runCatching
            }
            if (!expected.equals(actual, ignoreCase = true)) {
                notes.append("内核 $tag 的 SHA-256 不匹配，已丢弃\n")
                return@runCatching
            }
            val manifest = File(hotLibDir(), "sha256.txt")
            if (manifest.isFile && manifest.readText().trim().equals(actual, ignoreCase = true)) {
                notes.append("内核已是最新（$tag）\n")
                return@runCatching
            }
            hotLibDir().mkdirs()
            writeDurably(target, bytes)
            manifest.writeText(actual)
            File(hotLibDir(), "pending.txt").writeText(target.name)
            // Restarting the gateway does *not* pick this up: System.load() resolves a library
            // name once per process, and HotSoLoader.loaded is set in Application.onCreate, so
            // the new .so is only ever mapped after the process itself is replaced. Promising a
            // gateway restart here sent users looking for a refresh that could not happen.
            notes.append("新内核已就绪（${bytes.size} 字节）\n")
            notes.append("需要完全退出 App 再打开才会加载（重启网关不够）\n")
        }.onFailure { notes.append("内核检查失败：${it.message}\n") }
    }

    /**
     * (tag, soUrl, digest) for the newest release that carries a kernel asset.
     *
     * "Newest" is decided here by comparing publish times, not by trusting the order of the API
     * response. That order is not publication order: on the live repository the first entry was a
     * kernel from three days earlier while the newest one sat fourth, so taking the first match
     * picked a stale kernel — and a merely-older kernel still installs fine, so nothing would
     * have reported it.
     */
    private fun newestKernelRelease(repo: String): Triple<String, String, String>? {
        val json = httpGet(
            "https://api.github.com/repos/$repo/releases?per_page=20",
            accept = "application/vnd.github+json"
        )
        val releases = JSONArray(json)
        var best: Triple<String, String, String>? = null
        var bestAt = ""
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            // Only the automatic upstream syncs carry a bare kernel; hand-cut releases ship the
            // APK, whose kernel is already inside it.
            if (!release.optBoolean("prerelease", false)) continue
            val tag = release.optString("tag_name")
            if (!tag.startsWith("upstream-")) continue
            val publishedAt = release.optString("published_at")
            // Compare timestamps so the freshest kernel wins regardless of response order. A
            // release without a timestamp cannot be compared, so it only counts when nothing
            // else has matched yet.
            if (best != null && (publishedAt.isEmpty() || publishedAt < bestAt)) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (name.contains("libgojni", ignoreCase = true) &&
                    name.endsWith(".so", ignoreCase = true)
                ) {
                    best = Triple(
                        tag,
                        asset.optString("browser_download_url"),
                        asset.optString("digest")
                    )
                    bestAt = publishedAt
                    break
                }
            }
        }
        return best
    }

    /**
     * Reads the kernel digest out of the release's SHA256SUMS.txt.
     *
     * Used when the API omits `digest`. Returns "" when the file is absent or does not mention a
     * kernel, which the caller treats as "cannot verify, do not install".
     */
    private fun readSumsDigest(repo: String, tag: String): String {
        val sums = runCatching {
            String(
                httpGetBytes("https://github.com/$repo/releases/download/$tag/SHA256SUMS.txt"),
                Charsets.UTF_8
            )
        }.getOrNull() ?: return ""
        for (line in sums.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[1].contains("libgojni", ignoreCase = true)) {
                return parts[0]
            }
        }
        return ""
    }

    private fun downloadAndInstall(url: String, name: String, notes: StringBuilder) {
        // The release asset name is attacker-controlled text; it becomes a path inside filesDir
        // and is handed to the installer. Keep only the basename and refuse separators, so
        // "../shared_prefs/cpa-prefs.xml" cannot be written by way of a download.
        val safeName = File(name).name
        if (safeName.isEmpty() || safeName != name || safeName.contains('\\')) {
            notes.append("安装包文件名不合法，已忽略\n")
            return
        }
        val dir = File(context.filesDir, "updates")
        dir.mkdirs()
        // A downloaded APK is ~23 MB and the installer never deletes it, so every update used to
        // leave another copy behind. Clear the directory first: the only APK that matters is the
        // one about to be written.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val apk = File(dir, safeName)
        // Written through writeDurably: an APK truncated by a kill mid-download presents itself
        // to the installer as a corrupt package, and the user sees a parse error instead of an
        // update. The rename-free write keeps the file consistent with what was downloaded.
        writeDurably(apk, httpGetBytes(url, limit = MAX_APK_BYTES))
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // A device with installs disabled has no handler for this intent; that is a reportable
        // condition, not a crash on the update-check thread.
        runCatching { context.startActivity(intent) }
            .onFailure { notes.append("无法拉起安装器：${it.message}\n") }
    }

    /**
     * Deletes APKs staged for install. Called on every cold start.
     *
     * The engine trims its own log when it attaches the logger; this covers the APKs, which
     * nothing else would ever remove.
     */
    fun reclaimStorage() {
        io.execute {
            runCatching {
                File(context.filesDir, "updates").listFiles()?.forEach { it.delete() }
            }
        }
    }

    private fun hotLibDir(): File = HotSoLoader.hotLibDir(context)

    private fun httpGet(url: String, accept: String): String =
        String(httpGetBytes(url, accept = accept), Charsets.UTF_8)

    /**
     * Fetches a URL into memory, refusing anything above [limit].
     *
     * readBytes() on an unchunked response grows until the heap gives out; the check is done both
     * against Content-Length (when the server sends one) and against the bytes actually read, so
     * a server that lies about the length still cannot exhaust the process.
     */
    private fun httpGetBytes(url: String, accept: String = "*/*", limit: Long = MAX_JSON_BYTES): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "CPA-Android/" + Build.VERSION.SDK_INT)
        conn.instanceFollowRedirects = true
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val declared = conn.contentLengthLong
            if (declared > limit) {
                throw IllegalStateException("响应过大（$declared 字节）")
            }
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > limit) {
                        throw IllegalStateException("响应超过 ${limit} 字节上限")
                    }
                    out.write(buffer, 0, read)
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Writes [bytes] to [file] in place, with an fsync.
     *
     * In place, not via a temp file and rename(): renaming over the target replaces its inode, and
     * the engine's file watcher is registered on the inode. That is the whole reason settings
     * stopped hot-reloading in v2.0 — see docs/ENGINEERING.md. The update directories are not
     * watched, but writing one way everywhere means the mistake cannot be reintroduced by moving
     * a function.
     */
    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
        private const val MAX_KERNEL_BYTES = 200L * 1024 * 1024

        /**
         * One executor for the whole process.
         *
         * A per-instance pool leaked a thread on every cold start (CpaApp constructs an
         * UpdateManager in onCreate and never shuts it down); a daemon-backed single thread does
         * the same work without holding the process open.
         */
        private val io: java.util.concurrent.ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "cpa-update").apply { isDaemon = true }
            }
        }
    }
}
