package cliproxymobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// TestApplyThinkingVisibilityWritesVisibilityOnly pins that the rule sets includeThoughts and
// leaves the effort alone.
//
// The opposite was pinned here once — the pair includeThoughts + thinkingLevel:high — on the
// theory that visibility without effort is dropped by the translator's thinking normalisation.
// Measured A/B on the current upstream shows visibility alone does reach the client, and that
// forcing the level costs more completion tokens for fewer reasoning characters, while also
// overriding an effort the caller explicitly asked for. The assertion is inverted so a future
// build cannot silently reintroduce the hard-coded level.
func TestApplyThinkingVisibilityWritesVisibilityOnly(t *testing.T) {
	var root yaml.Node
	if err := yaml.Unmarshal([]byte("config-version: 8\nserver:\n  host: 127.0.0.1\n"), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	applyThinkingVisibility(root.Content[0], true)

	out, err := yaml.Marshal(&root)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	text := string(out)
	for _, want := range []string{
		"includeThoughts: true",
		"host: 127.0.0.1",
	} {
		if !strings.Contains(text, want) {
			t.Fatalf("missing %q after enabling:\n%s", want, text)
		}
	}
	// Hard-coding an effort overrides whatever the caller asked for, and on a 300-token budget
	// (the common SillyTavern default) it leaves almost nothing for the actual reply.
	if strings.Contains(text, "thinkingLevel") {
		t.Fatalf("rule forces a thinking level the caller did not ask for:\n%s", text)
	}
}

// TestApplyThinkingVisibilityMigratesOldRule covers the upgrade path: a config written by an
// earlier build still carries thinkingLevel, and simply skipping the write would leave the old
// behaviour in place forever on every existing install.
func TestApplyThinkingVisibilityMigratesOldRule(t *testing.T) {
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
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(src), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	applyThinkingVisibility(root.Content[0], true)

	out, err := yaml.Marshal(&root)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	text := string(out)
	if !strings.Contains(text, "includeThoughts: true") {
		t.Fatalf("migration dropped the visibility flag:\n%s", text)
	}
	if strings.Contains(text, "thinkingLevel") {
		t.Fatalf("old hard-coded level survived the migration:\n%s", text)
	}
}

// TestApplyThinkingVisibilityLeavesUserLevelAlone keeps a caller-chosen effort intact: a rule
// that sets a level without asking for thoughts is not ours to rewrite.
func TestApplyThinkingVisibilityLeavesUserLevelAlone(t *testing.T) {
	src := "" +
		"config-version: 8\n" +
		"requests:\n" +
		"  payload:\n" +
		"    override:\n" +
		"      - models:\n" +
		"          - name: gemini-3.8-flash-low\n" +
		"        params:\n" +
		"          generationConfig.thinkingConfig.thinkingLevel: \"low\"\n"
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(src), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	applyThinkingVisibility(root.Content[0], true)

	out, err := yaml.Marshal(&root)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	text := string(out)
	if !strings.Contains(text, "thinkingLevel: \"low\"") {
		t.Fatalf("user's own effort was removed:\n%s", text)
	}
}

// TestApplyThinkingVisibilityRemovesOnlyOwnRule keeps a user's own override intact.
func TestApplyThinkingVisibilityRemovesOnlyOwnRule(t *testing.T) {
	src := "" +
		"config-version: 8\n" +
		"requests:\n" +
		"  payload:\n" +
		"    override:\n" +
		"      - models:\n" +
		"          - name: gemini-*\n" +
		"        params:\n" +
		"          temperature: 0.2\n"
	var root yaml.Node
	if err := yaml.Unmarshal([]byte(src), &root); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	doc := root.Content[0]

	applyThinkingVisibility(doc, true)
	applyThinkingVisibility(doc, false)

	out, err := yaml.Marshal(&root)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	text := string(out)
	if strings.Contains(text, "includeThoughts") {
		t.Fatalf("thinking rule survived disable:\n%s", text)
	}
	if !strings.Contains(text, "temperature") {
		t.Fatalf("user rule was removed:\n%s", text)
	}
}

// TestMigrateExistingConfigDropsThinkingLevel covers the upgrade path end to end on disk: a
// config written by an earlier build starts with the forced level, and the migration that runs
// before every engine start must remove it while keeping the user's own keys.
func TestMigrateExistingConfigDropsThinkingLevel(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	original := "config-version: 8\n" +
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
	if err := migrateExistingConfig(cfg, filepath.Join(dir, modelsFileName)); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	out, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	text := string(out)
	for _, want := range []string{"includeThoughts: true", "my-key", "127.0.0.1"} {
		if !strings.Contains(text, want) {
			t.Fatalf("migration lost %q:\n%s", want, text)
		}
	}
	if strings.Contains(text, "thinkingLevel") {
		t.Fatalf("forced level survived migration:\n%s", text)
	}
}

// TestEnsureCatalogKeySurvivesThinkingPatch guards the combination: patching the catalog and
// then toggling thinking must leave a config the engine can still parse.
func TestEnsureCatalogKeySurvivesThinkingPatch(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	if err := os.WriteFile(cfg, []byte("config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := migrateExistingConfig(cfg, filepath.Join(dir, modelsFileName)); err != nil {
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
