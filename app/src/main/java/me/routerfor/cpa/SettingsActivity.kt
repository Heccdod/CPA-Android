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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Every Go call and file read this screen makes runs here.
     *
     * ensureDefaultConfig() parses config.yaml inside Go and writes it back with two fsyncs,
     * providerSummary() reads and unmarshals the same file, and the quota pull is a chain of
     * HTTP requests — all of it used to run on the main thread, which is what made the settings
     * screen freeze for a beat on every save.
     */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Quota pulls get their own thread.
     *
     * One full pull is a 12 s budget per enabled account; sharing [io] with it delayed every
     * settings write behind the whole queue.
     */
    private val quotaIo = Executors.newSingleThreadExecutor()

    /** Set while a background task owns a dialog or a row, so a second tap cannot stack one. */
    private val summaryLoading = AtomicBoolean(false)
    private val quotaPulling = AtomicBoolean(false)
    private val configLoading = AtomicBoolean(false)

    /** The providers row's subtitle, filled in when the background read comes back. */
    private var providerSummaryText: TextView? = null

    /** Last value the Go read produced, so the row has something to show while it refreshes. */
    private var providerSummaryCache: String? = null

    /** The client-key row's subtitle, filled in by the same kind of background read. */
    private var clientKeyText: TextView? = null

    /** Last client-key verdict, so the row is not blank between the bind and the read. */
    private var clientKeySummaryCache: String? = null

    /** True while a rotation is in flight, so a second tap cannot start another one. */
    private val rotating = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this, ThemeManager.colors(this))
        ThemeManager.applySystemBars(this, ThemeManager.colors(this))
        setContentView(buildUi())
        render()
    }

    override fun onDestroy() {
        io.shutdownNow()
        quotaIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Runs [block] on [io] unless the screen is gone.
     *
     * shutdownNow() rejects anything submitted afterwards; a rejected execute on the way out
     * would otherwise crash from a callback that no longer matters.
     */
    private fun bg(block: () -> Unit): Boolean {
        if (!alive()) return false
        return try {
            io.execute(block)
            true
        } catch (e: RejectedExecutionException) {
            // The executor was closed between the check above and this call.
            false
        }
    }

    /** Runs [block] on [quotaIo]; same shutdown guard as [bg]. */
    private fun bgQuota(block: () -> Unit): Boolean {
        if (!alive()) return false
        return try {
            quotaIo.execute(block)
            true
        } catch (e: RejectedExecutionException) {
            false
        }
    }

    /** True while the Activity can still host a dialog or a view update. */
    private fun alive(): Boolean = !isFinishing && !isDestroyed

    /**
     * Shows a dialog the answer of a background task built.
     *
     * Two guards, not one: the isFinishing check covers the ordinary "user pressed back while
     * the request was in flight", and the catch covers the window between that check and show()
     * where the window token can still be revoked — BadTokenException is a race by nature.
     */
    private fun showDialogSafely(build: (MaterialAlertDialogBuilder) -> AlertDialog.Builder) {
        if (!alive()) return
        try {
            // The lambda returns the builder it finished configuring (every call site ends on
            // setPositiveButton/setNegativeButton), and the .show() happens here — after the
            // isFinishing check — so a dialog can never be shown on a dead window token.
            //
            // The declared type is AlertDialog.Builder rather than AlertDialog: the button
            // setters are declared on AlertDialog.Builder, and only some of them are overridden
            // with a covariant return in MaterialAlertDialogBuilder. Builder accepts both, so the
            // helper does not depend on which override the library version happens to ship.
            build(MaterialAlertDialogBuilder(ui.dialogContext())).show()
        } catch (e: android.view.WindowManager.BadTokenException) {
            android.util.Log.w("CpaSettings", "dialog dropped: activity window is gone")
        }
    }

    override fun onBackPressed() {
        if (themeChanged) setResult(RESULT_THEME_CHANGED)
        super.onBackPressed()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    companion object {
        const val RESULT_THEME_CHANGED = RESULT_FIRST_USER

        /**
         * The key every install before v2.7 seeded, and the one this repository publishes.
         * Kept here only to recognise it; the Go side owns the replacement.
         */
        private const val LEGACY_CLIENT_KEY = "cpa-android-local"
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
            Entry(
                getString(R.string.settings_providers_entry),
                providerSummaryCache ?: getString(R.string.settings_providers_desc),
                onBind = { providerSummaryText = it }
            ) { startActivity(Intent(this, ProvidersActivity::class.java)) },
            Entry(
                getString(R.string.settings_rotate_key),
                clientKeySummaryCache ?: getString(R.string.settings_rotate_key_ok),
                onBind = { clientKeyText = it }
            ) { confirmRotateKey() },
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

        // The provider row and the key row are the only values that need a file read, so they
        // are filled in after the screen is on display instead of holding render() up for a YAML
        // parse. The fields were re-bound by Entry.onBind while the cards above were built, so
        // they point at the rows that are actually attached.
        refreshProviderSummary()
        refreshClientKeySummary()
    }

    /** Rereads the custom-provider summary in the background; one in flight at a time. */
    private fun refreshProviderSummary() {
        if (!summaryLoading.compareAndSet(false, true)) return
        val submitted = bg {
            val summary = providerSummary()
            runOnUiThread {
                summaryLoading.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                providerSummaryCache = summary
                providerSummaryText?.text = summary
            }
        }
        if (!submitted) summaryLoading.set(false)
    }

    override fun onResume() {
        super.onResume()
        // The custom providers are edited on another screen; returning here has to show what
        // that screen wrote, and it must not do it by reading config.yaml on the main thread.
        if (providerSummaryText != null) refreshProviderSummary()
        if (clientKeyText != null) refreshClientKeySummary()
    }

    /**
     * Reads access.api-keys and says whether it is still the value every old install carries.
     *
     * Nothing migrates on its own: the random key is only generated when config.yaml does not
     * exist, and no settings write ever touches that list. An upgrade from v2.0 therefore keeps
     * "cpa-android-local" — a string published in the repository — forever, which is only
     * harmless while the gateway binds loopback. This row is the one place the user can see it
     * and fix it, so it is filled in off the main thread like every other file read here.
     */
    private fun refreshClientKeySummary() {
        val submitted = bg {
            val key = WebBridgeCompat.clientApiKey(filesDir.absolutePath)
            val legacy = key.isEmpty() || key == LEGACY_CLIENT_KEY
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                clientKeySummaryCache = if (legacy) {
                    getString(R.string.settings_rotate_key_desc)
                } else {
                    key
                }
                clientKeyText?.text = clientKeySummaryCache
            }
        }
        if (!submitted) clientKeyText?.text = clientKeySummaryCache
    }

    /** Generates a new key after confirming: every configured client has to be updated. */
    private fun confirmRotateKey() {
        showDialogSafely { builder ->
            builder
                .setTitle(R.string.rotate_key_title)
                .setMessage(R.string.rotate_key_body)
                .setNegativeButton("取消", null)
                .setPositiveButton(R.string.settings_rotate_key) { _, _ -> rotateClientKey() }
        }
    }

    private fun rotateClientKey() {
        if (!rotating.compareAndSet(false, true)) return
        val submitted = bg {
            val error = WebBridgeCompat.rotateClientApiKey(filesDir.absolutePath)
            val key = if (error.isEmpty()) WebBridgeCompat.clientApiKey(filesDir.absolutePath) else ""
            runOnUiThread {
                rotating.set(false)
                if (!alive()) return@runOnUiThread
                if (error.isNotEmpty()) {
                    toast(getString(R.string.rotate_key_failed, error))
                } else {
                    toast(getString(R.string.rotate_key_done, key.ifEmpty { "—" }))
                }
                render()
            }
        }
        if (!submitted) rotating.set(false)
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

    /**
     * A card whose value is chosen from a fixed list: tapping the row opens a single-choice
     * dialog and the current option is shown underneath the title.
     *
     * Used instead of a switch when the honest answer is not yes/no. The theme picker builds its
     * rows inline because each option needs an icon; this one is for plain text options.
     */
    private fun buildChoiceCard(
        title: String,
        summary: String,
        options: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit
    ): View {
        val card = ui.card(bottomMargin = 6)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            setOnClickListener {
                val labels = options.map { it.second }.toTypedArray()
                val index = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(ui.dialogContext())
                    .setTitle(title)
                    .setSingleChoiceItems(labels, index) { dialog, which ->
                        dialog.dismiss()
                        onPick(options[which].first)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        })
        labels.addView(TextView(this).apply {
            text = summary
            textSize = 12f
            setTextColor(ui.colors.textSecondary)
            setPadding(0, ui.dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(ui.colors.textTertiary)
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
            if (on) pullQuotaForNotification() else QuotaNotifier.clear(this)
        }

    /**
     * Collects every enabled account's quota for the notification body.
     *
     * Rate-limited by [quotaPulling] — flipping the switch off and on again used to start a
     * second full pull while the first was still running, and the two then raced to write
     * QuotaNotifier's cached snapshot. The pull is skipped entirely when the gateway is not
     * serving: every request would fail, and the result would overwrite a good snapshot with
     * error lines.
     */
    private fun pullQuotaForNotification() {
        if (!quotaPulling.compareAndSet(false, true)) return
        // On quotaIo, not io. A full pull is one 12 s budget per enabled account, and sharing
        // the single settings thread with it meant a proxy/word/thinking change saved after it
        // sat in the queue for minutes with no feedback — users tapped again, thinking it failed.
        val submitted = bgQuota {
            val entries = Credentials.list()
            val results = LinkedHashMap<String, Quota.Result>()
            val serving = WebBridgeCompat.isRunning()
            for (entry in entries) {
                if (entry.disabled) continue
                results[entry.name] = if (serving) {
                    Quota.fetch(entry)
                } else {
                    Quota.Result(error = "网关未运行")
                }
            }
            runOnUiThread {
                quotaPulling.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                QuotaNotifier.publish(this, entries, results)
            }
        }
        if (!submitted) quotaPulling.set(false)
    }

    /**
     * How much of the token budget the model may spend thinking before it answers.
     *
     * Three options rather than an on/off switch, because the measured behaviour does not split
     * that way: visible reasoning only appears at medium effort and above, and those settings
     * consume the whole budget of a short-token client. The dialog spells out the numbers so the
     * choice is not a guess.
     */
    private fun buildThinkingCard(): View {
        return buildChoiceCard(
            getString(R.string.settings_thinking),
            AppPrefs.thinkingSummary(this),
            listOf(
                AppPrefs.THINKING_COMPACT to getString(R.string.settings_thinking_compact),
                AppPrefs.THINKING_VISIBLE to getString(R.string.settings_thinking_visible),
                AppPrefs.THINKING_OFF to getString(R.string.settings_thinking_off)
            ),
            AppPrefs.thinkingMode(this)
        ) { mode ->
            AppPrefs.setThinkingMode(this, mode)
            applyQuickSettings()
        }
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

    /**
     * One row in a settings card.
     *
     * [onBind] hands the subtitle TextView back to the caller so a value that has to be read
     * off the main thread (providerSummary reads config.yaml through Go) can be filled in when
     * it arrives instead of blocking render().
     */
    private class Entry(
        val title: String,
        val subtitle: String,
        val onBind: ((TextView) -> Unit)? = null,
        val onClick: () -> Unit
    )

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
                entry.onBind?.invoke(this)
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
        showDialogSafely { builder ->
            builder
                .setTitle(R.string.settings_proxy)
                .setView(wrap(input))
                .setPositiveButton("保存") { _, _ ->
                    val proxy = input.text.toString().trim()
                    AppPrefs.get(this).edit().putString(AppPrefs.KEY_PROXY, proxy).apply()
                    applyQuickSettings()
                }
                .setNegativeButton("取消", null)
        }
    }

    private fun editWords() {
        val input = EditText(this).apply {
            hint = getString(R.string.settings_words_hint)
            setText(AppPrefs.sensitiveWords(context))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }
        showDialogSafely { builder ->
            builder
                .setTitle(R.string.settings_words)
                .setView(wrap(input))
                .setPositiveButton("保存") { _, _ ->
                    val words = input.text.toString().trim()
                    AppPrefs.get(this).edit().putString(AppPrefs.KEY_WORDS, words).apply()
                    applyQuickSettings()
                }
                .setNegativeButton("取消", null)
        }
    }

    /**
     * The raw config.yaml editor.
     *
     * Nothing here may touch the file on the main thread. ensureDefaultConfig() parses the YAML
     * and writes it back with two fsyncs, and the editor's body is the whole document — reading
     * it on the same thread blocked the dialog from opening for as long as the disk took. The
     * dialog is therefore only built once the text is in hand.
     */
    private fun editConfig() {
        if (!configLoading.compareAndSet(false, true)) return
        val proxyUrl = AppPrefs.proxyUrl(this)
        val allowLan = AppPrefs.allowLan(this)
        val thinking = AppPrefs.thinkingMode(this)
        val submitted = bg {
            WebBridgeCompat.ensureDefaultConfig(filesDir.absolutePath, proxyUrl, allowLan, thinking)
            val text = runCatching { File(filesDir, "config.yaml").readText() }.getOrDefault("")
            runOnUiThread {
                configLoading.set(false)
                if (!alive()) return@runOnUiThread
                showConfigEditor(text)
            }
        }
        if (!submitted) configLoading.set(false)
    }

    private fun showConfigEditor(text: String) {
        val editor = EditText(this).apply {
            setText(text)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.TOP or Gravity.START
            minLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            typeface = Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(editor) }
        try {
            MaterialAlertDialogBuilder(ui.dialogContext())
                .setTitle("config.yaml")
                .setView(wrap(scroll, height = ui.dp(420)))
                .setPositiveButton("保存并重载") { _, _ -> saveConfig(editor.text.toString()) }
                .setNeutralButton("恢复默认") { _, _ -> resetConfig() }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: android.view.WindowManager.BadTokenException) {
            android.util.Log.w("CpaSettings", "config editor dropped: window is gone")
        }
    }

    /**
     * Writes the edited document from a worker thread.
     *
     * Not File.writeText, and not a Kotlin copy of the Go writer either. Go's writeFileAtomic is
     * the one implementation that validates the YAML, stages the *previous* bytes as .lastgood,
     * truncates the watched inode in place and holds configMu against the other writers. A local
     * reimplementation had already got the backup semantics wrong once (it staged the new bytes,
     * so a crash mid-write "recovered" into a half-written file). Calling across the bridge means
     * there is only ever one opinion about what a safe write looks like.
     */
    private fun saveConfig(raw: String) {
        val dataDir = filesDir.absolutePath
        bg {
            val error = WebBridgeCompat.setConfigText(dataDir, raw)
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (error.isEmpty()) toast("已保存，引擎正在热重载") else toast("保存失败：" + error)
            }
        }
    }

    private fun resetConfig() {
        bg {
            // delete() answers whether it worked. runCatching{}.map{""} threw that answer away,
            // so a delete that failed still reported "已恢复默认配置" while nothing had changed.
            val error = runCatching {
                val file = File(filesDir, "config.yaml")
                if (file.exists() && !file.delete()) {
                    "无法删除 config.yaml（文件被占用），配置没有重置"
                } else {
                    ""
                }
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            val ensure = if (error.isEmpty()) {
                WebBridgeCompat.ensureDefaultConfig(
                    filesDir.absolutePath,
                    AppPrefs.proxyUrl(this),
                    AppPrefs.allowLan(this),
                    AppPrefs.thinkingMode(this)
                )
            } else {
                error
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (ensure.isEmpty()) toast("已恢复默认配置") else toast("恢复失败：" + ensure)
                render()
            }
        }
    }

    /**
     * Pushes the preference-backed settings into config.yaml.
     *
     * updateQuickSettings() is a full parse-marshal-write cycle inside Go with two fsyncs; run
     * from a switch callback on the main thread it stalled the UI on every toggle. The toasts
     * and the re-render happen on the answer, and a second change while one is in flight waits
     * its turn on the same single-threaded executor rather than interleaving two writers.
     */
    private fun applyQuickSettings() {
        val dataDir = filesDir.absolutePath
        val proxyUrl = AppPrefs.proxyUrl(this)
        val allowLan = AppPrefs.allowLan(this)
        val words = AppPrefs.sensitiveWords(this)
        val thinking = AppPrefs.thinkingMode(this)
        bg {
            val err = WebBridgeCompat.updateQuickSettings(dataDir, proxyUrl, allowLan, words, thinking)
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                if (err.isEmpty()) {
                    toast("已保存，引擎正在热重载")
                } else {
                    toast("保存失败：" + err)
                }
                render()
            }
        }
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
        // The answer arrives from UpdateManager's worker thread, long after the user may have
        // left this screen; building the dialog without the guard threw BadTokenException.
        UpdateManager(this).checkForUpdates { message ->
            runOnUiThread {
                showDialogSafely { builder ->
                    builder
                        .setTitle(R.string.settings_check_update)
                        .setMessage(message)
                        .setPositiveButton("好", null)
                }
            }
        }
    }

    private fun syncRules() {
        if (!AppPrefs.ruleSyncEnabled(this)) {
            showDialogSafely { builder ->
                builder
                    .setTitle(R.string.settings_sync_rules)
                    .setMessage("远程规则只会改动模型规则、敏感词、重试与日志设置；监听地址、API 密钥、管理密钥与登录凭据都保持本地不变。确认拉取？")
                    .setPositiveButton("拉取") { _, _ ->
                        AppPrefs.setRuleSyncEnabled(this, true)
                        runRuleSync()
                    }
                    .setNegativeButton("取消", null)
            }
        } else {
            runRuleSync()
        }
    }

    /**
     * Applies a fetched rule pack through UpdateManager, which hands the text to the Go side
     * ([WebBridgeCompat.applyRulePack]) instead of rewriting config.yaml here: the Go path owns
     * the whitelist of keys a remote file may touch and the in-place write the engine's watcher
     * needs, and a Kotlin write raced the watcher with a half-written document.
     */
    private fun runRuleSync() {
        UpdateManager(this).syncRules { ok, message ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
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
            // Some OEM builds strip the direct request screen; the general list always exists,
            // and if it does not either, the row is informational rather than a crash.
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure { toast("这个系统没有电池优化设置入口") }
        }
    }

    /**
     * What is running, not what has been downloaded.
     *
     * A staged library does nothing until the process restarts — System.load() resolves a name
     * once per process and HotSoLoader latches in Application.onCreate — so "有已下载内核" made
     * people press 重启网关, which cannot work. Splitting loaded from pending says both which
     * kernel is in use and whether a restart would change it.
     */
    private fun describeHotState(): String {
        val enabled = AppPrefs.hotUpdateEnabled(this)
        val loaded = HotSoLoader.loadedKernel(this)
        return when {
            !enabled -> getString(R.string.hot_off)
            HotSoLoader.restartPending(this) -> "已开启 · 有新内核，需完全退出 App 后生效"
            loaded.isEmpty() || loaded == "packaged" -> "已开启 · 当前用内置内核"
            else -> "已开启 · 当前运行 " + loaded
        }
    }

    /**
     * One line for the custom-provider row.
     *
     * Read from the bridge rather than from preferences: the list lives in config.yaml, which the
     * user can also edit by hand, so the summary has to reflect the file rather than a cached copy
     * of what this screen last wrote.
     */
    private fun providerSummary(): String {
        val result = CustomProviders.load(filesDir.absolutePath)
        if (result.error != null) return "读取失败：" + result.error
        if (result.providers.isEmpty()) return getString(R.string.settings_providers_desc)
        val models = result.providers.sumOf { it.models.size }
        return result.providers.size.toString() + " 个提供商 · " + models + " 个模型"
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