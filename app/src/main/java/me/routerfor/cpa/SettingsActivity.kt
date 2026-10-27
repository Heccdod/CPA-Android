package me.routerfor.cpa

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 设置页：首页只留状态，配置全在这里。外观、网络、引擎、更新、系统。
 *
 * Theme changes are applied with [setResult] so the caller knows to rebuild: returning from
 * settings used to leave MainActivity showing whatever palette it was created with when the
 * new mode only differed by *mode* (day vs follow-system while the system is light).
 */
class SettingsActivity : AppCompatActivity() {

    private var themeChanged = false

    private lateinit var ui: UiKit
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this, ThemeManager.colors(this))
        ThemeManager.applySystemBars(this, ThemeManager.colors(this))
        setContentView(buildUi())
        render()
    }

    override fun onBackPressed() {
        if (themeChanged) setResult(RESULT_THEME_CHANGED)
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    companion object {
        const val RESULT_THEME_CHANGED = RESULT_FIRST_USER
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.surface)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.title_settings)
            setTitleTextColor(ui.colors.textPrimary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressed() }
            setBackgroundColor(ui.colors.surface)
        }
        toolbar.navigationIcon?.setTint(ui.colors.textPrimary)
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)))
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            insets
        }

        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(28))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        body.removeAllViews()

        body.addView(ui.sectionTitle(getString(R.string.settings_appearance)))
        body.addView(buildThemeCard())

        body.addView(ui.sectionTitle(getString(R.string.settings_network)))
        body.addView(card(listOf(
            Entry(getString(R.string.settings_proxy), AppPrefs.proxyUrl(this).ifEmpty { "直连" }) {
                editProxy()
            }
        )))
        body.addView(buildLanCard())

        body.addView(ui.sectionTitle(getString(R.string.settings_engine)))
        body.addView(card(listOf(
            Entry(getString(R.string.settings_words), summarize(AppPrefs.sensitiveWords(this))) {
                editWords()
            },
            Entry(getString(R.string.settings_config), getString(R.string.settings_config_desc)) {
                editConfig()
            }
        )))
        body.addView(buildThinkingCard())

        body.addView(ui.sectionTitle(getString(R.string.settings_update)))
        body.addView(buildStatusQuotaCard())
        body.addView(buildHotUpdateCard())

        body.addView(ui.sectionTitle(getString(R.string.settings_system)))
        body.addView(card(listOf(
            Entry(getString(R.string.settings_battery), getString(R.string.settings_battery_desc)) {
                requestBattery()
            },
            Entry(getString(R.string.settings_restart), "重新加载内核与配置") {
                restartGateway()
            }
        )))

        body.addView(ui.sectionTitle(getString(R.string.settings_about)))
        body.addView(card(listOf(
            Entry(getString(R.string.settings_version), BuildConfig.VERSION_NAME) {},
            Entry(getString(R.string.settings_abi), Build.SUPPORTED_ABIS.firstOrNull() ?: "—") {},
            Entry(getString(R.string.settings_hot_state), describeHotState()) {}
        ), showChevron = false))
    }

    // ---------------------------------------------------------------- appearance

    private fun buildThemeCard(): View {
        val card = ui.card()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)

        val options = listOf(
            Palette.Mode.SYSTEM to getString(R.string.theme_system),
            Palette.Mode.DAY to getString(R.string.theme_day),
            Palette.Mode.NIGHT to getString(R.string.theme_night),
            Palette.Mode.PAPER to getString(R.string.theme_paper)
        )
        val current = ThemeManager.mode(this)
        options.forEachIndexed { index, (mode, label) ->
            if (index > 0) box.addView(ui.divider(18))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
                setOnClickListener {
                    ThemeManager.setMode(this@SettingsActivity, mode)
                    themeChanged = true
                    recreate()
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(glyphFor(mode))
                setColorFilter(ui.colors.textSecondary)
            }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
            row.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(ui.colors.textPrimary)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = ui.dp(14)
            })
            if (mode == current) {
                row.addView(TextView(this).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(ui.colors.brand)
                })
            }
            box.addView(row)
        }
        return card
    }

    private fun glyphFor(mode: Palette.Mode): Int = when (mode) {
        Palette.Mode.SYSTEM -> R.drawable.ic_theme_auto
        Palette.Mode.DAY -> R.drawable.ic_theme_day
        Palette.Mode.NIGHT -> R.drawable.ic_theme_night
        Palette.Mode.PAPER -> R.drawable.ic_theme_paper
    }

    // ---------------------------------------------------------------- switches

    /** Same classic switch as the home screen: no chevron, no "已关闭" text, just the control. */
    private fun buildSwtichCard(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val card = ui.card(bottomMargin = 6)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        })
        labels.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(ui.colors.textSecondary)
            setPadding(0, ui.dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(12)
        })
        row.addView(UiKit.ClassicSwitch(this, ui.colors).apply {
            isChecked = checked
            onCheckedChange = onChange
        })
        card.addView(row)
        return card
    }

    private fun buildLanCard(): View =
        buildSwtichCard(
            getString(R.string.settings_lan),
            getString(R.string.settings_lan_desc),
            AppPrefs.allowLan(this)
        ) { on ->
            AppPrefs.get(this).edit().putBoolean(AppPrefs.KEY_LAN, on).apply()
            applyQuickSettings()
        }

    /**
     * Opt-in quota block in the expanded notification. Turning it off clears the cached body so
     * the next repaint drops the lines immediately rather than leaving a stale block behind.
     */
    private fun buildStatusQuotaCard(): View =
        buildSwtichCard(
            getString(R.string.settings_status_quota),
            getString(R.string.settings_status_quota_desc),
            AppPrefs.statusBarQuota(this)
        ) { on ->
            AppPrefs.setStatusBarQuota(this, on)
            if (on) {
                Thread {
                    val entries = Credentials.list()
                    val results = LinkedHashMap<String, Quota.Result>()
                    for (entry in entries) {
                        if (entry.disabled) continue
                        results[entry.name] = Quota.fetch(entry)
                    }
                    runOnUiThread { QuotaNotifier.publish(this, entries, results) }
                }.start()
            } else {
                QuotaNotifier.clear(this)
            }
        }

    /**
     * Makes the upstream include the model's reasoning in the response.
     *
     * Implemented by rewriting the requests.payload.override rule, so the behavior is visible
     * in config.yaml and survives an upstream sync. On by default; every request pays for the
     * thought tokens while it is on.
     */
    private fun buildThinkingCard(): View =
        buildSwtichCard(
            getString(R.string.settings_thinking),
            getString(R.string.settings_thinking_desc),
            AppPrefs.thinkingVisible(this)
        ) { on ->
            AppPrefs.setThinkingVisible(this, on)
            applyQuickSettings()
        }

    private fun buildHotUpdateCard(): View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(10))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = getString(R.string.settings_hot_update)
            textSize = 15f
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        })
        labels.addView(TextView(this).apply {
            text = getString(R.string.settings_hot_update_desc)
            textSize = 12f
            setTextColor(ui.colors.textSecondary)
            setPadding(0, ui.dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(12)
        })
        row.addView(UiKit.ClassicSwitch(this, ui.colors).apply {
            isChecked = AppPrefs.hotUpdateEnabled(this@SettingsActivity)
            onCheckedChange = { checked ->
                AppPrefs.setHotUpdateEnabled(this@SettingsActivity, checked)
                if (!checked) {
                    HotSoLoader.clearStaged(this@SettingsActivity)
                    toast("已关闭内核热更新，并清除已下载的内核")
                } else {
                    toast("已开启内核热更新")
                }
                render()
            }
        })
        container.addView(row)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14))
        }
        actions.addView(outlinedButton(getString(R.string.settings_check_update)) { checkUpdates() }, ui.rowWeight())
        actions.addView(ui.gap(ui.dp(10)))
        actions.addView(outlinedButton(getString(R.string.settings_sync_rules)) { syncRules() }, ui.rowWeight())
        container.addView(actions)

        val card = ui.card(bottomMargin = 6)
        card.addView(container)
        return card
    }

    private fun outlinedButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            cornerRadius = ui.dp(12)
            setTextColor(ui.colors.brand)
            setOnClickListener { onClick() }
        }

    // ---------------------------------------------------------------- list rows

    private class Entry(val title: String, val subtitle: String, val onClick: () -> Unit)

    private fun card(entries: List<Entry>, showChevron: Boolean = true): MaterialCardView {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        entries.forEachIndexed { index, entry ->
            if (index > 0) box.addView(ui.divider(18))
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(18), ui.dp(15), ui.dp(18), ui.dp(15))
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = entry.title
                textSize = 15f
                setTextColor(ui.colors.textPrimary)
                includeFontPadding = false
            })
            labels.addView(TextView(this).apply {
                text = entry.subtitle
                textSize = 12f
                setTextColor(ui.colors.textSecondary)
                setPadding(0, ui.dp(3), 0, 0)
            })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (showChevron) {
                row.addView(TextView(this).apply {
                    text = "›"
                    textSize = 20f
                    setTextColor(ui.colors.textSecondary)
                })
            }
            row.setOnClickListener { entry.onClick() }
            box.addView(row)
        }
        val card = ui.card(bottomMargin = 6)
        card.addView(box)
        return card
    }

    // ---------------------------------------------------------------- editors

    private fun editProxy() {
        val input = EditText(this).apply {
            hint = "socks5://127.0.0.1:7890"
            setText(AppPrefs.proxyUrl(context))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle(R.string.settings_proxy)
            .setView(wrap(input))
            .setPositiveButton("保存") { _, _ ->
                val proxy = input.text.toString().trim()
                AppPrefs.get(this).edit().putString(AppPrefs.KEY_PROXY, proxy).apply()
                applyQuickSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editWords() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_words_hint)
            setText(AppPrefs.sensitiveWords(context))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle(R.string.settings_words)
            .setView(wrap(input))
            .setPositiveButton("保存") { _, _ ->
                val words = input.text.toString().trim()
                AppPrefs.get(this).edit().putString(AppPrefs.KEY_WORDS, words).apply()
                applyQuickSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editConfig() {
        val configFile = File(filesDir, "config.yaml")
        WebBridgeCompat.ensureDefaultConfig(filesDir.absolutePath, AppPrefs.proxyUrl(this), AppPrefs.allowLan(this))
        val editor = EditText(this).apply {
            setText(if (configFile.exists()) configFile.readText() else "")
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.TOP or Gravity.START
            minLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(editor) }
        MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle("config.yaml")
            .setView(wrap(scroll, height = ui.dp(420)))
            .setPositiveButton("保存并重载") { _, _ ->
                runCatching { configFile.writeText(editor.text.toString()) }
                    .onSuccess { toast("已保存，引擎正在热重载") }
                    .onFailure { toast("写入失败：" + it.message) }
            }
            .setNeutralButton("恢复默认") { _, _ ->
                runCatching { configFile.delete() }
                WebBridgeCompat.ensureDefaultConfig(
                    filesDir.absolutePath,
                    AppPrefs.proxyUrl(this),
                    AppPrefs.allowLan(this)
                )
                toast("已恢复默认配置")
                render()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyQuickSettings() {
        val err = WebBridgeCompat.updateQuickSettings(
            filesDir.absolutePath,
            AppPrefs.proxyUrl(this),
            AppPrefs.allowLan(this),
            AppPrefs.sensitiveWords(this),
            AppPrefs.thinkingVisible(this)
        )
        if (err.isEmpty()) {
            toast("已保存，引擎正在热重载")
        } else {
            toast("保存失败：" + err)
        }
        render()
    }

    private fun restartGateway() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyForegroundService::class.java).setAction(ProxyForegroundService.ACTION_RESTART)
        )
        toast("正在重启网关")
    }

    private fun checkUpdates() {
        toast("正在检查更新…")
        UpdateManager(this).checkForUpdates { message ->
            runOnUiThread {
                MaterialAlertDialogBuilder(ui.dialogContext())
                    .setTitle(R.string.settings_check_update)
                    .setMessage(message)
                    .setPositiveButton("好", null)
                    .show()
            }
        }
    }

    private fun syncRules() {
        if (!AppPrefs.ruleSyncEnabled(this)) {
            MaterialAlertDialogBuilder(ui.dialogContext())
                .setTitle(R.string.settings_sync_rules)
                .setMessage("远程规则会直接覆盖本地 config.yaml（包括 API 密钥与监听地址）。确认拉取？")
                .setPositiveButton("拉取") { _, _ ->
                    AppPrefs.setRuleSyncEnabled(this, true)
                    runRuleSync()
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            runRuleSync()
        }
    }

    private fun runRuleSync() {
        UpdateManager(this).syncRules { ok, message ->
            runOnUiThread {
                toast(message)
                if (ok) render()
            }
        }
    }

    private fun requestBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("当前系统版本无需设置")
            return
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            toast("已在电池优化白名单中")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + packageName))
            )
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun describeHotState(): String {
        val enabled = AppPrefs.hotUpdateEnabled(this)
        val staged = HotSoLoader.hasStaged(this)
        return when {
            !enabled -> getString(R.string.hot_off)
            staged -> "已开启 · 有已下载内核"
            else -> getString(R.string.hot_on)
        }
    }

    private fun summarize(words: String): String {
        val count = words.split(",").count { it.isNotBlank() }
        return count.toString() + " 个词条"
    }

    private fun wrap(view: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}