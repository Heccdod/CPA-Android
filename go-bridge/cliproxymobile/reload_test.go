package cliproxymobile

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestConfigWritesReachTheEngine is the regression test for a hot reload that never happened.
//
// writeFileAtomic used to stage a temp file and os.Rename() it over config.yaml. That is atomic,
// but the rename swaps the inode and the engine's fsnotify watch is bound to the inode, so the
// watcher went silent after the first save: measured here, three consecutive renames produced
// zero "config file changed" lines while three in-place writes produced three. Every settings
// change, every custom-provider save, and the migrate-on-start rewrite were all invisible until
// the next restart — including the proxy URL, which is why changing it appeared to do nothing.
func TestConfigWritesReachTheEngine(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy failed: %s", msg)
	}
	defer StopProxy()

	logPath := filepath.Join(dir, "cpa-engine.log")
	// StartProxy returns when the port answers and the model list is populated, which is before
	// the watcher goroutine has registered its fsnotify handles. Writing inside that window
	// silently loses the event even with a correct in-place write, so the test waits for the
	// engine's own "file watcher started" line rather than sleeping a guessed interval.
	waitForLog(t, logPath, "file watcher started", 10*time.Second)
	reloads := func() int {
		raw, err := os.ReadFile(logPath)
		if err != nil {
			return -1
		}
		return strings.Count(string(raw), "config file changed")
	}
	waitForReload := func(before int) bool {
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) {
			if reloads() > before {
				return true
			}
			time.Sleep(50 * time.Millisecond)
		}
		return false
	}

	// Three writes, because the old code also lost the first one when the watcher had not yet
	// registered; a single-check test would pass against it on a slow machine.
	//
	// The content has to differ each time: the watcher drops a save whose SHA-256 matches the
	// config it already applied ("config file content unchanged"), so repeating the same words
	// would test that dedupe instead of the file handle.
	for i := 1; i <= 3; i++ {
		before := reloads()
		words := fmt.Sprintf("one-%d,two-%d", i, i)
		if msg := UpdateQuickSettings(dir, "", false, words, ThinkingCompact); msg != "" {
			t.Fatalf("write %d failed: %s", i, msg)
		}
		if !waitForReload(before) {
			t.Fatalf("write %d never reached the engine's watcher (reloads stayed at %d)"+
				" — the file is no longer the inode the engine watches", i, before)
		}
	}
}

// waitForLog blocks until the file at path contains needle.
func waitForLog(t *testing.T, path, needle string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if raw, err := os.ReadFile(path); err == nil && strings.Contains(string(raw), needle) {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("%q never appeared in %s within %s", needle, filepath.Base(path), timeout)
}

// TestWriteIsRecoverableAfterAnInterruptedWrite: dropping the atomic rename gives up
// all-or-nothing, so an interrupted write has to be recoverable.
//
// The signal is the leftover backup file, not the state of config.yaml: half a YAML document
// still parses, so a truncated file is indistinguishable from a short one by parsing it. A
// completed write always removes its backup, which makes a present backup proof of an
// interrupted write.
func TestWriteIsRecoverableAfterAnInterruptedWrite(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	good, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	// Simulate the interruption: a complete backup, and a live file cut in half.
	if err := os.WriteFile(path+configBackupSuffix, good, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, good[:len(good)/2], 0o600); err != nil {
		t.Fatal(err)
	}
	if !recoverConfigFromBackup(path) {
		t.Fatal("a truncated config next to a good backup was not recovered")
	}
	restored, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(restored) != string(good) {
		t.Fatalf("restored %d bytes, want the %d-byte backup", len(restored), len(good))
	}
	if _, err := os.Stat(path + configBackupSuffix); !os.IsNotExist(err) {
		t.Fatal("the backup should be consumed once it has been restored")
	}

	// An unusable backup must not be restored and must not linger: writing it back would
	// reproduce the corruption it is supposed to repair.
	if err := os.WriteFile(path+configBackupSuffix, []byte("config-version: 8\n@@ not yaml: [\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if recoverConfigFromBackup(path) {
		t.Fatal("an unparsable backup was restored")
	}
	if _, err := os.Stat(path + configBackupSuffix); !os.IsNotExist(err) {
		t.Fatal("an unusable backup should be removed so it cannot be retried forever")
	}
}

// TestSuccessfulWriteLeavesNoBackup: the backup exists only during a write. A normal save must
// not leave one behind, or recoverConfigFromBackup would be inspecting debris on every start.
func TestSuccessfulWriteLeavesNoBackup(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	if err := writeFileAtomic(path, []byte("config-version: 8\n")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path + configBackupSuffix); !os.IsNotExist(err) {
		t.Fatal("a successful write left its backup behind")
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(body), "restore") {
		t.Fatalf("unexpected content: %s", body)
	}
}
