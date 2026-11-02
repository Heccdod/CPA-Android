package me.routerfor.cpa

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible providers the user brings their own key for.
 *
 * The kernel already routes these (api-keys.openai-compatibility in config.yaml), which is what
 * lets one gateway base URL serve Gemini/Claude/Codex accounts *and* DeepSeek, Kimi, OpenRouter or
 * a local llama.cpp at the same time. Editing that block by hand in the config editor is possible
 * but unforgiving — a missing base-url is silently dropped by the loader, and a wrong key name
 * stops the engine from starting — so this screen owns exactly those fields and leaves everything
 * else (headers, priority, retry policy) to the YAML.
 *
 * The Go side does the parsing and validation: it is the only side that can check the result
 * against the kernel's own loader, and it is the side that writes the file atomically so the
 * watcher sees a complete document.
 */
data class CustomModel(
    val name: String,
    /** What clients ask for. Blank means "same as [name]". */
    val alias: String
) {
    val effectiveAlias: String get() = alias.ifBlank { name }
    val label: String get() = if (alias.isBlank() || alias == name) name else name + "  →  " + alias
}

data class CustomProvider(
    val name: String,
    val baseUrl: String,
    val keys: List<String> = emptyList(),
    val models: List<CustomModel> = emptyList(),
    val disabled: Boolean = false
) {
    /** "https://api.deepseek.com/v1 · 2 个模型 · 1 个密钥" — one line for the list row. */
    fun summary(): String = buildString {
        append(baseUrl)
        append(" · ")
        append(models.size)
        append(" 个模型")
        if (keys.isNotEmpty()) {
            append(" · ")
            append(keys.size)
            append(" 个密钥")
        }
        if (disabled) append(" · 已停用")
    }
}

object CustomProviders {

    /**
     * Reads the list through the bridge.
     *
     * A parse failure returns an empty list plus the message, instead of throwing: this runs on a
     * screen that also shows "还没有添加" for a genuinely empty config, and the two must not look
     * the same to the user.
     */
    fun load(dataDir: String): Result {
        val raw = WebBridgeCompat.getCustomProviders(dataDir)
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return Result(emptyList(), "读取失败：" + (it.message ?: "返回值不是 JSON"))
        }
        val error = root.optString("error")
        if (error.isNotEmpty()) return Result(emptyList(), error)
        val array = root.optJSONArray("providers") ?: JSONArray()
        val out = ArrayList<CustomProvider>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(decode(item))
        }
        return Result(out, null)
    }

    private fun decode(item: JSONObject): CustomProvider {
        val keys = ArrayList<String>()
        item.optJSONArray("keys")?.let { array ->
            for (i in 0 until array.length()) {
                val key = array.optString(i).trim()
                if (key.isNotEmpty()) keys.add(key)
            }
        }
        val models = ArrayList<CustomModel>()
        item.optJSONArray("models")?.let { array ->
            for (i in 0 until array.length()) {
                val m = array.optJSONObject(i) ?: continue
                val name = m.optString("name").trim()
                if (name.isNotEmpty()) models.add(CustomModel(name, m.optString("alias").trim()))
            }
        }
        return CustomProvider(
            name = item.optString("name").trim(),
            baseUrl = item.optString("baseUrl").trim(),
            keys = keys,
            models = models,
            disabled = item.optBoolean("disabled", false)
        )
    }

    /** Writes the whole list. Returns "" on success, otherwise the reason. */
    fun save(dataDir: String, providers: List<CustomProvider>): String {
        val array = JSONArray()
        providers.forEach { array.put(encode(it)) }
        val payload = JSONObject().put("providers", array).toString()
        return WebBridgeCompat.setCustomProviders(dataDir, payload)
    }

    private fun encode(p: CustomProvider): JSONObject {
        val keys = JSONArray()
        p.keys.forEach { if (it.isNotBlank()) keys.put(it.trim()) }
        val models = JSONArray()
        p.models.forEach { m ->
            if (m.name.isBlank()) return@forEach
            models.put(
                JSONObject()
                    .put("name", m.name.trim())
                    .put("alias", m.alias.trim().ifBlank { m.name.trim() })
            )
        }
        return JSONObject()
            .put("name", p.name.trim())
            .put("baseUrl", p.baseUrl.trim())
            .put("keys", keys)
            .put("models", models)
            .put("disabled", p.disabled)
    }

    /**
     * Splits the models text box into entries.
     *
     * One per line, either `name` or `name = alias`. The arrow form is accepted too because the
     * list row prints an arrow, and typing back what you just read is the obvious thing to do.
     */
    fun parseModels(text: String): List<CustomModel> =
        text.split("\n").mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = when {
                trimmed.contains("= ") -> trimmed.split("= ", limit = 2)
                trimmed.contains("=") -> trimmed.split("=", limit = 2)
                trimmed.contains("->") -> trimmed.split("->", limit = 2)
                trimmed.contains("→") -> trimmed.split("→", limit = 2)
                else -> listOf(trimmed)
            }
            val name = parts[0].trim()
            val alias = parts.getOrNull(1)?.trim().orEmpty()
            if (name.isEmpty()) null else CustomModel(name, alias)
        }

    fun modelsText(models: List<CustomModel>): String =
        models.joinToString("\n") { m ->
            if (m.alias.isBlank() || m.alias == m.name) m.name else m.name + " = " + m.alias
        }

    /** One key per line, blanks dropped, duplicates collapsed in order. */
    fun parseKeys(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        text.split("\n").forEach { line ->
            val key = line.trim()
            if (key.isNotEmpty()) seen.add(key)
        }
        return seen.toList()
    }

    class Result(val providers: List<CustomProvider>, val error: String?)
}
