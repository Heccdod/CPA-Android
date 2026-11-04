package me.routerfor.cpa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    private lateinit var accountsHead: LinearLayout
    private lateinit var accountsScroll: MaxHeightScrollView
    private lateinit var accountsBox: LinearLayout
    private lateinit var themeButton: ImageView
    private lateinit var refreshButton: ImageView

    private var retryCount = 0
    private var firstPaint = true
    private var accountsLoading = false
    private var applied: Palette.Resolved? = null

    /**
     * The mode the UI was built for, not just its resolved palette.
     *
     * "Day" and "follow system while the system is light" resolve to the same colours but need
     * different glyphs, so comparing only the resolved palette left the sun icon in place after
     * switching to follow-system on a light phone.
     */
    private var appliedMode: Palette.Mode? = null

    /** Set once the inventory has been read against a live gateway. */
    private var accountsLoaded = false

    /**
     * Bumped in [onDestroy]; a background result carrying an older generation must not touch views.
     *
     * A plain boolean is not enough: the threads below hand their answer back through
     * runOnUiThread, and between the hand-off and the callback the Activity can already be gone.
     */
    private val generation = AtomicInteger(0)

    /**
     * Every Go probe and inventory read runs here.
     *
     * WebBridgeCompat.status() dials the loopback port inside Go, so calling it on the main
     * thread stalls the UI for up to its 150 ms timeout on each repaint — and a start that is in
     * flight repaints thirty times.
     */
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Quota fetches run on their own thread, not on [io].
     *
     * Each account costs up to the 12 s budget inside Quota.fetch, and a five-account refresh is
     * therefore a minute of work. Sharing one single-threaded executor with the status probe put
     * every repaint behind that queue: the STARTING retry loop (30 ticks, 700 ms apart) all
     * arrived after the quota work, so the screen sat on 正在启动 and the restart button did
     * nothing visible until the last provider answered.
     */
    private val quotaIo = Executors.newSingleThreadExecutor()

    /** Last status painted, so onResume never has to probe the engine just to decide. */
    private var gatewayRunning = false

    /** Night bit of the configuration this screen was built for; see onConfigurationChanged. */
    private var nightMode = false

    /**
     * Every delayed repaint runs through here rather than through View.postDelayed.
     *
     * A View keeps its own callback queue but offers no way to clear it wholesale, and this
     * screen queues three different ticks (900 ms after a start, 1200 ms after a toggle, and the
     * retry loop). One Handler means onDestroy can drop all of them in a single call, so no
     * tick survives into a destroyed Activity.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The pending retry tick, kept as a field so a new one can replace it. */
    private val refreshTick = Runnable { refresh() }

    /**
     * The inventory last rendered successfully.
     *
     * Kept so a failed read (a stopped gateway, a request that lost a race with shutdown) cannot
     * blank the provider card — an empty result is indistinguishable from "user deleted every
     * account" at that point, and showing "尚未登录任何账号" was read as data loss.
     */
    private var knownEntries: List<Credentials.Entry> = emptyList()

    /** Quota results keyed by credential file name. */
    // Written from a background thread in fetchQuota() while the main thread renders, so a plain
    // LinkedHashMap could corrupt its buckets or throw ConcurrentModificationException when a
    // global refresh and a per-account refresh overlap. ConcurrentHashMap keeps insertion order
    // out — renderQuotaStates iterates the credential list, not this map, so order never mattered.
    private val quotas = java.util.concurrent.ConcurrentHashMap<String, Quota.Result>()

    /** Accounts whose quota request is in flight, so their row can show a spinner state. */
    private val quotaLoading: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Rebind targets, keyed by credential name, rebuilt on every inventory render. */
    private val quotaHolders = LinkedHashMap<String, QuotaHolder>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyChrome()
        setContentView(buildUi())
        startGateway()
    }

    override fun onResume() {
        super.onResume()
        // The palette can be changed from the settings screen; rebuilding is the only honest
        // way to repaint views that were inflated with the old colours. The mode is compared
        // too, because day and follow-system-light share colours but not the theme glyph.
        val mode = ThemeManager.mode(this)
        if (appliedMode != null && (appliedMode != mode ||
                applied != Palette.resolve(mode, this))
        ) {
            recreate()
            return
        }
        refresh(animate = !firstPaint)
        // Reading the inventory before the gateway answers returns an empty list, which renders
        // as "no accounts logged in". The first read happens in refresh() once it is up; every
        // later resume reads again to pick up accounts added from the panel.
        //
        // Only while it is actually serving: a stopped gateway answers nothing, and reloading
        // there replaced the list with "尚未登录任何账号" — which read as "my accounts are gone".
        //
        // isRunning() dials the loopback port inside Go, so it is answered from the status the
        // background probe already painted rather than probed again here.
        if (accountsLoaded && gatewayRunning) loadAccounts()
    }

    /**
     * "跟随系统" has to react to a live OS switch, not only at launch.
     *
     * Only when the night bit actually changed: recreate() on every configuration change threw
     * away the whole screen (and re-ran startGateway + loadAccounts) when the user merely
     * rotated the phone, which the manifest's configChanges exists to avoid.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        if (night == nightMode) return
        nightMode = night
        if (ThemeManager.mode(this) == Palette.Mode.SYSTEM) recreate()
    }

    /**
     * Drops everything the background threads could still write into.
     *
     * The generation bump is what actually stops them: the runOnUiThread lambda they post
     * compares the counter it captured against this one and returns when they differ, so a
     * quota that lands 20 seconds after the user left cannot touch a detached view.
     */
    override fun onDestroy() {
        generation.incrementAndGet()
        // One call drops every queued tick: the 900/1200 ms refreshes and the STARTING retry loop.
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        quotaIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Runs [block] on [io] unless the screen is already gone.
     *
     * shutdownNow() in onDestroy rejects anything submitted afterwards, and a rejected execute
     * would otherwise surface as a crash from a callback that no longer matters.
     */
    private fun bg(block: () -> Unit): Boolean {
        if (isFinishing || isDestroyed) return false
        return try {
            io.execute(block)
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // The executor was closed between the check above and this call.
            false
        }
    }

    /** Runs [block] on [quotaIo]; same shutdown guard as [bg]. */
    private fun bgQuota(block: () -> Unit): Boolean {
        if (isFinishing || isDestroyed) return false
        return try {
            quotaIo.execute(block)
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    private fun applyChrome() {
        val mode = ThemeManager.mode(this)
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val resolved = Palette.resolve(mode, this)
        appliedMode = mode
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

        // The account card is split into a fixed header and a capped scroll area: the title
        // always says how many accounts there are, while the list inside can grow to any length
        // without pushing the quick actions off screen.
        accountsCard = ui.card()
        accountsHead = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), 0)
        }
        accountsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountsScroll = MaxHeightScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(accountsBox)
        }
        // CardView is a FrameLayout, so a header and a list added side by side would stack on top
        // of each other. One vertical column keeps them in flow.
        val accountsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(accountsHead)
            addView(accountsScroll)
        }
        accountsCard.addView(accountsColumn)
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
        refreshButton = ui.iconButton(R.drawable.ic_refresh, getString(R.string.action_refresh)) {
            refreshAll()
        }
        bar.addView(refreshButton)
        themeButton = ui.iconButton(themeGlyph(), getString(R.string.action_theme)) { cycleTheme() }
        bar.addView(themeButton)
        bar.addView(ui.iconButton(R.drawable.ic_settings, getString(R.string.action_settings)) { openSettings() })
        return bar
    }

    /**
     * The one refresh control: re-reads the inventory, then every account's quota.
     *
     * Everything that was already loading is marked again rather than skipped, so the whole
     * card visibly enters the refreshing state together — the per-account buttons included.
     */
    private fun refreshAll() {
        if (accountsLoading) return
        refreshButton.animate().rotationBy(360f).setDuration(650).start()
        loadAccounts(forceQuota = true)
    }

    /**
     * One glyph per mode, and "跟随系统" gets its own half-and-half icon.
     *
     * It used to borrow the day or night glyph depending on the OS setting, which made
     * "follow system, currently light" indistinguishable from "explicitly day".
     */
    private fun themeGlyph(): Int = when (ThemeManager.mode(this)) {
        Palette.Mode.DAY -> R.drawable.ic_theme_day
        Palette.Mode.NIGHT -> R.drawable.ic_theme_night
        Palette.Mode.PAPER -> R.drawable.ic_theme_paper
        Palette.Mode.SYSTEM -> R.drawable.ic_theme_auto
    }

    /**
     * Cycles the palette. The result is visible in the glyph and in every surface on screen,
     * so no toast: a popup under the button on every tap was pure noise.
     */
    private fun cycleTheme() {
        ThemeManager.setMode(this, ThemeManager.next(ThemeManager.mode(this)))
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
        startStopButton.setOnLongClickListener {
            if (gatewayRunning) {
                confirmStopGateway()
                true
            } else {
                false
            }
        }
        row2.addView(startStopButton, ui.rowWeight())
        row2.addView(ui.gap(ui.dp(10)))
        row2.addView(ui.button(getString(R.string.action_settings)) { openSettings() }, ui.rowWeight())
        box.addView(row2)
        return box
    }

    // ---------------------------------------------------------------- accounts

    /**
     * Reads the inventory, then kicks off quota for every account.
     *
     * [forceQuota] re-fetches quota even when a value is already cached, which is what the top
     * refresh button means; a normal resume only fills in accounts that have no value yet, so
     * returning to the screen does not hammer every provider.
     */
    private fun loadAccounts(forceQuota: Boolean = false) {
        if (accountsLoading) return
        accountsLoading = true
        val mine = generation.get()
        val submitted = bg {
            val entries = Credentials.list()
            runOnUiThread {
                // The read can outlive the screen: the gateway answers in its own time and the
                // user may already have left. Returning here is what keeps the detached view
                // tree untouched.
                if (mine != generation.get()) return@runOnUiThread
                accountsLoading = false
                accountsLoaded = true
                // An empty answer while accounts are already on screen means the read failed (the
                // gateway stopped mid-flight, or answered before it was ready), not that the user
                // removed every account. Keeping the previous list avoids the "accounts vanished"
                // report; a genuine removal still lands because it comes with a non-empty list.
                applyInventory(entries)
                val stale = if (forceQuota) entries else entries.filter { !quotas.containsKey(it.name) }
                if (stale.isNotEmpty()) fetchQuota(stale)
            }
        }
        // Never submitted (the screen is gone): clear the in-flight flag so a later refresh on a
        // recreated Activity is not blocked by it.
        if (!submitted) accountsLoading = false
    }

    /**
     * Fetches quota for the given accounts, publishing each result as it lands.
     *
     * Runs on [io] rather than a per-call thread so a refresh and a stop cannot overlap, and
     * every publication carries the generation it started with: an answer that arrives after
     * onDestroy is dropped instead of being written into removed views.
     */
    private fun fetchQuota(targets: List<Credentials.Entry>) {
        if (targets.isEmpty()) return
        val mine = generation.get()
        targets.forEach { quotaLoading.add(it.name) }
        renderQuotaStates()
        bgQuota {
            for (entry in targets) {
                if (mine != generation.get()) return@bgQuota
                val result = Quota.fetch(entry)
                quotas[entry.name] = result
                quotaLoading.remove(entry.name)
                runOnUiThread { if (mine == generation.get()) renderQuotaStates() }
            }
            // The notification needs the whole inventory, not just the accounts just fetched,
            // so it lists every enabled credential rather than the last refresh subset.
            val all = Credentials.list()
            runOnUiThread {
                if (mine != generation.get()) return@runOnUiThread
                QuotaNotifier.publish(this, all, quotas)
            }
        }
    }

    /**
     * The one place an inventory answer is allowed onto the screen.
     *
     * Both the background reload and the two on/off switches used to call [renderAccounts]
     * directly, and the switches skipped the guard below: Credentials.list() answers with an
     * empty list whenever the read fails (Credentials.kt:47 returns emptyList from runCatching),
     * so flipping a switch while the gateway was restarting wiped the card to "尚未登录任何账号".
     *
     * @return true when the list was accepted.
     */
    private fun applyInventory(entries: List<Credentials.Entry>): Boolean {
        if (entries.isEmpty() && knownEntries.isNotEmpty()) return false
        knownEntries = entries
        renderAccounts(entries)
        return true
    }

    /** Rebinds every account row currently on screen to its latest quota state. */
    private fun renderQuotaStates() {
        for ((name, holder) in quotaHolders) {
            holder.bind(quotas[name], quotaLoading.contains(name))
        }
    }

    private fun renderAccounts(entries: List<Credentials.Entry>) {
        accountsBox.removeAllViews()
        // Row bindings belong to the views just removed; without clearing, a rebind would write
        // into detached views and the freshly built rows would never update.
        quotaHolders.clear()
        accountsBox.setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(12))

        // The title lives outside the scroll area so the account count stays visible however far
        // the list is scrolled.
        accountsHead.removeAllViews()
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
        accountsHead.addView(head)

        if (entries.isEmpty()) {
            accountsBox.addView(TextView(this).apply {
                text = getString(R.string.label_no_accounts)
                textSize = 13f
                setTextColor(ui.colors.textSecondary)
                setPadding(0, ui.dp(8), 0, 0)
            })
            capAccountsHeight()
            return
        }

        // The first group sits directly under the fixed header, so it gets air rather than a rule;
        // a divider there would read as a border under the card title.
        var firstGroup = true
        for ((provider, mine) in Credentials.grouped(entries)) {
            accountsBox.addView(if (firstGroup) verticalGap(10) else dividerLine())
            firstGroup = false
            accountsBox.addView(providerHeader(provider, mine))
            mine.forEach { accountsBox.addView(accountRow(it)) }
        }

        // A provider that exists in the kernel but not in our enum still has to be reachable.
        val known = Provider.entries.map { it.id.lowercase() }.toSet()
        val others = entries.filter { it.provider.lowercase() !in known }
        if (others.isNotEmpty()) {
            accountsBox.addView(if (firstGroup) verticalGap(10) else dividerLine())
            accountsBox.addView(providerHeader(null, others))
            others.forEach { accountsBox.addView(accountRow(it)) }
        }
        capAccountsHeight()
    }

    /**
     * Bounds the account list to a fraction of the window.
     *
     * The card is the only unbounded section on the home screen: every provider and credential
     * adds a row, and four providers were enough to push the quick actions below the fold. Two
     * fifths of the window keeps the card clearly present without letting it swallow the screen —
     * and the cap is recomputed on every render because rotation changes the available height.
     */
    private fun capAccountsHeight() {
        // The window, not the screen: displayMetrics.heightPixels is the whole display, so in
        // split screen or a free-form window 40% of it was larger than the space the app
        // actually had — and the card then pushed the quick actions out of view, the very thing
        // this cap exists to prevent.
        val window = windowHeightPx()
        accountsScroll.maxHeight = (window * ACCOUNT_CARD_SCREEN_FRACTION).toInt()
        // Only scroll when the content really overflows, so a one-account card shows no hint of
        // being scrollable and does not intercept a drag meant for the page.
        accountsScroll.post {
            val content = accountsBox.height
            val visible = accountsScroll.height
            val overflowing = content > visible
            accountsScroll.isVerticalScrollBarEnabled = false
            accountsScroll.isNestedScrollingEnabled = overflowing
            accountsScroll.overScrollMode =
                if (overflowing) View.OVER_SCROLL_IF_CONTENT_SCROLLS else View.OVER_SCROLL_NEVER
            if (!overflowing && accountsScroll.scrollY != 0) accountsScroll.scrollTo(0, 0)
        }
    }

    /**
     * Usable window height in pixels, whatever shape the window currently has.
     *
     * getWindowVisibleDisplayFrame is the one call that answers "how much room does this window
     * actually have": in split screen and free-form windows it returns the app's frame, while
     * displayMetrics returns the whole panel. The display metric stays as the fallback for the
     * moment before the window is laid out.
     */
    private fun windowHeightPx(): Int {
        val frame = android.graphics.Rect()
        @Suppress("DEPRECATION")
        window.decorView.getWindowVisibleDisplayFrame(frame)
        if (frame.height() > 0) return frame.height()
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.heightPixels
    }

    private fun verticalGap(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(height))
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
                val names = mine.map { it.name }
                mutateAccounts { names.forEach { Credentials.setEnabled(it, on) } }
            }
        }
        row.addView(switch)
        return row
    }

    /**
     * Applies a credential change off the main thread, then re-reads the inventory.
     *
     * Shared by both switches so neither can bypass [applyInventory]'s empty-list guard — the
     * bug the direct renderAccounts(fresh) call had.
     */
    private fun mutateAccounts(change: () -> Unit) {
        val mine = generation.get()
        bg {
            change()
            val fresh = Credentials.list()
            runOnUiThread {
                if (mine != generation.get()) return@runOnUiThread
                applyInventory(fresh)
            }
        }
    }

    /**
     * One account: email + its own refresh button + the on/off switch, with the quota meter
     * underneath. The refresh button is per account on purpose — a slow provider must not
     * block the others, and a single account is usually all that needs re-asking.
     */
    private fun accountRow(entry: Credentials.Entry): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(34), ui.dp(6), 0, ui.dp(8))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = entry.email
            textSize = 13f
            setTextColor(ui.colors.textSecondary)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = ui.dp(6)
        })

        // Per-account refresh: spins itself, and only re-asks this one credential.
        val holder = QuotaHolder(ui)
        val spin = ui.iconButton(R.drawable.ic_refresh, getString(R.string.action_refresh), size = 30) {
            if (quotaLoading.contains(entry.name)) return@iconButton
            loadQuota(entry)
        }
        holder.spin = spin
        row.addView(spin)

        row.addView(UiKit.ClassicSwitch(this, ui.colors).apply {
            isChecked = !entry.disabled
            onCheckedChange = { on -> mutateAccounts { Credentials.setEnabled(entry.name, on) } }
        })
        column.addView(row)

        holder.attach(column)
        quotaHolders[entry.name] = holder
        holder.bind(quotas[entry.name], quotaLoading.contains(entry.name))
        return column
    }

    /** Re-asks one account's quota and animates just that account's button. */
    private fun loadQuota(entry: Credentials.Entry) {
        fetchQuota(listOf(entry))
    }

    /**
     * One account's quota: a block per model family, each with a meter and a line per window.
     *
     * Gemini and Claude are separate budgets on antigravity, so they get separate blocks —
     * folding them into one summary would show a single number where the account really has
     * two independent limits. The container is rebuilt on every bind because the number of
     * families is only known once the answer lands.
     *
     * The row's own refresh button is held here so [bind] can spin it while that account is in
     * flight and stop it when the answer arrives.
     */
    private inner class QuotaHolder(private val kit: UiKit) {

        var spin: ImageView? = null

        private val box = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }

        private var attached = false

        /** Adds the block container under an account row. */
        fun attach(parent: LinearLayout) {
            if (attached) return
            attached = true
            parent.addView(box, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = kit.dp(6) })
        }

        fun bind(result: Quota.Result?, loading: Boolean) {
            if (!attached) return
            spin?.let { button ->
                button.animate().cancel()
                button.alpha = if (loading) 0.45f else 1f
                if (loading) button.animate().rotationBy(360f).setDuration(650).start()
            }
            box.removeAllViews()
            when {
                loading -> box.addView(note(getString(R.string.quota_loading)))
                result == null -> box.addView(note(getString(R.string.quota_unknown)))
                result.error != null -> box.addView(note(result.error))
                result.unsupported || !result.hasData -> box.addView(note(getString(R.string.quota_unavailable)))
                else -> {
                    for ((family, windows) in Quota.byFamily(result.windows)) {
                        if (family.isNotEmpty()) {
                            box.addView(TextView(this@MainActivity).apply {
                                text = family
                                textSize = 12f
                                typeface = Typeface.DEFAULT_BOLD
                                setTextColor(kit.colors.textSecondary)
                                includeFontPadding = false
                                setPadding(0, kit.dp(6), 0, kit.dp(2))
                            })
                        }
                        for (w in windows) box.addView(windowRow(w))
                    }
                }
            }
        }

        /** One window inside a family: the meter, then "5 小时额度 余 99% · 10:30 重置". */
        private fun windowRow(w: Quota.Window): View {
            val column = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, kit.dp(3), 0, 0)
            }
            column.addView(UiKit.QuotaBar(this@MainActivity, kit.colors).apply {
                percent = w.usedPercent
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            column.addView(TextView(this@MainActivity).apply {
                text = buildString {
                    append(w.label)
                    w.usedPercent?.let { append(" 余 ").append((100.0 - it).toInt()).append("%") }
                    w.resetLabel?.let { append(" · ").append(it).append(" 重置") }
                }
                textSize = 11f
                setTextColor(kit.colors.textTertiary)
                includeFontPadding = false
                setPadding(0, kit.dp(3), 0, 0)
            })
            return column
        }

        /** A plain caption used for the loading / error / unavailable states. */
        private fun note(text: String?): View = TextView(this@MainActivity).apply {
            this.text = text
            textSize = 11f
            setTextColor(kit.colors.textTertiary)
            includeFontPadding = false
        }
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
        if (!gatewayRunning) {
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
        val mine = generation.get()
        bg {
            val url = OauthLauncher.requestAuthUrl(provider.id)
            runOnUiThread {
                if (mine != generation.get()) return@runOnUiThread
                if (url == null) {
                    toast(getString(R.string.toast_auth_unavailable))
                } else {
                    // Only http(s): the URL comes back from the kernel over loopback, and a
                    // scheme it does not own (intent:, file:) has no business being launched.
                    val uri = android.net.Uri.parse(url)
                    if (uri.scheme != "http" && uri.scheme != "https") {
                        toast(getString(R.string.toast_auth_unavailable))
                        return@runOnUiThread
                    }
                    try {
                        androidx.browser.customtabs.CustomTabsIntent.Builder()
                            .setShowTitle(true)
                            .build()
                            .launchUrl(this, uri)
                    } catch (e: Exception) {
                        // A device with no Custom Tabs host and no browser at all is not exotic
                        // enough to crash on: ACTION_VIEW has no handler and launchUrl throws.
                        toast(getString(R.string.toast_auth_unavailable))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- state

    /**
     * Repaints the status card.
     *
     * The three engine reads happen on [io], never here: status() ends in a loopback dial with
     * a 150 ms timeout inside Go (mobile.go:189), and a start in flight calls this thirty times
     * — on the main thread that is up to four and a half seconds of blocked frames, which is
     * exactly what an ANR is made of. The UI half of this method is [paint].
     */
    private fun refresh(animate: Boolean = false) {
        val mine = generation.get()
        bg {
            // One call, one state. Reading liveness and the error message separately let a
            // repaint straddle a stop and show a state that never existed; the engine answers
            // them under a single lock after a live probe, and it knows whether the user asked
            // for the gateway to run.
            val status = WebBridgeCompat.status()
            val error = WebBridgeCompat.lastError()
            val address = WebBridgeCompat.listenAddress()
            // Read here rather than in paint(): the client key is parsed out of config.yaml by
            // the Go side, which is a file read the main thread must not wait on.
            val key = WebBridgeCompat.clientApiKey(filesDir.absolutePath)
            runOnUiThread {
                if (mine != generation.get()) return@runOnUiThread
                paint(status, error, address, key, animate)
            }
        }
    }

    private fun paint(
        status: String,
        error: String?,
        address: String,
        clientKey: String,
        animate: Boolean
    ) {
        val running = status == GatewayStatus.RUNNING
        gatewayRunning = running

        statusText.text = when (status) {
            GatewayStatus.RUNNING -> getString(R.string.state_running)
            GatewayStatus.ERROR -> getString(R.string.state_error)
            // Deliberately stopped is not "starting": that collapse is what made the screen
            // claim the gateway was coming up forever after a stop from the notification.
            GatewayStatus.STOPPED -> getString(R.string.state_stopped)
            else -> getString(R.string.state_starting)
        }
        statusDot.background?.setTint(
            when (status) {
                GatewayStatus.RUNNING -> ui.colors.ok
                GatewayStatus.ERROR -> ui.colors.err
                GatewayStatus.STOPPED -> ui.colors.textTertiary
                else -> ui.colors.warn
            }
        )
        // The OpenAI-compatible surface lives under /v1, which is what a client has to paste
        // into SillyTavern and friends; showing the bare host:port made everyone add it by hand.
        addressText.text = if (running) address.trimEnd('/') + "/v1" else "—"
        // The client key, not the management key: the panel's secret is only good for the
        // management API, so pasting it into a client and calling /v1 answered 401 every time.
        keyText.text = clientKey.ifEmpty { NO_CLIENT_KEY }
        engineText.text = if (!error.isNullOrEmpty()) {
            "内核异常：" + error
        } else {
            // Read the kernel that actually mapped, not the switch that permits a download:
            // they disagree in exactly the cases that matter. The switch stays ON while a staged
            // library is missing or was rejected at load, so this line kept advertising "热更新
            // 已开启" over a packaged kernel.
            val state = HotSoLoader.hotState(this)
            val kernel = if (state.startsWith("hot:")) {
                getString(R.string.engine_hot, state.removePrefix("hot:"))
            } else {
                getString(R.string.engine_packaged)
            }
            "内核 " + BuildConfig.VERSION_NAME + " · " + kernel
        }
        // Three-way, not two: "running" offers 重启 because that is what a stalled gateway
        // needs, and everything else offers 启动 — including STOPPED, which previously offered
        // 启动 while the label above it said 正在启动.
        startStopButton.text = if (running) {
            getString(R.string.action_restart)
        } else {
            getString(R.string.action_start)
        }

        // Repaint only: the quota snapshot is owned by fetchQuota(), and passing nothing here
        // keeps whatever it last collected instead of blanking the expanded notification.

        // The inventory is read as soon as the gateway answers, and again on any later retry
        // that finds it up after it was down. A stopped gateway keeps whatever account list is
        // already on screen — wiping it made the provider card vanish on stop, which read as
        // "my accounts are gone".
        if (running && !accountsLoaded) loadAccounts()

        if (animate) playEntrance()
        firstPaint = false

        // Only keep polling while a start is genuinely in flight. Retrying against STOPPED
        // burned all 30 attempts in 21 seconds and then sat on a wrong label.
        if (status == GatewayStatus.STARTING && retryCount < MAX_RETRY) {
            retryCount++
            // postDelayed, not a background sleep: the tick lands on the main thread and
            // refreshes off it, and onDestroy drops whatever is still queued.
            mainHandler.removeCallbacks(refreshTick)
            mainHandler.postDelayed(refreshTick, 700L)
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

    /**
     * Asks the service to start the gateway — unless the user stopped it last time.
     *
     * START_STICKY brings the service back after the process is killed, and onCreate used to
     * call startGateway() unconditionally, so a deliberate stop lasted only until the system
     * decided to recreate the service. The persisted intent decides instead.
     */
    private fun startGateway() {
        if (!AppPrefs.gatewayWanted(this)) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyForegroundService::class.java).setAction(ProxyForegroundService.ACTION_START)
        )
        mainHandler.postDelayed({ refresh() }, 900L)
    }

    /**
     * Start or restart from the home screen.
     *
     * Long-press is the stop: a plain tap on a running gateway should restart it (the common
     * recovery action), but until now stopping was reachable only from the notification, so a
     * user who swiped the notification away had no way back. The long-press asks first, because
     * stopping a gateway a client is mid-conversation with is not something to do by accident.
     */
    private fun toggleGateway() {
        // Read from the painted status rather than dialling the engine again: the button label
        // on screen was derived from this same value, so a fresh probe could only disagree.
        val action = if (gatewayRunning) {
            ProxyForegroundService.ACTION_RESTART
        } else {
            ProxyForegroundService.ACTION_START
        }
        // A manual tap is an explicit request, so it also revives a gateway the user had
        // stopped: the intent is recorded here rather than left to the service so that the
        // status the screen reads next is already correct.
        AppPrefs.setGatewayWanted(this, true)
        WebBridgeCompat.setDesiredRunning(true)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ProxyForegroundService::class.java).setAction(action)
        )
        toast(if (action == ProxyForegroundService.ACTION_RESTART) "正在重启网关" else "正在启动网关")
        retryCount = 0
        mainHandler.postDelayed({ refresh(animate = true) }, 1200L)
    }

    /** Long-press on the start/stop button: stop the gateway on purpose. */
    private fun confirmStopGateway() {
        MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle(R.string.stop_confirm_title)
            .setMessage(R.string.stop_confirm_body)
            .setNegativeButton("取消", null)
            .setPositiveButton(R.string.action_stop) { _, _ ->
                AppPrefs.setGatewayWanted(this, false)
                WebBridgeCompat.setDesiredRunning(false)
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ProxyForegroundService::class.java)
                        .setAction(ProxyForegroundService.ACTION_STOP)
                )
                toast(getString(R.string.toast_stopping))
                retryCount = 0
                mainHandler.postDelayed({ refresh(animate = true) }, 900L)
            }
            .show()
    }

    private fun openPanel() {
        // The painted status, not another loopback dial on the main thread.
        if (!gatewayRunning) {
            toast(getString(R.string.toast_engine_not_ready))
            return
        }
        startActivity(Intent(this, PanelActivity::class.java))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    private fun openSettings() {
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    /**
     * Settings reports a palette change through a result rather than relying on onResume
     * comparisons alone: when the new mode resolves to the same colours (day -> follow
     * system on a light phone) nothing else would tell this screen to rebuild.
     */
    @Deprecated("startActivityForResult is fine here; the only data is a single flag")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SETTINGS && resultCode == SettingsActivity.RESULT_THEME_CHANGED) {
            recreate()
        }
    }

    private fun copyToClipboard(value: String) {
        // "未生成" is a placeholder, not a key: copying it silently produced a 401 later. The
        // check is on the text itself rather than a fresh isRunning(), which would dial the
        // loopback port on the main thread.
        if (value.isBlank() || value == NO_CLIENT_KEY) {
            toast(getString(R.string.toast_engine_not_ready))
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("CPA API key", value))
        toast(getString(R.string.action_copied))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MAX_RETRY = 30
        private const val REQ_SETTINGS = 1001

        /** Shown in the API-key row when the gateway has not generated one yet. */
        private const val NO_CLIENT_KEY = "未生成"

        /**
         * How much of the window the account list may occupy.
         *
         * Two fifths is the balance the layout needs: one provider with two accounts fits without
         * scrolling at all, and a long inventory still leaves the status card above and the quick
         * actions below on screen. Measured against the window rather than in dp so it holds on
         * both a 6.7" phone and a tablet.
         */
        private const val ACCOUNT_CARD_SCREEN_FRACTION = 0.40f
    }
}