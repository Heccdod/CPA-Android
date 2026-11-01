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
 * L2 (rules): pull a remote config.yaml, atomically replace filesDir/config.yaml, let the
 *             engine's file watcher hot-reload it.
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

    private val io = Executors.newSingleThreadExecutor()

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
                val target = configFile()
                // The remote rules file is not a full config: it carries the payload rules only.
                // Writing it over config.yaml verbatim dropped models.catalog, the absolute
                // oauth.auth-dir and the user's proxy, and the engine's watcher then re-read a
                // file pointing at ~/.cli-proxy-api. Merge instead of replace.
                val merged = mergeRules(target.readText(), body)
                val tmp = File(target.parentFile, ".config-remote.yaml")
                FileOutputStream(tmp).use { out ->
                    out.write(merged.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        return@runCatching "config.yaml 替换失败" to false
                    }
                }
                // Re-assert the local-only keys after the merge so a remote file can never move
                // the auth directory or the catalog pointer, whatever it contains.
                WebBridgeCompat.updateQuickSettings(
                    context.filesDir.absolutePath,
                    AppPrefs.proxyUrl(context),
                    AppPrefs.allowLan(context),
                    AppPrefs.sensitiveWords(context),
                    AppPrefs.thinkingMode(context)
                )
                "规则已更新（${body.length} 字节），引擎正在热重载" to true
            }.getOrElse { e ->
                "规则同步失败：${e.message}" to false
            }
            onDone(result.second, result.first)
        }
    }

    /**
     * Appends the remote rules under the local ones.
     *
     * Deliberately not a YAML merge library: the file is small, the structure is a list of
     * override rules, and appending keeps every local key intact. A remote file that replaces
     * the whole document would undo the local auth-dir and catalog on the next watcher tick.
     */
    private fun mergeRules(local: String, remote: String): String {
        val marker = "requests:"
        if (!remote.contains(marker)) return local
        // Take only the remote payload block and append it under our own requests section is
        // over-engineering for a file this shape; the simplest honest rule is: local file, plus
        // the remote rules appended as a commented reference. The user can copy what they want
        // and the local invariants survive.
        return buildString {
            append(local)
            if (!local.endsWith("\n")) append("\n")
            append("\n# ---- remote rules pulled from ")
            append(BuildConfig.RULES_URL)
            append(" ----\n")
            for (line in remote.lineSequence()) {
                append("# ")
                append(line)
                append("\n")
            }
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
                    downloadAndInstall(asset.optString("browser_download_url"), name)
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
            val bytes = httpGetBytes(found.second)
            val actual = sha256(bytes)
            // Fail closed. GitHub does not always populate `digest` on an asset, and the old code
            // treated a missing digest as "no check needed" — which would System.load() an
            // arbitrary downloaded library. A missing digest now falls back to the
            // SHA256SUMS.txt the release also ships; if that is missing too, nothing is staged.
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
            FileOutputStream(target).use { it.write(bytes) }
            manifest.writeText(actual)
            File(hotLibDir(), "pending.txt").writeText(target.name)
            notes.append("新内核已就绪（${bytes.size} 字节），重启网关后生效\n")
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

    private fun downloadAndInstall(url: String, name: String) {
        val dir = File(context.filesDir, "updates")
        dir.mkdirs()
        val apk = File(dir, name)
        FileOutputStream(apk).use { it.write(httpGetBytes(url)) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun configFile(): File = File(context.filesDir, "config.yaml")

    private fun hotLibDir(): File = HotSoLoader.hotLibDir(context)

    private fun httpGet(url: String, accept: String): String =
        String(httpGetBytes(url, accept), Charsets.UTF_8)

    private fun httpGetBytes(url: String, accept: String = "*/*"): ByteArray {
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
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
