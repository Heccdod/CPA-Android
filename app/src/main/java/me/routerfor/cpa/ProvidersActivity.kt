package me.routerfor.cpa

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 自定义模型：管理 api-keys.openai-compatibility 里的分组。
 *
 * 为什么单独一页而不是塞进设置：一个分组要把名称、地址、密钥、模型四样都写对才能用，在设置页
 * 那种一行的条目里放不下，而写错的表现是"引擎起不来"或"模型列表里没有"，都不是用户能自己
 * 看出来的东西。这里把四样分开写、逐项校验，并且只改这几样——分组里别的字段（headers、
 * priority、retry）原样留在 config.yaml 里，因为有人手写过它们。
 */
class ProvidersActivity : AppCompatActivity() {

    private lateinit var ui: UiKit
    private lateinit var body: LinearLayout

    private var providers: List<CustomProvider> = emptyList()
    private var loadError: String? = null

    /**
     * Config reads and writes run here, never on the main thread.
     *
     * Both directions go through Go: loading parses config.yaml with a YAML parser, and saving
     * does read → edit → marshal → an in-place write with two fsyncs. On the main thread that
     * froze the screen every time a provider was toggled, and the freeze grew with the file.
     */
    private val io = Executors.newSingleThreadExecutor()

    /** Set while a save is in flight, so two taps cannot interleave a read-modify-write. */
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this, ThemeManager.colors(this))
        ThemeManager.applySystemBars(this, ThemeManager.colors(this))
        setContentView(buildUi())
        reload()
    }

    override fun onBackPressed() {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** Runs [block] on [io] unless the screen is gone; shutdownNow() rejects late submissions. */
    private fun bg(block: () -> Unit): Boolean {
        if (isFinishing || isDestroyed) return false
        return try {
            io.execute(block)
            true
        } catch (e: RejectedExecutionException) {
            // The executor was closed between the check above and this call.
            false
        }
    }

    /** True while the Activity can still host a dialog. */
    private fun alive(): Boolean = !isFinishing && !isDestroyed

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.surface)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.providers_title)
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

    private fun reload() {
        bg {
            val result = CustomProviders.load(filesDir.absolutePath)
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                providers = result.providers
                loadError = result.error
                render()
            }
        }
    }

    private fun render() {
        body.removeAllViews()

        body.addView(ui.sectionTitle(getString(R.string.settings_providers)))

        val error = loadError
        if (error != null) {
            body.addView(hintCard("读取失败：" + error))
        }

        if (providers.isEmpty() && error == null) {
            body.addView(hintCard(getString(R.string.settings_providers_empty) + " · " +
                getString(R.string.settings_providers_desc)))
        } else {
            providers.forEachIndexed { index, provider ->
                body.addView(providerCard(index, provider))
            }
        }

        body.addView(ui.button(getString(R.string.providers_add), primary = true) {
            edit(null, providers.size)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(6) })
    }

    // The parameter is not called text: inside apply{} the implicit receiver's own text wins
    // name resolution, so "text = text" assigns the view's property to itself and the compiler
    // reports the getter as a val.
    private fun hintCard(message: String): View {
        val card = ui.card()
        card.addView(TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(ui.colors.textSecondary)
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
        })
        return card
    }

    private fun providerCard(index: Int, provider: CustomProvider): View {
        val card = ui.card()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(box)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(18), ui.dp(15), ui.dp(18), ui.dp(15))
            setOnClickListener { edit(provider, index) }
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(TextView(this).apply {
            text = provider.name
            textSize = 15f
            setTextColor(if (provider.disabled) ui.colors.textTertiary else ui.colors.textPrimary)
            includeFontPadding = false
        })
        labels.addView(TextView(this).apply {
            text = provider.summary()
            textSize = 12f
            setTextColor(ui.colors.textSecondary)
            setPadding(0, ui.dp(4), 0, 0)
        })
        // The models themselves are what a client actually has to type, so they are listed
        // rather than counted: "3 个模型" does not tell anyone what to paste into SillyTavern.
        if (provider.models.isNotEmpty()) {
            labels.addView(TextView(this).apply {
                text = provider.models.joinToString("\n") { it.label }
                textSize = 12f
                setTextColor(ui.colors.textTertiary)
                setPadding(0, ui.dp(6), 0, 0)
                typeface = android.graphics.Typeface.MONOSPACE
            })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(14))
        }
        actions.addView(outlined(if (provider.disabled) "启用" else "停用") {
            toggle(provider, index)
        }, ui.rowWeight())
        actions.addView(ui.gap(ui.dp(10)))
        actions.addView(outlined(getString(R.string.providers_delete)) { confirmDelete(provider) }, ui.rowWeight())
        box.addView(actions)
        return card
    }

    private fun outlined(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            cornerRadius = ui.dp(12)
            setTextColor(ui.colors.brand)
            setOnClickListener { onClick() }
        }

    /** Disabling rather than deleting keeps the key and model list for the next time it is needed. */
    private fun toggle(provider: CustomProvider, index: Int) {
        val updated = providers.toMutableList()
        updated[index] = provider.copy(disabled = !provider.disabled)
        commit(updated, "已" + (if (provider.disabled) "启用" else "停用") + " " + provider.name)
    }

    private fun confirmDelete(provider: CustomProvider) {
        MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle(R.string.providers_delete)
            .setMessage(getString(R.string.providers_delete_confirm, provider.name))
            .setPositiveButton(R.string.providers_delete) { _, _ ->
                commit(providers.filter { it.name != provider.name }, "已删除 " + provider.name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * Writes the list, then reloads.
     *
     * The save is a read-modify-write of config.yaml with two fsyncs inside Go; it runs on [io]
     * and a second tap is refused while it is in flight, because two interleaved saves would
     * each write a list built from the same stale snapshot.
     */
    private fun commit(updated: List<CustomProvider>, success: String) {
        if (saving) return
        saving = true
        val submitted = bg {
            val error = CustomProviders.save(filesDir.absolutePath, updated)
            runOnUiThread {
                saving = false
                if (!alive()) return@runOnUiThread
                if (error.isEmpty()) {
                    toast(success)
                } else {
                    // The Go side refuses before writing, so a failure here means the file on
                    // disk is unchanged — showing the reason is enough, no rollback needed.
                    try {
                        MaterialAlertDialogBuilder(ui.dialogContext())
                            .setTitle("保存失败")
                            .setMessage(error)
                            .setPositiveButton(R.string.action_ok, null)
                            .show()
                    } catch (e: android.view.WindowManager.BadTokenException) {
                        android.util.Log.w("CpaProviders", "dialog dropped: window is gone")
                    }
                }
                // Reload from disk rather than from the in-memory list: the Go side may have
                // normalised what it wrote, and the new list is what the engine now serves.
                reload()
            }
        }
        if (!submitted) saving = false
    }

    /**
     * The edit form.
     *
     * [index] is -1 for a new provider. Everything is one dialog rather than a second screen
     * because the four fields only make sense together, and a half-filled provider is exactly
     * what the validation exists to prevent.
     */
    private fun edit(provider: CustomProvider?, index: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }

        val name = field(box, R.string.providers_name, R.string.providers_name_hint, provider?.name.orEmpty(), false)
        val baseUrl = field(
            box, R.string.providers_base_url, R.string.providers_base_url_hint,
            provider?.baseUrl.orEmpty(), false
        )
        val keys = field(
            box, R.string.providers_keys, R.string.providers_keys_hint,
            provider?.keys?.joinToString("\n").orEmpty(), true
        )
        val models = field(
            box, R.string.providers_models, R.string.providers_models_hint,
            provider?.let { CustomProviders.modelsText(it.models) }.orEmpty(), true
        )

        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = MaterialAlertDialogBuilder(ui.dialogContext())
            .setTitle(if (provider == null) R.string.providers_add else R.string.providers_title)
            .setView(scroll)
            .setPositiveButton(R.string.providers_save, null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // Overriding the positive button's listener so a validation failure can keep the dialog
        // open with the user's text intact; the default listener dismisses unconditionally.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val draft = CustomProvider(
                name = name.text.toString().trim(),
                baseUrl = baseUrl.text.toString().trim(),
                keys = CustomProviders.parseKeys(keys.text.toString()),
                models = CustomProviders.parseModels(models.text.toString()),
                disabled = provider?.disabled ?: false
            )
            when {
                draft.name.isEmpty() -> toast(getString(R.string.providers_need_name))
                draft.baseUrl.isEmpty() -> toast(getString(R.string.providers_need_base_url))
                draft.models.isEmpty() -> toast(getString(R.string.providers_need_models))
                else -> {
                    val updated = providers.toMutableList()
                    if (index in updated.indices) updated[index] = draft else updated.add(draft)
                    dialog.dismiss()
                    commit(updated, getString(R.string.providers_saved))
                }
            }
        }
    }

    private fun field(
        parent: LinearLayout,
        labelRes: Int,
        hintRes: Int,
        value: String,
        multiline: Boolean
    ): EditText {
        parent.addView(TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(ui.colors.textSecondary)
            setPadding(0, ui.dp(12), 0, ui.dp(4))
        })
        val input = EditText(this).apply {
            hint = getString(hintRes)
            setText(value)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or
                if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            if (multiline) {
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                setSingleLine()
            }
        }
        parent.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return input
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
