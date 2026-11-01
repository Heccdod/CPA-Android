package cliproxymobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// TestFreshInstallMatchesEveryLaterStart pins first start and later starts to one rule.
//
// The shipped template hard-codes an includeThoughts rule (it predates the three modes), and
// ensureDefaultConfig used to write that template and stop. A brand-new install therefore ran
// includeThoughts while the settings screen showed 不思考, and only agreed after a restart. The
// migration now runs on the fresh path too; this test fails if that call is ever dropped again.
func TestFreshInstallMatchesEveryLaterStart(t *testing.T) {
	for _, mode := range []string{ThinkingCompact, ThinkingVisible, ThinkingOff} {
		dir := t.TempDir()
		if msg := EnsureDefaultConfig(dir, "", false, mode); msg != "" {
			t.Fatalf("EnsureDefaultConfig(%s): %s", mode, msg)
		}
		fresh, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
		if err != nil {
			t.Fatalf("read: %v", err)
		}
		// Running the upgrade path on the file it just wrote must be a no-op: if it changes
		// anything, the two paths disagree.
		if err := migrateExistingConfig(filepath.Join(dir, ConfigFileName), catalogPath(dir), mode); err != nil {
			t.Fatalf("re-migrate: %v", err)
		}
		again, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
		if err != nil {
			t.Fatalf("re-read: %v", err)
		}
		if string(fresh) != string(again) {
			t.Fatalf("mode %s: first start and a later start disagree:\n--- fresh ---\n%s\n--- after re-migrate ---\n%s", mode, fresh, again)
		}
	}
}

// TestNewAPIKeyIsUnpredictable guards the LAN-sharing case: with a 0.0.0.0 bind the gateway is
// reachable from the whole network, and a constant published in this repository would let anyone
// on the same Wi-Fi spend the owner's quota.
func TestNewAPIKeyIsUnpredictable(t *testing.T) {
	seen := make(map[string]bool, 64)
	for i := 0; i < 64; i++ {
		key := newAPIKey()
		if key == legacyAPIKey {
			t.Fatal("new install fell back to the published constant")
		}
		if !strings.HasPrefix(key, defaultAPIKeyPrefix) {
			t.Fatalf("key %q does not carry the expected prefix", key)
		}
		// prefix + 8 hex characters
		if got := len(key) - len(defaultAPIKeyPrefix); got != 8 {
			t.Fatalf("key %q has a %d-character suffix, want 8", key, got)
		}
		if seen[key] {
			t.Fatalf("duplicate key %q in 64 draws", key)
		}
		seen[key] = true
	}
}

// renderRule marshals root after applying a mode, so each test asserts on real YAML rather
// than on node contents.
func renderRule(t *testing.T, src, mode string) string {
	t.Helper()
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(src), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	applyThinkingVisibility(root.Content[0], mode)
	out, err := yaml.Marshal(&root)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return string(out)
}

const bareConfig = "config-version: 8\nserver:\n  host: 127.0.0.1\n"

// TestCompactCapsTheThinkingBudget pins the only setting that keeps a short-token client
// usable.
//
// Measured on gemini-3.8-flash-high, same prompt, max_tokens=300: leaving thinking
// unconstrained returned 12-29 characters of reply with finish_reason max_tokens, while
// thinkingBudget:0 returned 167-208 characters and finish_reason stop. Visibility does not
// help here — the model thinks either way, it just sends the thoughts back.
func TestCompactCapsTheThinkingBudget(t *testing.T) {
	text := renderRule(t, bareConfig, ThinkingCompact)
	if !strings.Contains(text, "generationConfig.thinkingConfig.thinkingBudget: 0") {
		t.Fatalf("compact mode does not cap the budget:\n%s", text)
	}
	if strings.Contains(text, "includeThoughts") {
		t.Fatalf("compact mode should not ask for thoughts:\n%s", text)
	}
}

