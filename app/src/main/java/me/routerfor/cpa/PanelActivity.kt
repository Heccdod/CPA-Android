package me.routerfor.cpa

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar

/**
 * 全屏管理面板。
 *
 * 返回键与左上角返回都直接回到主界面：面板内部是一个 SPA，它自己的历史记录对用户来说是
 * "上一步操作"，不是"上一层页面"，之前把系统返回交给 WebView.goBack() 会让用户连按好几次
 * 才退得出去。
 */
class PanelActivity : AppCompatActivity() {

    private lateinit var ui: UiKit
    private lateinit var webView: PanelWebView
    private lateinit var loading: ProgressBar
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this, ThemeManager.colors(this))
        ThemeManager.applySystemBars(this, ThemeManager.colors(this))
        setContentView(buildUi())
        load()
    }

    /** Always leave the panel; never walk the SPA's internal history. */
    override fun onBackPressed() {
        finish()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.colors.surfaceCard)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.title_panel)
            setTitleTextColor(ui.colors.textPrimary)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressed() }
            setBackgroundColor(ui.colors.surfaceCard)
            // The toolbar otherwise keeps a pressed-state elevation, which read as a shadow that
            // never goes away after tapping the navigation icon.
            elevation = 0f
            isFocusable = false
            isFocusableInTouchMode = false
        }
        toolbar.navigationIcon?.setTint(ui.colors.textPrimary)
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)))
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            insets
        }

        val stage = FrameLayout(this)
        webView = PanelWebView(this)
        stage.addView(webView)

        loading = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ui.colors.brand)
        }
        stage.addView(loading, FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.CENTER))

        hint = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(ui.dp(28), 0, ui.dp(28), 0)
            setTextColor(ui.colors.textSecondary)
            visibility = View.GONE
        }
        stage.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        webView.onPanelReady = {
            loading.visibility = View.GONE
            hint.visibility = View.GONE
            webView.alpha = 0f
            webView.animate().alpha(1f).setDuration(180).start()
        }
        webView.onPanelProblem = { message ->
            loading.visibility = View.GONE
            hint.text = message
            hint.visibility = View.VISIBLE
        }
        return root
    }

    private fun load() {
        if (webView.loadIfNeeded()) return
        loading.visibility = View.VISIBLE
        hint.text = "网关正在启动，稍后自动加载"
        hint.visibility = View.VISIBLE
        webView.postDelayed({ load() }, 600L)
    }
}