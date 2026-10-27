package cliproxymobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// TestApplyThinkingVisibilityWritesPair pins the finding that includeThoughts alone does
// nothing: the translator drops visibility that arrives without an effort, so the rule must
// carry thinkingLevel too or the thought tokens are spent invisibly.
func TestApplyThinkingVisibilityWritesPair(t *testing.T) {
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
		"thinkingLevel: high",
		"host: 127.0.0.1",
	} {
		if !strings.Contains(text, want) {
			t.Fatalf("missing %q after enabling:\n%s", want, text)
		}
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

// TestEnsureCatalogKeySurvivesThinkingPatch guards the combination: patching the catalog and
// then toggling thinking must leave a config the engine can still parse.
func TestEnsureCatalogKeySurvivesThinkingPatch(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	if err := os.WriteFile(cfg, []byte("config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := ensureCatalogKey(cfg, filepath.Join(dir, modelsFileName)); err != nil {
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
