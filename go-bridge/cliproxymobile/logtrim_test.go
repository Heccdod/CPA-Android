package cliproxymobile

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestTrimLogFileKeepsTheTail pins the storage contract: an oversized engine log is cut down to
// the newest half at a line boundary. Without this the log grows forever - every proxied request
// appends a line and nothing ever removes one.
func TestTrimLogFileKeepsTheTail(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cpa-engine.log")

	var buf bytes.Buffer
	line := strings.Repeat("x", 99) + "\n"
	for i := 0; i < 100; i++ {
		buf.WriteString(line)
	}
	buf.WriteString("THE-NEWEST-LINE\n")
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}

	before, _ := os.Stat(path)
	trimLogFile(path, 2000)
	after, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if after.Size() >= before.Size() {
		t.Fatalf("log was not trimmed: %d -> %d bytes", before.Size(), after.Size())
	}
	if after.Size() > 2000 {
		t.Fatalf("trimmed log is still over the cap: %d bytes", after.Size())
	}

	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasSuffix(got, []byte("THE-NEWEST-LINE\n")) {
		t.Fatal("trim dropped the newest line; the tail is what matters")
	}
	// Cutting mid-record would leave a half line at the top, which reads as corruption.
	if !bytes.HasPrefix(got, []byte(strings.Repeat("x", 99))) {
		t.Fatalf("log does not start on a line boundary: %.60q", got)
	}
	for _, ln := range strings.Split(strings.TrimSuffix(string(got), "\n"), "\n") {
		if ln != strings.Repeat("x", 99) && ln != "THE-NEWEST-LINE" {
			t.Fatalf("partial line survived the trim: %.60q", ln)
		}
	}
}

// A log under the cap must be left exactly as it was - trimming is not allowed to rewrite or
// reorder a healthy file.
func TestTrimLogFileLeavesSmallLogsAlone(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "cpa-engine.log")
	original := []byte("one\ntwo\nthree\n")
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	trimLogFile(path, engineLogMaxBytes)
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, original) {
		t.Fatalf("a small log was modified: %q", got)
	}
}

// A missing file is the normal first-start case, not an error.
func TestTrimLogFileIgnoresMissingFile(t *testing.T) {
	trimLogFile(filepath.Join(t.TempDir(), "nope.log"), 1024)
}