// TestVisibleAsksForThoughtsAtLowEffort pins the visible mode: visibility plus the level that
// measured best under a small budget (low: 195-206 reply chars at max_tokens=300, and no
// thinking tokens at all on a generous budget).
func TestVisibleAsksForThoughtsAtLowEffort(t *testing.T) {
	text := renderRule(t, bareConfig, ThinkingVisible)
	for _, want := range []string{
		"generationConfig.thinkingConfig.includeThoughts: true",
		"generationConfig.thinkingConfig.thinkingLevel: low",
	} {
		if !strings.Contains(text, want) {
			t.Fatalf("visible mode missing %q:\n%s", want, text)
		}
	}
	// medium and high were measured to destroy a 300-token budget (12-19 reply chars), so a
	// default that reaches for them would be a regression.
	for _, bad := range []string{"\"medium\"", "\"high\""} {
		if strings.Contains(text, bad) {
			t.Fatalf("visible mode asks for effort %s, which truncates small budgets:\n%s", bad, text)
		}
	}
}

// TestOffWritesNoRule keeps the third mode honest: it removes the managed rule and adds
// nothing, leaving the model's own default in place.
func TestOffWritesNoRule(t *testing.T) {
	text := renderRule(t, bareConfig, ThinkingOff)
	if strings.Contains(text, "thinkingConfig") {
		t.Fatalf("off mode wrote a thinking rule:\n%s", text)
	}
}

// TestSwitchingModesReplacesTheRuleNotStacksIt is what makes the setting usable more than
// once: every change has to end with exactly one managed rule, or config.yaml grows a rule per
// tap.
func TestSwitchingModesReplacesTheRuleNotStacksIt(t *testing.T) {
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(bareConfig), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	doc := root.Content[0]
	for _, mode := range []string{ThinkingCompact, ThinkingVisible, ThinkingCompact, ThinkingVisible} {
		applyThinkingVisibility(doc, mode)
	}
	out, err := yaml.Marshal(&root)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	text := string(out)
	// Count entries through the parsed document rather than by string matching: yaml.v3 emits
	// the wildcard as name: '*' and any quoting assertion would be testing the emitter, not the
	// rule count.
	var parsed struct {
		Requests struct {
			Payload struct {
				Override []map[string]any `yaml:"override"`
			} `yaml:"payload"`
		} `yaml:"requests"`
	}
	if err := yaml.Unmarshal(out, &parsed); err != nil {
		t.Fatalf("result does not parse: %v\n%s", err, text)
	}
	if got := len(parsed.Requests.Payload.Override); got != 1 {
		t.Fatalf("managed rule stacked %d times, want 1:\n%s", got, text)
	}
	if !strings.Contains(text, "includeThoughts: true") {
		t.Fatalf("last applied mode is missing:\n%s", text)
	}
	if strings.Contains(text, "thinkingBudget") {
		t.Fatalf("a previous mode's key survived:\n%s", text)
	}
}

// TestUserRulesSurviveEveryMode guards the risk of "remove our rule, then write the new one":
// ownership has to be narrow enough that a hand-written override is never treated as ours.
//
// A user rule is anything that pins specific models, or carries a param this app never writes.
func TestUserRulesSurviveEveryMode(t *testing.T) {
	src := "" +
		"config-version: 8\n" +
		"requests:\n" +
		"  payload:\n" +
		"    override:\n" +
		"      - models:\n" +
		"          - name: gemini-*\n" +
		"        params:\n" +
		"          temperature: 0.2\n" +
		"      - models:\n" +
		"          - name: \"*\"\n" +
		"        params:\n" +
		"          generationConfig.thinkingConfig.thinkingLevel: \"low\"\n" +
		"          temperature: 0.9\n"
	for _, mode := range []string{ThinkingCompact, ThinkingVisible, ThinkingOff} {
		text := renderRule(t, src, mode)
		if !strings.Contains(text, "temperature: 0.2") {
			t.Fatalf("mode %s dropped the user's model-specific rule:\n%s", mode, text)
		}
		// The second rule sets a level we manage, but also temperature, which we never write —
		// so it is the user's, not ours.
		if !strings.Contains(text, "temperature: 0.9") {
			t.Fatalf("mode %s dropped a user rule that shares one of our keys:\n%s", mode, text)
		}
	}
}

