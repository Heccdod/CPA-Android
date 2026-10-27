package cliproxymobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestEnsureCatalogKeyPreservesConfig pins the bug that wiped a live config: passing the
// DocumentNode to setPath replaced the whole tree, so the engine came up on ":0" with no
// server.host and no api-keys. Every other key must survive the patch.
func TestEnsureCatalogKeyPreservesConfig(t *testing.T) {
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
		"oauth:\n" +
		"  auth-dir: /tmp/auths\n"
	if err := os.WriteFile(cfg, []byte(original), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}

	catalog := filepath.Join(dir, modelsFileName)
	if err := migrateExistingConfig(cfg, catalog); err != nil {
		t.Fatalf("migrateExistingConfig: %v", err)
	}

	out, err := os.ReadFile(cfg)
	if err != nil {
		t.Fatalf("read config: %v", err)
	}
	text := string(out)
	for _, want := range []string{
		"config-version: 8",
		"host: 127.0.0.1",
		"port: 8317",
		"my-key",
		"/tmp/auths",
		catalog,
	} {
		if !strings.Contains(text, want) {
			t.Fatalf("config lost %q after patch:\n%s", want, text)
		}
	}
}

// TestEnsureCatalogKeyIdempotent checks the second call is a no-op rather than a rewrite.
func TestEnsureCatalogKeyIdempotent(t *testing.T) {
	dir := t.TempDir()
	cfg := filepath.Join(dir, ConfigFileName)
	original := "config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n"
	if err := os.WriteFile(cfg, []byte(original), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	catalog := filepath.Join(dir, modelsFileName)

	if err := migrateExistingConfig(cfg, catalog); err != nil {
		t.Fatalf("first patch: %v", err)
	}
	first, _ := os.ReadFile(cfg)
	if err := migrateExistingConfig(cfg, catalog); err != nil {
		t.Fatalf("second patch: %v", err)
	}
	second, _ := os.ReadFile(cfg)
	if string(first) != string(second) {
		t.Fatalf("second patch rewrote the file:\nfirst:\n%s\nsecond:\n%s", first, second)
	}
	if !strings.Contains(string(second), "host: 127.0.0.1") {
		t.Fatalf("host lost:\n%s", second)
	}
}
