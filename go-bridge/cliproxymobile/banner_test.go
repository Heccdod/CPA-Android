package cliproxymobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

// TestMigrateExistingConfigKeepsOneBanner pins the bug where every migration prepended the
// head comment block again: yaml.v3 already preserves the document head comment, so the
// prepend doubled it, and a config migrated four times carried four banners.
func TestMigrateExistingConfigKeepsOneBanner(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	banner := "# CPA-Android default configuration (CLIProxyAPI v8 layout).\n" +
		"# Hardened defaults: nothing here.\n"
	original := banner + "config-version: 8\nserver:\n    host: 127.0.0.1\n    port: 8317\n"
	if err := os.WriteFile(cfg, []byte(original), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	// Migrate repeatedly: the first call adds models.catalog, which is what used to append a
	// second copy, and any later call must stay a no-op.
	for i := 0; i < 4; i++ {
		if err := migrateExistingConfig(cfg, filepath.Join(dir, modelsFileName)); err != nil {
			t.Fatalf("migrate %d: %v", i, err)
		}
	}
	raw, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if got := strings.Count(string(raw), "CPA-Android default configuration"); got != 1 {
		t.Fatalf("banner copied %d times, want 1:\n%s", got, raw)
	}
}

// TestCollapseDuplicateHeadComments covers the repair path for configs already carrying
// several copies, and checks that distinct user comments survive.
func TestCollapseDuplicateHeadComments(t *testing.T) {
	banner := "# banner line one\n# banner line two\n"
	in := banner + banner + banner + "# a comment the user wrote\nconfig-version: 8\n"
	out := string(collapseDuplicateHeadComments([]byte(in)))
	if got := strings.Count(out, "banner line one"); got != 1 {
		t.Fatalf("banner copied %d times, want 1:\n%s", got, out)
	}
	if !strings.Contains(out, "a comment the user wrote") {
		t.Fatalf("user comment was dropped:\n%s", out)
	}
}

// TestCollapseKeepsDocumentParseable is the regression for the repair itself: joining the
// trimmed comment block back onto the body without its newline glued "…idle timers." to
// "config-version: 8", which turned the first key into a comment. A count-only assertion on
// the banner passed while the document lost a key, so this one parses the result.
func TestCollapseKeepsDocumentParseable(t *testing.T) {
	banner := "# line one\n# line two\n"
	in := banner + banner + "config-version: 8\nserver:\n    host: 127.0.0.1\n"
	out := collapseDuplicateHeadComments([]byte(in))

	var parsed map[string]any
	if err := yaml.Unmarshal(out, &parsed); err != nil {
		t.Fatalf("collapsed document no longer parses: %v\n%s", err, out)
	}
	if _, ok := parsed["config-version"]; !ok {
		t.Fatalf("config-version was swallowed by the comment block:\n%s", out)
	}
	if _, ok := parsed["server"]; !ok {
		t.Fatalf("server section was swallowed by the comment block:\n%s", out)
	}
	// The banner must still be a comment block, not merged into the key line.
	if !strings.Contains(string(out), "# line two\nconfig-version:") {
		t.Fatalf("comment block lost its terminating newline:\n%s", out)
	}
}

// TestMigrateRestoresMissingConfigVersion pins the self-heal for a document that lost its
// schema selector. Without config-version the engine's v8 validation no longer recognises the
// file, and every other key can be perfectly valid.
func TestMigrateRestoresMissingConfigVersion(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	catalog := filepath.Join(dir, modelsFileName)
	// No config-version at all, and nothing else to fix.
	original := "server:\n    host: 127.0.0.1\n    port: 8317\nmodels:\n    catalog: " + catalog + "\n"
	if err := os.WriteFile(cfg, []byte(original), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := migrateExistingConfig(cfg, catalog); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	raw, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	var parsed map[string]any
	if err := yaml.Unmarshal(raw, &parsed); err != nil {
		t.Fatalf("config no longer parses: %v\n%s", err, raw)
	}
	if got := parsed["config-version"]; got != 8 {
		t.Fatalf("config-version = %v, want 8:\n%s", got, raw)
	}
	if _, ok := parsed["server"]; !ok {
		t.Fatalf("server section lost during repair:\n%s", raw)
	}
}

// TestMigrateRepairsAlreadyRepeatedBanner covers installs that are already damaged: the
// catalog and the thinking rule are both correct, so nothing else asks for a rewrite, and
// without bannerRepeated the repeats would survive every future launch.
func TestMigrateRepairsAlreadyRepeatedBanner(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	catalog := filepath.Join(dir, modelsFileName)
	banner := "# banner line one\n# banner line two\n"
	// Everything else is already in its final state, including a correct models.catalog.
	original := banner + banner + "config-version: 8\nmodels:\n    catalog: " + catalog + "\n"
	if err := os.WriteFile(cfg, []byte(original), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := migrateExistingConfig(cfg, catalog); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	raw, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if got := strings.Count(string(raw), "banner line one"); got != 1 {
		t.Fatalf("banner copied %d times after repair, want 1:\n%s", got, raw)
	}
}
