package me.routerfor.cpa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
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
                val tmp = File(target.parentFile, ".config-remote.yaml")
                FileOutputStream(tmp).use { out ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    if (!tmp.renameTo(target)) {
                        return@runCatching "config.yaml 替换失败" to false
                    }
                }
                "规则已更新（${body.length} 字节），引擎正在热重载" to true
            }.getOrElse { e ->
                "规则同步失败：${e.message}" to false
            }
            onDone(result.second, result.first)
        }
    }

    fun checkForUpdates(onDone: (message: String) -> Unit) {
        io.execute {
            val message = runCatching {
                val repo = BuildConfig.UPDATE_REPO
                if (repo.isEmpty()) {
                    return@runCatching "未配置更新仓库"
                }
                val json = httpGet(
                    "https://api.github.com/repos/$repo/releases/latest",
                    accept = "application/vnd.github+json"
                )
                val release = JSONObject(json)
                val tag = release.optString("tag_name")
                val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                if (tag.isNullOrEmpty()) {
                    return@runCatching "远程 Release 没有 tag_name"
                }
                if (tag == current) {
                    return@runCatching "已是最新版本（$current）"
                }
                val assets = release.optJSONArray("assets")
                var soAssetUrl: String? = null
                var soDigest: String? = null
                var apkAssetUrl: String? = null
                var apkName: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        val digest = asset.optString("digest")
                        when {
                            name.endsWith("libgojni.so") -> {
                                soAssetUrl = url
                                soDigest = digest
                            }
                            name.endsWith(".apk") -> {
                                apkAssetUrl = url
                                apkName = name
                            }
                        }
                    }
                }
                val notes = StringBuilder()
                notes.append("远程版本：$tag（本地 $current）\n")
                val hotEnabled = AppPrefs.hotUpdateEnabled(context)
                if (soAssetUrl != null) {
                    if (!hotEnabled) {
                        notes.append("发现新内核，但「内核热更新」已关闭，已跳过\n")
                    } else {
                        val target = File(hotLibDir(), "libgojni-$tag.so")
                        val bytes = httpGetBytes(soAssetUrl)
                        val actual = sha256(bytes)
                        val expected = (soDigest ?: "").removePrefix("sha256:")
                        if (expected.isNotEmpty() && !expected.equals(actual, ignoreCase = true)) {
                            notes.append("内核 SHA-256 不匹配，已丢弃\n")
                        } else {
                            hotLibDir().mkdirs()
                            FileOutputStream(target).use { it.write(bytes) }
                            File(hotLibDir(), "sha256.txt").writeText(actual)
                            File(hotLibDir(), "pending.txt").writeText(target.name)
                            notes.append("新内核已就绪（${bytes.size} 字节），重启网关后生效\n")
                        }
                    }
                }
                if (apkAssetUrl != null && apkName != null) {
                    notes.append("发现安装包：$apkName\n")
                    downloadAndInstall(apkAssetUrl, apkName)
                }
                notes.toString().trim()
            }.getOrElse { e ->
                "检查更新失败：${e.message}"
            }
            onDone(message)
        }
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
