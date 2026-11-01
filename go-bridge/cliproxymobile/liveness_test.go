package cliproxymobile

import (
	"net"
	"testing"
	"time"
)

// TestIsRunningReflectsThePortNotThePointer is the regression for the "connection dropped and I
// had to restart the gateway" report.
//
// IsRunning used to be "state.service != nil". When the engine died in the background the
// pointer stayed set, so the app kept claiming 运行中 while nothing was listening — and
// StartProxy, which also keyed off that pointer, returned early instead of rebuilding it, so a
// restart could be a no-op. Liveness has to come from the socket.
func TestIsRunningReflectsThePortNotThePointer(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig: %s", msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer func() { _ = StopProxy() }()

	if !IsRunning() {
		t.Fatal("IsRunning() = false right after a successful start")
	}

	// Simulate the engine dying without recordError: the pointer survives, the socket does not.
	state.mu.Lock()
	savedAddr := state.listenAddr
	state.listenAddr = unusedAddr(t)
	state.mu.Unlock()
	defer func() {
		state.mu.Lock()
		state.listenAddr = savedAddr
		state.mu.Unlock()
	}()

	if IsRunning() {
		t.Fatal("IsRunning() = true for an address nothing is listening on")
	}
}

// TestStartProxyRebuildsAfterSilentDeath checks the other half: a stale service pointer must not
// make StartProxy return early, because that is what turned every recovery into a manual restart
// that only worked by luck.
func TestStartProxyRebuildsAfterSilentDeath(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig: %s", msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer func() { _ = StopProxy() }()

	// Point the state at a dead address while keeping the service pointer, which is exactly the
	// state a silently-dead engine leaves behind: the pointer is set, the port answers nothing.
	state.mu.Lock()
	state.listenAddr = unusedAddr(t)
	state.mu.Unlock()

	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy after silent death: %s", msg)
	}
	if !IsRunning() {
		t.Fatal("gateway not serving after the rebuild")
	}
	// The old loop must be gone, not merely unreferenced. Two Service.Run loops in one process
	// collide on gin's global mode flag; that is what the race detector reported here, and it
	// only shows up under -race, so this test asserts the outcome rather than the mechanism: a
	// second rebuild must also succeed and leave exactly one listener.
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("second StartProxy: %s", msg)
	}
	if !IsRunning() {
		t.Fatal("gateway not serving after a second rebuild")
	}
}

// TestStatusSeparatesStoppedFromStarting pins the state machine the home screen reads. Both
// states are "not running, no error", and collapsing them is what made a deliberate stop display
// 正在启动 forever.
func TestStatusSeparatesStoppedFromStarting(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig: %s", msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}

	if got := Status(); got != StatusRunning {
		t.Fatalf("Status() = %q after start, want %q", got, StatusRunning)
	}

	if msg := StopProxy(); msg != "" {
		t.Fatalf("StopProxy: %s", msg)
	}
	if got := Status(); got != StatusStopped {
		t.Fatalf("Status() = %q after a deliberate stop, want %q", got, StatusStopped)
	}
	if GetDesiredRunning() {
		t.Fatal("StopProxy did not clear the intent")
	}

	// A deliberate stop must not leave a failure behind. The engine's Run() returns
	// "context canceled" on the way out, and recording that made a stopped gateway display
	// 异常 with the message "context canceled".
	if err := GetLastError(); err != "" {
		t.Fatalf("a deliberate stop left an error behind: %q", err)
	}

	// Asking for it again must not report "stopped" while the start is in flight.
	SetDesiredRunning(true)
	if got := Status(); got != StatusStarting && got != StatusRunning {
		t.Fatalf("Status() = %q after requesting a start, want %q or %q", got, StatusStarting, StatusRunning)
	}
}

// unusedAddr returns a loopback address nothing is bound to.
func unusedAddr(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	addr := l.Addr().String()
	if errClose := l.Close(); errClose != nil {
		t.Fatalf("close: %v", errClose)
	}
	// Give the kernel a moment to release it; a racing accept would make the test flaky.
	time.Sleep(20 * time.Millisecond)
	return addr
}
