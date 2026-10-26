package me.routerfor.cpa

/**
 * The providers the kernel can authenticate, in the order the quick-add sheet shows them.
 *
 * The ids are the exact strings /v8/management/oauth/auth-url?provider=... accepts
 * (internal/api/handlers/management/auth_files_v8.go switch on provider). The glyph is the same
 * brand mark the management panel uses for that provider, so the two surfaces agree; the id is
 * what actually starts a login.
 */
enum class Provider(
    val id: String,
    val label: String,
    val glyph: Int,
    /** true when the kernel exposes a browser OAuth flow for it. */
    val hasOauth: Boolean
) {
    ANTIGRAVITY("antigravity", "Antigravity", R.drawable.brand_antigravity, true),
    CLAUDE("claude", "Claude", R.drawable.brand_claude, true),
    CODEX("codex", "Codex", R.drawable.brand_codex, true),
    GEMINI("gemini", "Gemini", R.drawable.brand_gemini, false),
    KIMI("kimi", "Kimi", R.drawable.brand_kimi, true),
    XAI("xai", "xAI", R.drawable.brand_xai, true);

    companion object {
        /** Resolves the credential "type"/"provider" field the kernel writes. */
        fun fromId(id: String?): Provider? =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

        fun labelOf(id: String?): String = fromId(id)?.label ?: (id?.ifBlank { null } ?: "其他")
    }
}