package cliproxymobile

import (
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// TestWaitForReadyWaitsForModels is the regression test for the "comes up disconnected" report.
//
// The listener used to be the only readiness signal, but the engine starts listening before it
// has registered any credentials' models: measured on the device, the port answered at t=0 and
// /v1/models only had entries 0.41s later. A client that reconnects in that window gets
// "400 unknown provider for model". This test reproduces exactly that shape — a server that
// answers 200 with an empty data array first, and fills in only after several polls — and it
// fails if waitForReady returns on the empty answer.
func TestWaitForReadyWaitsForModels(t *testing.T) {
	var polls int
	handler := func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		polls++
		w.Header().Set("Content-Type", "application/json")
		// Empty for the first three polls, then two models. The window is short but the gate has
		// to survive it: on the device it was 8 poll intervals long at the same cadence.
		if polls < 4 {
			_, _ = w.Write([]byte(`{"object":"list","data":[]}`))
			return
		}
		_, _ = w.Write([]byte(`{"object":"list","data":[{"id":"a"},{"id":"b"}]}`))
	}
	srv := httptest.NewServer(http.HandlerFunc(handler))
	defer srv.Close()

	// A credential file has to be present, because that is what tells the gate the empty list is
	// "not registered yet" rather than "this install has no providers". The device always has one
	// by the time a client reconnects, which is the case being reproduced.
	dir := t.TempDir()
	if err := writeTestFile(dir+"/cred.json", "{}"); err != nil {
		t.Fatal(err)
	}
	addr := strings.TrimPrefix(srv.URL, "http://")
	start := time.Now()
	err := waitForReady(addr, 5*time.Second, readinessProbe{apiKey: "k", authDir: dir})
	if err != nil {
		t.Fatalf("waitForReady returned %v, want nil once models exist", err)
	}
	if polls < 4 {
		t.Fatalf("returned after %d polls, before the model list was populated", polls)
	}
	if elapsed := time.Since(start); elapsed < 3*probeInterval {
		t.Fatalf("returned after %s, which is too fast to have waited for the empty responses", elapsed)
	}
}

// TestWaitForReadyAcceptsEmptyListWithoutCredentials pins the other half of the contract: a fresh
// install has no credential files, so there is nothing to register and a 200 is the whole
// readiness condition. Requiring entries there would hang every first start.
func TestWaitForReadyAcceptsEmptyListWithoutCredentials(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"object":"list","data":[]}`))
	}))
	defer srv.Close()

	addr := strings.TrimPrefix(srv.URL, "http://")
	start := time.Now()
	// t.TempDir() is empty, so hasCredentials is false and the empty list must be accepted.
	err := waitForReady(addr, 3*time.Second, readinessProbe{apiKey: "k", authDir: t.TempDir()})
	if err != nil {
		t.Fatalf("waitForReady returned %v, want nil for a 200 with no credentials configured", err)
	}
	if elapsed := time.Since(start); elapsed > time.Second {
		t.Fatalf("took %s; an empty-list 200 with no credentials should accept immediately", elapsed)
	}
}

// TestWaitForReadyKeepsWaitingWithCredentials is the complement: with a credential file present,
// the same empty 200 is NOT ready, because models are still being registered.
func TestWaitForReadyKeepsWaitingWithCredentials(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"object":"list","data":[]}`))
	}))
	defer srv.Close()

	dir := t.TempDir()
	if err := writeTestFile(dir+"/cred.json", "{}"); err != nil {
		t.Fatal(err)
	}
	addr := strings.TrimPrefix(srv.URL, "http://")
	err := waitForReady(addr, 600*time.Millisecond, readinessProbe{apiKey: "k", authDir: dir})
	if err == nil {
		t.Fatal("waitForReady returned nil while the model list stayed empty with a credential present")
	}
	if !strings.Contains(err.Error(), "no models registered") {
		t.Fatalf("error = %v, want it to name the missing models", err)
	}
}

// TestWaitForReadyFailsWhenThePortNeverOpens keeps a broken start from looking like a slow one.
func TestWaitForReadyFailsWhenThePortNeverOpens(t *testing.T) {
	addr := unusedAddr(t)
	err := waitForReady(addr, 400*time.Millisecond, readinessProbe{apiKey: "k"})
	if err == nil {
		t.Fatal("waitForReady returned nil for an address nothing listens on")
	}
	if _, isDial := err.(*net.OpError); isDial {
		// The dial error is the right answer here: the port never opened.
		return
	}
	if !strings.Contains(strings.ToLower(err.Error()), "connect") &&
		!strings.Contains(strings.ToLower(err.Error()), "refused") &&
		!strings.Contains(strings.ToLower(err.Error()), "timeout") {
		t.Fatalf("error = %v, want a connection failure", err)
	}
}

// TestFirstAPIKey skips blanks so the probe never authenticates with an empty key.
func TestFirstAPIKey(t *testing.T) {
	cases := []struct {
		in   []string
		want string
	}{
		{nil, ""},
		{[]string{}, ""},
		{[]string{"  ", ""}, ""},
		{[]string{"", " real-key "}, "real-key"},
		{[]string{"a", "b"}, "a"},
	}
	for _, c := range cases {
		if got := firstAPIKey(c.in); got != c.want {
			t.Fatalf("firstAPIKey(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func writeTestFile(path, body string) error {
	return writeFileAtomic(path, []byte(body))
}
