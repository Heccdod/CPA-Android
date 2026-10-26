package me.routerfor.cpa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 首页：状态 + 账号聚合 + 四个同形操作。配置类全部在 [SettingsActivity]，
 * 完整面板在 [PanelActivity]。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ui: UiKit
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var keyText: TextView
    private lateinit var engineText: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var statusCard: MaterialCardView
    private lateinit var accountsCard: MaterialCardView
    private lateinit var accountsBox: LinearLayout
    private lateinit var themeButton: ImageView

    private var retryCount = 0
    private var firstPaint = true
    private var accountsLoading = false
    private var applied: Palette.Resolved? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyChrome()
        setContentView(buildUi())
        startGateway()
    }

    override fun onResume() {
        super.onResume()
        // The palette can be changed from the settings screen; rebuilding is the only honest
        // way to repaint views that were inflated with the old colours.
        if (applied != null && applied != Palette.resolve(ThemeManager.mode(this), this)) {
            recreate()
            return
        }
        refresh(animate = !firstPaint)
        loadAccounts()
    }

    /** "跟随系统" has to react to a live OS switch, not only at launch. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (ThemeManager.mode(this) == Palette.Mode.SYSTEM) recreate()
    }

    private fun applyChrome() {
        val resolved = Palette.resolve(ThemeManager.mode(this), this)
        applied = resolved
        ui = UiKit(this, Palette.load(this, resolved))
        ThemeManager.applySystemBars(this, ui.colors)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.surface)
        }

        val topBar = buildTopBar()
        root.addView(topBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(ui.dp(20), ui.dp(14) + top, ui.dp(10), ui.dp(8))
            insets
        }

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(28))
        }

        statusCard = ui.card()
        statusCard.addView(buildStatusBlock())
        body.addView(statusCard)

        accountsCard = ui.card()
        accountsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountsCard.addView(accountsBox)
        body.addView(accountsCard)

        body.addView(ui.sectionTitle(getString(R.string.section_quick)))
        body.addView(buildActions())

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(14), ui.dp(10), ui.dp(8))
        }
        bar.addView(ui.title(getString(R.string.app_name)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Theme switcher sits to the left of the gear, exactly as asked.
        themeButton = ui.iconButton(themeGlyph(), getString(R.string.action_theme)) { cycleTheme() }
        bar.addView(themeButton)
        bar.addView(ui.iconButton(R.drawable.ic_settings, getString(R.string.action_settings)) { openSettings() })
        return bar
    }

    /** SYSTEM shows whatever the OS is on right now, so the glyph never lies. */
    private fun themeGlyph(): Int = when (ThemeManager.mode(this)) {
        Palette.Mode.DAY -> R.drawable.ic_theme_day
        Palette.Mode.NIGHT -> R.drawable.ic_theme_night
        Palette.Mode.PAPER -> R.drawable.ic_theme_paper
        Palette.Mode.SYSTEM ->
            if (ThemeManager.isDark(this)) R.drawable.ic_theme_night else R.drawable.ic_theme_day
    }

    private fun cycleTheme() {
        val next = ThemeManager.next(ThemeManager.mode(this))
        ThemeManager.setMode(this, next)
        toast(
            when (next) {
                Palette.Mode.DAY -> "日间主题"
                Palette.Mode.NIGHT -> "黑夜主题"
                Palette.Mode.PAPER -> "羊皮纸主题"
                Palette.Mode.SYSTEM -> "跟随系统"
            }
        )
        recreate()
    }

    private fun buildStatusBlock(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(16))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            background = ContextCompat.getDrawable(context, R.drawable.dot_status)
        }
        head.addView(statusDot, LinearLayout.LayoutParams(ui.dp(9), ui.dp(9)))
        statusText = TextView(this).apply {
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        }
        head.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ui.dp(10) })
        box.addView(head)

        addressText = valueLine(box, getString(R.string.label_address))
        keyText = valueLine(box, getString(R.string.label_api_key), clickable = true)
        engineText = TextView(this).apply {
            textSize = 12f
            setTextColor(ui.colors.textSecondary)
            includeFontPadding = false
            setPadding(0, ui.dp(10), 0, 0)
        }
        box.addView(engineText)
        return box
    }

    /**
     * Label on the left, monospace value on the right, both on one baseline.
     *
     * The value used to sit a few pixels lower: the monospace face has different vertical metrics
     * than the proportional label, and includeFontPadding added the label's extra top bearing.
     * Both sides now drop font padding and are centre-aligned in the row.
     */
    private fun valueLine(parent: LinearLayout, label: String, clickable: Boolean = false): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(8), 0, 0)
        }
        val name = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(ui.colors.textSecondary)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            if (clickable) setOnClickListener { copyToClipboard(text.toString()) }
        }
        row.addView(value, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        parent.addView(row)
        return value
    }

    private fun buildActions(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(ui.button(getString(R.string.action_open_panel)) { openPanel() }, ui.rowWeight())
        row1.addView(ui.gap(ui.dp(10)))
        row1.addView(ui.button(getString(R.string.action_quick_add)) { quickAdd() }, ui.rowWeight())
        box.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, 0)
        }
        startStopButton = ui.button(getString(R.string.action_restart)) { toggleGateway() }
        row2.addView(startStopButton, ui.rowWeight())
        row2.addView(ui.gap(ui.dp(10)))
        row2.addView(ui.button(getString(R.string.action_settings)) { openSettings() }, ui.rowWeight())
        box.addView(row2)
        return box
    }

    // ---------------------------------------------------------------- accounts

    private fun loadAccounts() {
        if (accountsLoading) return
        accountsLoading = true
        Thread {
            val entries = Credentials.list()
            runOnUiThread {
                accountsLoading = false
                renderAccounts(entries)
            }
        }.start()
    }

    private fun renderAccounts(entries: List<Credentials.Entry>) {
        accountsBox.removeAllViews()
        accountsBox.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(12))

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = getString(R.string.label_accounts)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (entries.isNotEmpty()) {
            head.addView(TextView(this).apply {
                text = entries.count { !it.disabled }.toString() + "/" + entries.size
                textSize = 13f
                setTextColor(ui.colors.textSecondary)
                includeFontPadding = false
            })
        }
        accountsBox.addView(head)

        if (entries.isEmpty()) {
            accountsBox.addView(TextView(this).apply {
                text = getString(R.string.label_no_accounts)
                textSize = 13f
                setTextColor(ui.colors.textSecondary)
                setPadding(0, ui.dp(8), 0, 0)
            })
            return
        }

        for ((provider, mine) in Credentials.grouped(entries)) {
            accountsBox.addView(dividerLine())
            accountsBox.addView(providerHeader(provider, mine))
            mine.forEach { accountsBox.addView(accountRow(it)) }
        }

        // A provider that exists in the kernel but not in our enum still has to be reachable.
        val known = Provider.entries.map { it.id.lowercase() }.toSet()
        val others = entries.filter { it.provider.lowercase() !in known }
        if (others.isNotEmpty()) {
            accountsBox.addView(dividerLine())
            accountsBox.addView(providerHeader(null, others))
            others.forEach { accountsBox.addView(accountRow(it)) }
        }
    }

    private fun dividerLine(): View = View(this).apply {
        setBackgroundColor(ui.colors.divider)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)).apply {
            topMargin = ui.dp(12)
        }
    }

    private fun providerHeader(provider: Provider?, mine: List<Credentials.Entry>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(12), 0, ui.dp(2))
        }
        row.addView(providerGlyph(provider))
        row.addView(TextView(this).apply {
            text = provider?.label ?: "其他"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = ui.dp(10)
        })

        val switch = UiKit.ClassicSwitch(this, ui.colors).apply {
            isChecked = mine.any { !it.disabled }
            onCheckedChange = { on ->
                Thread {
                    mine.forEach { Credentials.setEnabled(it.name, on) }
                    val fresh = Credentials.list()
                    runOnUiThread { renderAccounts(fresh) }
                }.start()
            }
        }
        row.addView(switch)
        return row
    }

    private fun accountRow(entry: Credentials.Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(34), ui.dp(4), 0, ui.dp(4))
        }
        row.addView(TextView(this).apply {
            text = entry.email
            textSize = 13f
            setTextColor(ui.colors.textSecondary)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(8)
        })

        row.addView(UiKit.ClassicSwitch(this, ui.colors).apply {
            isChecked = !entry.disabled
            onCheckedChange = { on ->
                Thread {
                    Credentials.setEnabled(entry.name, on)
                    val fresh = Credentials.list()
                    runOnUiThread { renderAccounts(fresh) }
                }.start()
            }
        })
        return row
    }

    private fun providerGlyph(provider: Provider?): View {
        val wrapper = FrameLayout(this)
        val bg = ContextCompat.getDrawable(this, R.drawable.bg_provider_chip)?.mutate()
        bg?.setTint(ui.colors.primaryContainer)
        wrapper.background = bg
        wrapper.addView(ImageView(this).apply {
            setImageResource(provider?.glyph ?: R.drawable.ic_provider_more)
            // Brand marks keep their own colours; only the generic fallback follows the palette.
            if (provider == null) setColorFilter(ui.colors.onPrimaryContainer)
            setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5))
        }, FrameLayout.LayoutParams(ui.dp(28), ui.dp(28)))
        wrapper.layoutParams = LinearLayout.LayoutParams(ui.dp(28), ui.dp(28))
        return wrapper
    }

    /**
     * One sheet for every provider: the glyph is the icon, the "…" entry jumps to the panel for
     * anything the kernel exposes that is not in this list.
     */
    private fun quickAdd() {
        if (!WebBridgeCompat.isRunning()) {
            toast(getString(R.string.toast_engine_not_ready))
            return
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(8), 0, 0)
        }
        Provider.entries.forEach { provider ->
            list.addView(providerOption(provider.glyph, provider.label, provider.hasOauth) {
                startOauth(provider)
            })
        }
        list.addView(ui.divider(0))
        list.addView(providerOption(R.drawable.ic_provider_more, getString(R.string.add_more), true) {
            openPanel()
        })

        MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle(R.string.action_quick_add)
            .setView(list)
            .setNegativeButton("取消", null)
            .show()
    }

    private fun providerOption(glyph: Int, label: String, enabled: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            if (enabled) setOnClickListener { onClick() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(glyph)
            if (glyph == R.drawable.ic_provider_more) setColorFilter(ui.colors.textPrimary)
        }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(ui.colors.textPrimary)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = ui.dp(14)
        })
        if (!enabled) {
            row.addView(TextView(this).apply {
                text = getString(R.string.provider_needs_key)
                textSize = 11f
                setTextColor(ui.colors.textTertiary)
            })
        }
        return row
    }

    private fun startOauth(provider: Provider) {
        Thread {
            val url = OauthLauncher.requestAuthUrl(provider.id)
            runOnUiThread {
                if (url == null) {
                    toast(getString(R.string.toast_auth_unavailable))
                } else {
                    val tab = androidx.browser.customtabs.CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    tab.launchUrl(this, android.net.Uri.parse(url))
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- state

    private fun refresh(animate: Boolean = false) {
        val running = WebBridgeCompat.isRunning()
        val error = WebBridgeCompat.lastError()
        val address = WebBridgeCompat.listenAddress()

        statusText.text = when {
            running -> getString(R.string.state_running)
            !error.isNullOrEmpty() -> getString(R.string.state_error)
            else -> getString(R.string.state_starting)
        }
        statusDot.background?.setTint(
            when {
                running -> ui.colors.ok
                !error.isNullOrEmpty() -> ui.colors.err
                else -> ui.colors.warn
            }
        )
        addressText.text = if (running) address else "—"
        keyText.text = WebBridgeCompat.managementKey()
        engineText.text = if (!error.isNullOrEmpty()) {
            "内核异常：" + error
        } else {
            val hot = if (AppPrefs.hotUpdateEnabled(this)) getString(R.string.hot_on) else getString(R.string.hot_off)
            "内核 " + BuildConfig.VERSION_NAME + " · 热更新" + hot
        }
        startStopButton.text = if (running) getString(R.string.action_restart) else getString(R.string.action_start)

        if (animate) playEntrance()
        firstPaint = false

        if (!running && retryCount < MAX_RETRY) {
            retryCount++
            statusCard.postDelayed({ refresh() }, 700L)
        }
    }

    private fun playEntrance() {
        for ((index, view) in listOf(statusCard, accountsCard).withIndex()) {
            view.alpha = 0f
            view.translationY = ui.dp(12).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 40).toLong())
                .setDuration(220)
                .start()
        }
    }

    private fun startGateway() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyForegroundService::class.java).setAction(ProxyForegroundService.ACTION_START)
        )
        statusCard.postDelayed({ refresh() }, 900L)
    }

    private fun toggleGateway() {
        val action = if (WebBridgeCompat.isRunning()) {
            ProxyForegroundService.ACTION_RESTART
        } else {
            ProxyForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyForegroundService::class.java).setAction(action)
        )
        toast(if (action == ProxyForegroundService.ACTION_RESTART) "正在重启网关" else "正在启动网关")
        retryCount = 0
        statusCard.postDelayed({ refresh(animate = true) }, 1200L)
    }

    private fun openPanel() {
        if (!WebBridgeCompat.isRunning()) {
            toast(getString(R.string.toast_engine_not_ready))
            return
        }
        startActivity(Intent(this, PanelActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    private fun copyToClipboard(value: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("CPA API key", value))
        toast(getString(R.string.action_copied))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MAX_RETRY = 30
    }
}