// TestMigrationReplacesTheOldHardCodedHigh covers the upgrade path: an existing install has the
// old rule (includeThoughts + thinkingLevel high) and, left alone, would keep truncating small
// budgets forever because the config already exists.
func TestMigrationReplacesTheOldHardCodedHigh(t *testing.T) {
	src := "" +
		"config-version: 8\n" +
		"requests:\n" +
		"  payload:\n" +
		"    override:\n" +
		"      - models:\n" +
		"          - name: \"*\"\n" +
		"        params:\n" +
		"          generationConfig.thinkingConfig.includeThoughts: true\n" +
		"          generationConfig.thinkingConfig.thinkingLevel: \"high\"\n"
	text := renderRule(t, src, ThinkingCompact)
	if strings.Contains(text, "\"high\"") {
		t.Fatalf("old hard-coded effort survived:\n%s", text)
	}
	if !strings.Contains(text, "thinkingBudget: 0") {
		t.Fatalf("upgrade did not reach the new default:\n%s", text)
	}
}

// TestNormalizeThinkingModeKeepsOldBooleanValues keeps a stored Boolean meaningful across the
// upgrade: the setting used to be "是否显示思维链", and reading it as an unknown string would
// quietly switch the feature off for anyone who had it on.
func TestNormalizeThinkingModeKeepsOldBooleanValues(t *testing.T) {
	cases := map[string]string{
		"true":    ThinkingVisible,
		"on":      ThinkingVisible,
		"1":       ThinkingVisible,
		"visible": ThinkingVisible,
		"false":   ThinkingOff,
		"0":       ThinkingOff,
		"off":     ThinkingOff,
		"compact": ThinkingCompact,
		"":        ThinkingCompact,
		"junk":    ThinkingCompact,
		"  LOW  ": ThinkingCompact, // not a mode; falls back rather than guessing
	}
	for in, want := range cases {
		if got := normalizeThinkingMode(in); got != want {
			t.Fatalf("normalizeThinkingMode(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestMigrateExistingConfigAppliesStoredMode runs the whole upgrade on disk: the config on the
// phone carries the old rule, and the migration that runs before every start has to leave the
// mode the user chose.
func TestMigrateExistingConfigAppliesStoredMode(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	original := "" +
		"config-version: 8\n" +
		"server:\n" +
		"  host: 127.0.0.1\n" +
		"  port: 8317\n" +
		"access:\n" +
		"  api-keys:\n" +
		"    - my-key\n" +
		"requests:\n" +
		"  payload:\n" +
		"    override:\n" +
		"      - models:\n" +
		"          - name: \"*\"\n" +
		"        params:\n" +
		"          generationConfig.thinkingConfig.includeThoughts: true\n" +
		"          generationConfig.thinkingConfig.thinkingLevel: \"high\"\n"
	if err := os.WriteFile(cfg, []byte(original), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	// The Android host passes the mode the user picked, so the migration has to leave the file
	// matching it — not merely strip the old effort and stop there.
	if err := migrateExistingConfig(cfg, filepath.Join(dir, modelsFileName), ThinkingCompact); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	out, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	text := string(out)
	if strings.Contains(text, "\"high\"") {
		t.Fatalf("forced high survived migration:\n%s", text)
	}
	for _, want := range []string{"my-key", "127.0.0.1", "thinkingBudget: 0"} {
		if !strings.Contains(text, want) {
			t.Fatalf("migration lost %q:\n%s", want, text)
		}
	}
}

// TestEnsureCatalogKeySurvivesThinkingPatch guards the combination: patching the catalog and
// then writing the thinking rule must leave a config the engine can still parse.
func TestEnsureCatalogKeySurvivesThinkingPatch(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	if err := os.WriteFile(cfg, []byte("config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := migrateExistingConfig(cfg, filepath.Join(dir, modelsFileName), ""); err != nil {
		t.Fatalf("catalog: %v", err)
	}
	raw, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var parsed map[string]any
	if err := yaml.Unmarshal(raw, &parsed); err != nil {
		t.Fatalf("config no longer parses: %v\n%s", err, raw)
	}
	if _, ok := parsed["server"]; !ok {
		t.Fatalf("server section lost:\n%s", raw)
	}
}
