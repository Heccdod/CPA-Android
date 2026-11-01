package cliproxymobile

import (
	"sync"
	"sync/atomic"
	"testing"
)

// TestConcurrentStartsBuildOneEngine is the regression for the unlocked window in startProxy.
//
// state.mu only guards the pointer fields; building the engine and waiting for the port run with
// it released. Several callers can arrive there at once — the activity, the watchdog and the
// network callback all start the gateway — and without lifecycleMu each builds its own engine. A
// second engine cannot bind the port, so the failure is silent: the extra goroutine and its log
// handle simply linger.
//
// Counting engines is what makes this observable. Asserting only "the port answers" passes either
// way, which is why the first version of this test was worthless — it passed with the lock
// removed.
func TestConcurrentStartsBuildOneEngine(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig: %s", msg)
	}
	defer func() { _ = StopProxy() }()

	atomic.StoreInt64(&builds, 0)

	var wg sync.WaitGroup
	errs := make([]string, 8)
	for i := range errs {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			errs[i] = StartProxy(dir)
		}(i)
	}
	wg.Wait()

	for i, msg := range errs {
		if msg != "" {
			t.Fatalf("concurrent StartProxy %d failed: %s", i, msg)
		}
	}
	if got := atomic.LoadInt64(&builds); got != 1 {
		t.Fatalf("%d engines were built for 8 concurrent starts, want 1", got)
	}
	if !IsRunning() {
		t.Fatal("gateway not serving after concurrent starts")
	}
}
