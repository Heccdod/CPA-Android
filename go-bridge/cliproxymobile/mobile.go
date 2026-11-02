// Package cliproxymobile exposes the embedded CLIProxyAPI engine to Android through
// gomobile. Every exported symbol uses only primitive types (string / bool) because
// gobind cannot marshal structs, context.Context, errors or multi-value returns.
package cliproxymobile

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/router-for-me/CLIProxyAPI/v8/sdk/cliproxy"
	sdkconfig "github.com/router-for-me/CLIProxyAPI/v8/sdk/config"
	log "github.com/sirupsen/logrus"
	"gopkg.in/yaml.v3"
)

const (
	// ConfigFileName is the file the Android host keeps inside filesDir.
	ConfigFileName = "config.yaml"
	// AuthDirName is the OAuth credential store created next to the config file.
	AuthDirName = "auths"
	// thinkingParamPath is the payload key that makes the upstream return thoughts.
	thinkingParamPath = "generationConfig.thinkingConfig.includeThoughts"
	// thinkingLevelPath and thinkingBudgetPath are the effort knobs. Either one switches the
	// model's thinking from "whatever it feels like" to a fixed amount, and that is the only
	// lever that makes a short-token client work: measured on gemini-3.8-flash-high with the
	// same prompt at max_tokens=300, an unconstrained model spent 340-394 chars of thinking and
	// left 12-29 characters of reply (finish_reason max_tokens), while thinkingBudget:0
	// produced 167-208 characters of reply and no thinking at all. Visibility alone does not
	// help — thinking still happens and still eats the budget.
	thinkingLevelPath  = "generationConfig.thinkingConfig.thinkingLevel"
	thinkingBudgetPath = "generationConfig.thinkingConfig.thinkingBudget"
	// thinkingLevelLow is the level used by the "compact" mode. low measured 195-206 reply
	// chars at max_tokens=300 — the best of the constrained settings — and at a generous
	// budget it is cheap too (121 chars of reply at 1500, no thinking tokens spent).
	thinkingLevelLow = "low"
	// modelsFileName is the corrected model catalog the host copies into filesDir.
	modelsFileName = "models.json"
	// configVersion is the schema version the config file declares. ValidateV8Config keys off
	// it, so a document missing it is not a valid v8 config even if every other key is right.
	configVersion = "8"
	// DefaultPort is the gateway port used by every generated configuration.
	DefaultPort = 8317
	// ManagementKey is the local management secret seeded into the panel.
	ManagementKey = "cpa-android-local"
	// defaultAPIKeyPrefix is the local proxy key accepted by the /v1 endpoints. The full key
	// carries a random suffix, generated when the config is first written: with 0.0.0.0 bind the
	// gateway is reachable from the whole LAN, and a constant published in this repository would
	// let anyone on the same Wi-Fi spend the owner's quota. Loopback-only installs are unaffected
	// either way.
	defaultAPIKeyPrefix = "cpa-android-"
	// legacyAPIKey is the fixed key earlier builds wrote into access.api-keys. It is a literal in
	// this repository, so every install that still carries it is reachable by anyone who reads the
	// source — which matters the moment server.host is 0.0.0.0.
	//
	// Upgrades are NOT converted automatically. Nothing in the settings path ever wrote
	// access.api-keys (an earlier comment here claimed it did; that was wrong), so an installed
	// base that upgraded from those builds still has this value in its config file. The constant
	// survives only as [newAPIKey]'s fallback when crypto/rand is unavailable, and as the value
	// [RotateClientAPIKey] looks for; it is still ACCEPTED by the engine until the user rotates,
	// because rejecting it would silently lock out every client already configured against it.
	legacyAPIKey = "cpa-android-local"

	loopbackHost = "127.0.0.1"
	anyHost      = "0.0.0.0"

	// readyTimeout is how long StartProxy waits for the engine to accept connections before
	// giving up. Three seconds was enough on an idle machine but not on a cold phone start or
	// while a build is running: the bridge test flaked roughly one run in ten, and the app
	// would report a failed start that a retry then fixed. Starting the engine is the slow
	// part; ten seconds costs nothing when it succeeds on the first probe.
	readyTimeout  = 10 * time.Second
	probeInterval = 50 * time.Millisecond
	stopTimeout   = 10 * time.Second

	// livenessTimeout bounds the loopback dial IsRunning uses as its health check. A refused
	// connection fails in microseconds, so this only matters when the kernel is accepting but
	// not answering. The caller is often the UI thread and the target is now always loopback (a
	// local handshake completes in well under a millisecond), so 150ms is generous.
	livenessTimeout = 150 * time.Millisecond
)

// defaultSensitiveWords ship by default. Antigravity intermittently answers with a
// fake 429 when these literals reach the upstream WAF, so the engine obfuscates them
// with zero-width spaces before the request leaves the device.
var defaultSensitiveWords = []string{
	"Claude Agent SDK",
	"RFC 2119",
	"system-conventions",
	"system_conventions",
	"system-directive",
	"system_directive",
	"Hermes Agent",
	"Codex",
}

type runtimeState struct {
	mu         sync.Mutex
	service    *cliproxy.Service
	cancel     context.CancelFunc
	done       chan struct{}
	listenAddr string
	logFile    *os.File
	// generation identifies whose engine is currently installed; see recordError.
	generation uint64
}

var state runtimeState

// lifecycleMu serialises start and stop against each other.
//
// state.mu only guards the pointer fields; the expensive part of a start (building the engine,
// then waiting up to readyTimeout for the port) runs with it released. Without a second lock two
// callers — the activity, the watchdog and the network callback can all trigger a start — both
// see state.service == nil during that window and both start an engine, and the second one wins
// the assignment while the first keeps a goroutine and a log file alive forever.
var lifecycleMu sync.Mutex

// configMu serialises the read-modify-write cycles that edit config.yaml.
//
// Every writer here reads the file, edits the YAML tree, marshals it and writes it back. Two of
// those interleaved lose one edit outright — measured: a provider saved from the settings screen
// disappeared on the very next settings write, because that write had read the file before the
// provider was added and then wrote its own tree back over it. The lock has to cover the whole
// cycle, not just the write, or the loss comes straight back.
var configMu sync.Mutex

// generationCounter hands out the ids that tell one engine apart from the next.
var generationCounter uint64

// builds is test-only instrumentation: it counts how many engines were actually built.
var builds int64
var lastError string

// desiredRunning is the user's intent, guarded by state.mu. It lives outside runtimeState
// because it survives a stop/start cycle and is read by Status even when service is nil.
var desiredRunning bool

// StartProxy boots the embedded CLIProxyAPI engine.
// dataDir is Android  context.filesDir.absolutePath.
// It returns "" on success and a readable error description otherwise.
func StartProxy(dataDir string) string {
	// Record the intent before starting: if the start then fails, Status must say "error"
	// rather than "stopped", because the user asked for it to run.
	SetDesiredRunning(true)
	if err := startProxy(dataDir); err != nil {
		return err.Error()
	}
	return ""
}

// StopProxy gracefully stops the running gateway and records that it was deliberate.
func StopProxy() string {
	SetDesiredRunning(false)
	if err := stopProxy(); err != nil {
		return err.Error()
	}
	return ""
}

// RestartProxy stops the gateway and starts it again against dataDir.
func RestartProxy(dataDir string) string {
	// A restart is a request to be running: clear the stopped state first so a failure
	// surfaces as "error" instead of being masked by the previous intent.
	SetDesiredRunning(true)
	if err := stopProxy(); err != nil {
		return err.Error()
	}
	// No settle sleep here. It used to wait 150ms "to let the port free up", but stopProxy
	// already waits for the old engine's Run to return (it blocks on the done channel), so the
	// listener is closed by the time we get here. The sleep was pure downtime: every restart
	// kept 127.0.0.1:8317 refusing connections for an extra 150ms, which is exactly the window a
	// client opening the app lands in.
	if err := startProxy(dataDir); err != nil {
		return err.Error()
	}
	return ""
}

// IsRunning reports whether the HTTP gateway is actually serving requests.
//
// This used to be "state.service != nil", which stayed true after the engine died in the
// background — recordError only logged the failure — so the home screen kept saying 运行中
// while nothing was listening on 127.0.0.1:8317. That is exactly the "connection drops and I
// have to restart the gateway" report. A loopback dial is authoritative, and it costs nothing
// in the common failure case because a refused connection returns immediately; the timeout
// only bounds a pathological one.
func IsRunning() bool {
	state.mu.Lock()
	svc := state.service
	addr := state.listenAddr
	state.mu.Unlock()
	if svc == nil || addr == "" {
		return false
	}
	return probeListen(addr, livenessTimeout) == nil
}

// probeListen reports whether something accepts TCP connections on addr.
//
// A wildcard bind address is rewritten to loopback first. Dialling "0.0.0.0:8317" is not the
// same as dialling the machine: on Android it can be routed out through a VPN or TUN interface
// and time out, so a perfectly healthy local gateway would look dead — and this probe runs on
// the UI thread. Loopback is the right target because the engine is in this process.
func probeListen(addr string, timeout time.Duration) error {
	host, port, errSplit := net.SplitHostPort(addr)
	if errSplit != nil {
		return errSplit
	}
	if host == "" || host == "0.0.0.0" || host == "::" || host == "[::]" {
		host = loopbackHost
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(host, port), timeout)
	if err != nil {
		return err
	}
	return conn.Close()
}

// GetListenAddress returns the effective host:port the gateway binds to.
func GetListenAddress() string {
	state.mu.Lock()
	addr := state.listenAddr
	state.mu.Unlock()
	if addr != "" {
		return addr
	}
	return net.JoinHostPort(loopbackHost, itoa(DefaultPort))
}

// GetManagementKey returns the local management secret the Android WebView must
// present to the bundled control panel.
func GetManagementKey() string { return ManagementKey }

// ClientAPIKey returns the key a client (SillyTavern, a chat app) has to send to /v1.
//
// It is NOT GetManagementKey(): that one unlocks the bundled control panel, this one is the
// access.api-keys entry the gateway accepts as a bearer token. The settings screen showed the
// management key, so every client the user configured got 401 and the gateway looked broken.
// Returns "" when the config or the key is missing, so the host can tell "not configured yet"
// from "here is the key".
func ClientAPIKey(dataDir string) string {
	if strings.TrimSpace(dataDir) == "" {
		return ""
	}
	raw, err := os.ReadFile(configPath(dataDir))
	if err != nil {
		return ""
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		return ""
	}
	keys := lookupNode(docRoot(&doc), []string{"access", "api-keys"})
	if keys == nil || keys.Kind != yaml.SequenceNode {
		return ""
	}
	for _, item := range keys.Content {
		if item.Kind == yaml.ScalarNode {
			if v := strings.TrimSpace(item.Value); v != "" {
				return v
			}
		}
	}
	return ""
}

// RotateClientAPIKey replaces the repository-published key in access.api-keys with a fresh
// random one.
//
// Older builds seeded access.api-keys with the constant "cpa-android-local", which is in this
// repository: on an install that binds 0.0.0.0 that single string is the entire door. Upgrades
// keep it, because nothing in the settings path ever rewrote that list — the comment claiming it
// did was wrong. This is the explicit migration the host can offer ("更换密钥"), and it is also
// what the app calls on a LAN bind if it wants the gate to be a real one.
//
// It rewrites every entry that still equals the legacy literal, leaves user-chosen keys alone, and
// adds a generated key when the list ends up empty (an install with no access.api-keys accepts
// nothing, so "rotate" would otherwise mean "leave it broken"). Returns "" on success — including
// the no-op case, where the file is not touched at all — and a readable error otherwise.
func RotateClientAPIKey(dataDir string) string {
	if err := rotateClientAPIKey(dataDir); err != nil {
		return err.Error()
	}
	return ""
}

func rotateClientAPIKey(dataDir string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	configMu.Lock()
	defer configMu.Unlock()
	path := configPath(dataDir)
	recoverConfigFromBackup(path)
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("先启动一次网关，让 config.yaml 生成：%w", err)
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("解析 config.yaml 失败：%w", err)
	}
	root := docRoot(&doc)
	if root == nil || root.Kind != yaml.MappingNode {
		return fmt.Errorf("config.yaml 根节点不是映射（实际是 %s），没有写入", kindName(root))
	}

	seq := lookupNode(root, []string{"access", "api-keys"})
	seq = unwrapAlias(seq)
	replaced := 0
	var next []*yaml.Node
	if seq != nil && seq.Kind == yaml.SequenceNode {
		for _, item := range seq.Content {
			value := item
			if value != nil && value.Kind == yaml.AliasNode && value.Alias != nil {
				value = value.Alias
			}
			if value != nil && value.Kind == yaml.ScalarNode && strings.TrimSpace(value.Value) == legacyAPIKey {
				// Replace the value, keep position and style: a key's line number is what the
				// panel and the user's own notes refer to.
				next = append(next, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: newAPIKey()})
				replaced++
				continue
			}
			next = append(next, item)
		}
	}
	if replaced == 0 && seq != nil && seq.Kind == yaml.SequenceNode && len(seq.Content) > 0 {
		// Nothing was legacy: the file already carries keys the user chose. Do not touch it —
		// a rotation that rewrote a working key would break every client the user configured.
		return nil
	}
	if replaced > 0 {
		seq.Content = next
	}
	if seq == nil || seq.Kind != yaml.SequenceNode || len(seq.Content) == 0 {
		keys := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		keys.Content = append(keys.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: newAPIKey()})
		if err := setPath(root, []string{"access", "api-keys"}, keys); err != nil {
			return err
		}
	} else if replaced > 0 {
		// The engine's watcher reloads on the write, and the engine re-bcrypts while loading, so
		// the tree is written back through the normal path rather than patched in place.
		if err := setPath(root, []string{"access", "api-keys"}, seq); err != nil {
			return err
		}
	}

	out, errMarshal := yaml.Marshal(&doc)
	if errMarshal != nil {
		return errMarshal
	}
	return writeFileAtomic(path, out)
}

// unwrapAlias follows an alias one level, so "access: *a" is treated like the mapping it names.
func unwrapAlias(node *yaml.Node) *yaml.Node {
	if node != nil && node.Kind == yaml.AliasNode && node.Alias != nil {
		return node.Alias
	}
	return node
}

// GetLastError returns the last startup failure recorded by the engine, if any.
func GetLastError() string {
	state.mu.Lock()
	defer state.mu.Unlock()
	return lastError
}

// EnsureDefaultConfig writes the hardened default config.yaml when it is missing and brings an
// existing one up to date.
// proxyURL seeds requests.proxy-url ("" keeps a direct connection).
// allowLan binds 0.0.0.0 instead of 127.0.0.1.
// thinkingMode is one of the Thinking* constants; "" leaves whatever rule the file already has,
// which is what the engine's own startup path wants (only the Android host knows the setting).
func EnsureDefaultConfig(dataDir string, proxyURL string, allowLan bool, thinkingMode string) string {
	if err := ensureDefaultConfig(dataDir, proxyURL, allowLan, thinkingMode); err != nil {
		return err.Error()
	}
	return ""
}

// UpdateQuickSettings rewrites proxy-url, the bind host, the sensitive-word list and the
// reasoning-visibility rule, and the engine picks the file up through its watcher (L2 hot reload).
// The write has to be in place for that to happen at all — see writeFileAtomic.
// sensitiveWordsCSV is comma separated; an empty value restores the defaults.
func UpdateQuickSettings(dataDir string, proxyURL string, allowLan bool, sensitiveWordsCSV string, thinkingMode string) string {
	if err := updateQuickSettings(dataDir, proxyURL, allowLan, sensitiveWordsCSV, thinkingMode); err != nil {
		return err.Error()
	}
	return ""
}

func itoa(v int) string { return fmt.Sprintf("%d", v) }

func configPath(dataDir string) string { return filepath.Join(dataDir, ConfigFileName) }

func authDir(dataDir string) string { return filepath.Join(dataDir, AuthDirName) }

func ensureDir(dir string) error {
	if dir == "" {
		return fmt.Errorf("empty directory")
	}
	return os.MkdirAll(dir, 0o700)
}

func startProxy(dataDir string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	gen, err := startProxyLocked(dataDir)
	if err != nil {
		// Every failure is recorded against the generation this attempt owns, so Status() says
		// 异常 with the reason instead of sitting on "正在启动" with an empty lastError.
		recordError(gen, err)
		return err
	}
	return nil
}

// probeEngineSnapshot reads the installed engine under state.mu and dials it OUTSIDE the lock.
//
// Splitting the two is the point: livenessTimeout is 150ms, and the UI thread calls Status() and
// IsRunning() on a repaint timer, so holding state.mu across the dial froze the status bar for the
// whole timeout on every start and every restart. Nothing here writes; the caller re-checks the id
// and the pointer under the lock before trusting the answer (see startProxyLocked).
func probeEngineSnapshot() (svc *cliproxy.Service, gen uint64, live bool) {
	state.mu.Lock()
	svc = state.service
	addr := state.listenAddr
	gen = state.generation
	state.mu.Unlock()
	if svc == nil {
		return svc, gen, false
	}
	return svc, gen, probeListen(addr, livenessTimeout) == nil
}

// startProxyLocked runs the whole start and returns the generation it created.
func startProxyLocked(dataDir string) (uint64, error) {
	// Snapshot under the lock, dial outside it. livenessTimeout is 150ms and the UI thread calls
	// Status()/IsRunning() on a repaint timer; holding state.mu across the dial blocked every one
	// of those callers for the whole timeout, so a start could freeze the status bar. Nothing is
	// written here — the pointer is read, the probe runs unlocked, and the result is checked
	// against the same generation below.
	//
	// A nil service means nothing is running. Do not treat a live-looking pointer as "already
	// started" without checking: recordError clears it on failure, and the probe catches the
	// case where the engine died without going through recordError at all.
	probeSvc, probeGen, live := probeEngineSnapshot()

	state.mu.Lock()
	// TOCTOU guard. The probe ran unlocked, so the engine it measured may have been retired in
	// the meantime — recordError clears the pointer without bumping the generation, and a stop
	// (which cannot race here, lifecycleMu is held, but is checked anyway) bumps it. Only the
	// exact pair that was probed counts as "already running"; anything else means this call has
	// to build, and it must not return 0 for an engine that is already gone.
	if live && state.service == probeSvc && state.generation == probeGen {
		state.mu.Unlock()
		// No new generation: the engine that is running stays the current one, so its own
		// goroutine still owns the state it is reporting on.
		return 0, nil
	}
	// The id is taken only once this call has decided to build, and before the goroutine below
	// exists. Bumping it for a no-op start would orphan the live engine: its failures would be
	// dropped as "stale" and the UI would never learn the gateway died.
	//
	// Incremented inline, NOT through nextGeneration(): state.mu is already held here, and a
	// helper that locks it again deadlocks the whole bridge on the first start.
	gen := bumpGenerationLocked()
	// A stale pointer means the engine is unreachable but its goroutine may still be alive —
	// that is exactly the state this path exists to recover from. Cancelling it before dropping
	// the pointers matters: leaving it running and starting a second engine puts two Service.Run
	// loops in the same process, and they collide on gin's global mode flag (the race detector
	// caught gin.SetMode in one Run racing gin.IsDebugging in the other). Wait for the old loop
	// to exit so the new one starts from a quiet process.
	staleCancel := state.cancel
	staleDone := state.done
	staleLog := state.logFile
	hadStale := state.service != nil
	state.service = nil
	state.cancel = nil
	state.done = nil
	state.listenAddr = ""
	state.logFile = nil
	state.mu.Unlock()

	if hadStale {
		log.Warnf("cpa engine unreachable; shutting down the old loop before restarting")
		if staleCancel != nil {
			staleCancel()
		}
		if staleDone != nil {
			select {
			case <-staleDone:
			case <-time.After(stopTimeout):
				log.Warnf("old engine loop did not exit within %s", stopTimeout)
			}
		}
		// Detach before closing, or the next log line writes into a closed descriptor.
		detachLogger()
		if staleLog != nil {
			_ = staleLog.Close()
		}
	}

	if err := ensureDir(dataDir); err != nil {
		return gen, fmt.Errorf("create data dir: %w", err)
	}
	if err := ensureDir(authDir(dataDir)); err != nil {
		return gen, fmt.Errorf("create auth dir: %w", err)
	}
	if err := ensureDefaultConfig(dataDir, "", false, ""); err != nil {
		return gen, err
	}

	path := configPath(dataDir)
	cfg, err := sdkconfig.LoadConfig(path)
	if err != nil {
		return gen, fmt.Errorf("load config: %w", err)
	}
	if cfg == nil {
		return gen, fmt.Errorf("load config: empty result")
	}
	cfg.AuthDir = authDir(dataDir)

	listen := net.JoinHostPort(bindHost(cfg.Host), itoa(bindPort(cfg.Port)))

	svc, err := cliproxy.NewBuilder().
		WithConfig(cfg).
		WithConfigPath(path).
		WithLocalManagementPassword(ManagementKey).
		Build()
	if err != nil {
		return gen, fmt.Errorf("build engine: %w", err)
	}

	attachLogger(dataDir)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		if errRun := svc.Run(ctx); errRun != nil && !strings.Contains(errRun.Error(), "context canceled") {
			recordError(gen, errRun)
		}
	}()

	if err := waitForReady(listen, readyTimeout, readinessProbe{
		apiKey:  firstAPIKey(cfg.APIKeys),
		authDir: cfg.AuthDir,
	}); err != nil {
		cancel()
		// Bounded, for the same reason stopProxy bounds it: Run normally returns as soon as its
		// context is cancelled, but a blocked shutdown must not leave the exported StartProxy
		// hanging forever — gomobile calls it from the Kotlin side and the app would freeze.
		select {
		case <-done:
		case <-time.After(stopTimeout):
			log.Warnf("engine loop did not exit within %s after a failed start", stopTimeout)
		}
		// The start failed, so the logger this attempt attached is no longer wanted. Closing it
		// here (rather than waiting for the next start to overwrite it) keeps the descriptor count
		// flat across repeated failures, which is what a flapping network produces.
		detachLogger()
		return gen, fmt.Errorf("engine did not become ready on %s: %w", listen, err)
	}

	state.mu.Lock()
	atomic.AddInt64(&builds, 1)
	state.service = svc
	state.cancel = cancel
	state.done = done
	state.listenAddr = listen
	state.generation = gen
	lastError = ""
	state.mu.Unlock()
	return gen, nil
}

// Gateway states reported by Status.
const (
	// StatusRunning means the engine is up and the port answers.
	StatusRunning = "running"
	// StatusStarting means the user wants it up but it is not serving (yet, or again).
	StatusStarting = "starting"
	// StatusStopped means the user asked for it to stop and it is down.
	StatusStopped = "stopped"
	// StatusError means it was asked to run, is down, and failed with an error.
	StatusError = "error"
)

// SetDesiredRunning records whether the user wants the gateway up.
//
// The Android host calls this in the same breath as start/stop. Keeping the intent inside the
// engine means Status() can answer "stopped" versus "starting" without the UI guessing from a
// pair of booleans — which is what produced "正在启动" after every deliberate stop.
func SetDesiredRunning(want bool) {
	state.mu.Lock()
	desiredRunning = want
	state.mu.Unlock()
}

// GetDesiredRunning reports the recorded intent.
func GetDesiredRunning() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return desiredRunning
}

// Status returns one of the Status* constants, combining intent, liveness and last error.
//
// A single call rather than three getters: the UI repaints on a timer, and reading intent,
// liveness and error separately let them disagree mid-repaint.
func Status() string {
	want := GetDesiredRunning()
	if IsRunning() {
		return StatusRunning
	}
	if !want {
		return StatusStopped
	}
	if strings.TrimSpace(GetLastError()) != "" {
		return StatusError
	}
	return StatusStarting
}

// recordError notes an engine failure and marks the engine as gone.
//
// Clearing service matters as much as the message: the previous version left it set, so a
// dead engine still looked alive to StartProxy (which returns early when service != nil, so
// the user's "restart" could be a no-op) and to IsRunning. desiredRunning is left untouched —
// the user still wants the gateway up, the engine just failed, and that distinction is what
// lets the UI offer "启动" instead of claiming it already is.
func recordError(gen uint64, err error) {
	state.mu.Lock()
	// Only the generation that is actually installed may touch the state. startProxy waits for
	// the previous loop to exit, but that wait is bounded: after stopTimeout it logs and carries
	// on. The abandoned loop then returns late and calls this — and without the id it cleared
	// the pointer and wrote "engine died" onto the healthy engine that replaced it, which is how
	// the gateway ended up reporting 异常 while it was still serving on the port.
	if gen != state.generation {
		state.mu.Unlock()
		log.Debugf("ignoring failure from engine generation %d: %v", gen, err)
		return
	}
	// A deliberate stop cancels the context, and the engine's Run() returns "context canceled"
	// for it. That is the normal path out, not a failure — recording it made a stopped gateway
	// report 异常 with the message "context canceled" the moment the goroutine finished.
	if !desiredRunning && errors.Is(err, context.Canceled) {
		state.service = nil
		state.cancel = nil
		state.done = nil
		state.mu.Unlock()
		log.Infof("cpa engine stopped on request")
		return
	}
	lastError = err.Error()
	state.service = nil
	state.cancel = nil
	state.done = nil
	state.mu.Unlock()
	log.Errorf("cpa engine stopped: %v", err)
}

// bumpGenerationLocked hands out a fresh engine id and installs it as the current one.
//
// The caller must hold state.mu. It exists as a separate function only so the two places that
// retire an engine (a start that decides to build, and every stop) bump the counter the same way.
func bumpGenerationLocked() uint64 {
	generationCounter++
	state.generation = generationCounter
	return generationCounter
}

func stopProxy() error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	state.mu.Lock()
	svc := state.service
	cancel := state.cancel
	done := state.done
	logFile := state.logFile
	state.service = nil
	state.cancel = nil
	state.done = nil
	state.listenAddr = ""
	state.logFile = nil
	// A stop retires the generation as well: whatever its goroutine reports on the way out is
	// about an engine nobody is watching any more.
	bumpGenerationLocked()
	state.mu.Unlock()

	if svc == nil || cancel == nil {
		detachLogger()
		if logFile != nil {
			_ = logFile.Close()
		}
		// A stop with nothing running is a success, and it must not leave a stale error behind:
		// the previous version kept whatever the last failure wrote, so Status() reported
		// "error" for a gateway that was simply stopped.
		clearLastError()
		return nil
	}

	cancel()
	select {
	case <-done:
	case <-time.After(stopTimeout):
		detachLogger()
		if logFile != nil {
			_ = logFile.Close()
		}
		return fmt.Errorf("engine shutdown timed out after %s", stopTimeout)
	}
	detachLogger()
	if logFile != nil {
		_ = logFile.Close()
	}
	// The engine exiting because we cancelled it is not a failure. Without this the goroutine's
	// recordError would race a normal stop and leave "context canceled" in lastError, which the
	// UI then shows as 异常 right after a deliberate 停止.
	clearLastError()
	return nil
}

// clearLastError forgets the previous failure.
func clearLastError() {
	state.mu.Lock()
	lastError = ""
	state.mu.Unlock()
}

// engineLogMaxBytes caps the engine log at 4 MB. The log is append-only and every proxied
// request writes at least one line, so without a cap a heavy SillyTavern user turns a few days
// of chatting into tens of megabytes the app never gives back. Trimming happens once at start:
// when the file is over the cap, the newest half is kept and the rest is dropped. Rotating on
// every write would need a writer wrapper and a mutex on the hot path for no real benefit -
// the interesting part of a log is always the tail.
const engineLogMaxBytes = 4 << 20

func attachLogger(dataDir string) {
	path := filepath.Join(dataDir, "cpa-engine.log")
	trimLogFile(path, engineLogMaxBytes)
	if f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
		state.mu.Lock()
		// Close the previous handle before replacing it. A start that failed at waitForListen
		// already left one open; overwriting the field without closing leaked a descriptor on
		// every failed start.
		if state.logFile != nil {
			_ = state.logFile.Close()
		}
		state.logFile = f
		state.mu.Unlock()
		log.SetOutput(io.MultiWriter(f))
		log.SetLevel(log.InfoLevel)
		log.SetFormatter(&log.TextFormatter{DisableColors: true, FullTimestamp: true})
		return
	}
	log.SetOutput(io.Discard)
}

// detachLogger stops logrus writing into a file this process is about to close.
//
// Closing the handle alone is not enough: logrus keeps the *os.File and every later log line —
// the engine's own shutdown messages, a late goroutine, the next request — tries to write into a
// closed descriptor and ends up as "Failed to write to log, write ...: file already closed" on
// stderr, which on Android is invisible. The interesting messages are the ones right after a
// stop, so they have to survive.
func detachLogger() {
	state.mu.Lock()
	if state.logFile != nil {
		_ = state.logFile.Close()
		state.logFile = nil
	}
	state.mu.Unlock()
	log.SetOutput(io.Discard)
}

// trimLogFile keeps the newest limit bytes of an oversized log, cutting at a line boundary so
// the file never starts mid-record. A missing file or a stat error is not a failure: the caller
// opens the file next and will surface a real problem then.
func trimLogFile(path string, limit int64) {
	info, err := os.Stat(path)
	if err != nil || info.Size() <= limit {
		return
	}
	src, err := os.Open(path)
	if err != nil {
		return
	}
	defer src.Close()
	// Overshoot by one byte so the first partial line is visible and can be dropped.
	if _, err := src.Seek(info.Size()-limit-1, io.SeekStart); err != nil {
		return
	}
	tail, err := io.ReadAll(src)
	if err != nil || len(tail) == 0 {
		return
	}
	if i := bytes.IndexByte(tail, '\n'); i >= 0 && i+1 < len(tail) {
		tail = tail[i+1:]
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, tail, 0o600); err != nil {
		return
	}
	_ = os.Rename(tmp, path)
}

func waitForListen(addr string, timeout time.Duration) error {
	return waitForListenUntil(addr, time.Now().Add(timeout))
}

// waitForListenUntil is waitForListen against a deadline somebody else owns, so the two halves of
// the readiness gate can share one budget instead of each spending the full timeout.
func waitForListenUntil(addr string, deadline time.Time) error {
	var lastErr error
	for time.Now().Before(deadline) {
		conn, err := net.DialTimeout("tcp", addr, 200*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		lastErr = err
		time.Sleep(probeInterval)
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("timeout")
	}
	return lastErr
}

// readinessProbe carries what GET /v1/models needs so the gate can be cheap.
type readinessProbe struct {
	apiKey  string
	authDir string
}

// waitForReady blocks until the engine can actually serve a request, not merely until the port
// accepts a connection.
//
// Measured on the device (Redmi Note 12 Turbo, Android 17): the listener answers at t=0 and the
// model registry is populated 0.41s later. During that window gin accepts the connection and the
// router answers "400 unknown provider for model ...", because the server starts before the core
// auth manager has registered credentials and their models. Every client that opens the app and
// reconnects immediately lands in that window, which is the "comes up disconnected, needs a
// gateway restart" report: the restart was only ever a retry that happened to arrive later.
//
// The probe is GET /v1/models with the client key: 200 plus at least one entry means the registry
// is populated. A configured install (credentials in authDir) additionally requires a non-empty
// list, because the very same request answers 200 with an empty data array while models are still
// being registered. A fresh install with no credentials has nothing to register, so a 200 is the
// whole contract there — waiting for entries would never finish.
func waitForReady(addr string, timeout time.Duration, probe readinessProbe) error {
	// One budget for both halves. waitForListen and the model poll each used to start their own
	// deadline from the full timeout, so a start that never becomes ready took twice as long as
	// advertised to fail — 20 seconds of a frozen "正在启动" on a cold phone.
	deadline := time.Now().Add(timeout)
	if err := waitForListenUntil(addr, deadline); err != nil {
		return err
	}
	requireModels := hasCredentials(probe.authDir)
	var lastErr error
	for time.Now().Before(deadline) {
		count, err := probeModelCount(addr, probe.apiKey)
		if err == nil && (!requireModels || count > 0) {
			return nil
		}
		if err != nil {
			lastErr = err
		} else {
			lastErr = fmt.Errorf("no models registered yet")
		}
		time.Sleep(probeInterval)
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("timeout")
	}
	return lastErr
}

// hasCredentials reports whether any credential file exists, which decides whether the readiness
// gate should wait for a non-empty model list.
func hasCredentials(dir string) bool {
	if strings.TrimSpace(dir) == "" {
		return false
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".json") {
			return true
		}
	}
	return false
}

// probeModelCount asks GET /v1/models and returns how many models came back.
func probeModelCount(addr, apiKey string) (int, error) {
	host, port, errSplit := net.SplitHostPort(addr)
	if errSplit != nil {
		return 0, errSplit
	}
	if host == "" || host == anyHost || host == "::" || host == "[::]" {
		host = loopbackHost
	}
	client := &http.Client{Timeout: 2 * time.Second}
	url := "http://" + net.JoinHostPort(host, port) + "/v1/models"
	req, errReq := http.NewRequest(http.MethodGet, url, nil)
	if errReq != nil {
		return 0, errReq
	}
	if apiKey != "" {
		req.Header.Set("Authorization", "Bearer "+apiKey)
	}
	resp, errDo := client.Do(req)
	if errDo != nil {
		return 0, errDo
	}
	defer func() { _ = resp.Body.Close() }()
	body, errRead := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if errRead != nil {
		return 0, errRead
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return 0, fmt.Errorf("models probe returned %d", resp.StatusCode)
	}
	var doc struct {
		Data []json.RawMessage `json:"data"`
	}
	if errJSON := json.Unmarshal(body, &doc); errJSON != nil {
		return 0, errJSON
	}
	return len(doc.Data), nil
}

// firstAPIKey returns the first configured client key, which is what the readiness probe and the
// host-side sanity checks authenticate with.
func firstAPIKey(keys []string) string {
	for _, k := range keys {
		if trimmed := strings.TrimSpace(k); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

func bindHost(configured string) string {
	if strings.TrimSpace(configured) == "" {
		return loopbackHost
	}
	return configured
}

func bindPort(configured int) int {
	if configured <= 0 {
		return DefaultPort
	}
	return configured
}

func yamlScalar(v string) string {
	if v == "" {
		return "\"\""
	}
	out, err := yaml.Marshal(v)
	if err != nil {
		return "\"\""
	}
	return strings.TrimRight(string(out), "\n")
}

func renderDefaultConfig(dataDir, proxyURL string, allowLan bool) string {
	host := loopbackHost
	if allowLan {
		host = anyHost
	}
	words := make([]string, 0, len(defaultSensitiveWords))
	for _, w := range defaultSensitiveWords {
		words = append(words, "        - "+yamlScalar(w))
	}
	// Argument order follows the template: host, port, proxy-url, models.catalog,
	// api-keys, secret-key, auth-dir, sensitive-words.
	return fmt.Sprintf(defaultConfigTemplate,
		yamlScalar(host), DefaultPort, yamlScalar(proxyURL),
		yamlScalar(catalogPath(dataDir)), yamlScalar(newAPIKey()),
		yamlScalar(ManagementKey), yamlScalar(authDir(dataDir)), strings.Join(words, "\n"))
}

// newAPIKey returns the default proxy key for a fresh install: a fixed prefix plus 8 random hex
// characters. The prefix keeps it recognisable as this app's key; the suffix makes it useless to
// anyone who has read the repository.
//
// Falls back to the legacy constant if crypto/rand is unavailable, which on Android never
// happens; a predictable key is a smaller problem than a gateway nobody can authenticate to.
func newAPIKey() string {
	buf := make([]byte, 4)
	if _, err := rand.Read(buf); err != nil {
		return legacyAPIKey
	}
	return defaultAPIKeyPrefix + hex.EncodeToString(buf)
}

// catalogPath returns the model catalog written next to config.yaml.
//
// The upstream registry ships a catalog missing some variants (antigravity advertises only
// gemini-3.8-flash-high despite the API accepting low/medium), and the remote catalog repeats
// the same gap. Pointing models.catalog at a file we control lets the APK carry the corrected
// table without forking the registry package.
func catalogPath(dataDir string) string {
	return filepath.Join(dataDir, modelsFileName)
}

const defaultConfigTemplate = `# CPA-Android default configuration (CLIProxyAPI v8 layout).
# Hardened defaults: antigravity thinking-block signature cache, credits fallback,
# zero-width-space obfuscation of WAF trigger words, and a 15s SSE keep-alive so long
# "thinking" phases survive mobile NAT idle timers.
config-version: 8

server:
  host: %s
  port: %d

requests:
  # Upstream proxy for provider calls (socks5/http/https, "direct" or "none").
  proxy-url: %s
  # Show the chain of thought to the client. Visibility only — the effort level is left to
  # whichever client asks for one, because forcing it here overrides that choice and spends
  # the caller's token budget on it.
  payload:
    override:
      - models:
          - name: "*"
        params:
          generationConfig.thinkingConfig.includeThoughts: true
  streaming:
    # Emit ": keep-alive" SSE heartbeats every 15s while the model is thinking.
    keepalive-seconds: 15
    # Retry a streaming request before any byte reached the client.
    bootstrap-retries: 2
    send-keepalive-before-ft: true

# Model catalog carried by the APK. The upstream table advertises only
# gemini-3.8-flash-high for antigravity although the API also accepts low/medium.
models:
  catalog: %s

access:
  api-keys:
    - %s

management:
  allow-remote: false
  secret-key: %s
  disable-control-panel: false
  disable-auto-update-panel: true

oauth:
  auth-dir: %s
  providers:
    antigravity:
      # Cache and re-validate thought signatures (cpa-gemini-carrier-v1 / R..E.. base64).
      signature-cache-enabled: true
      # Last-resort fallback to Google One AI credits after the free tier is exhausted.
      antigravity-credits: true
      # Obfuscated with U+200B before the payload leaves the device.
      sensitive-words:
%s
      connection-pool:
        enabled: true
        idle-conn-timeout: "30s"
        max-idle-conns-per-host: 2

routing:
  retry:
    request-retry: 3
    max-retry-credentials: 2
    max-retry-interval: 30

observability:
  logs:
    debug: false
    logging-to-file: false
`

func ensureDefaultConfig(dataDir, proxyURL string, allowLan bool, thinkingMode string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	if err := ensureDir(dataDir); err != nil {
		return err
	}
	if err := ensureDir(authDir(dataDir)); err != nil {
		return err
	}
	// The catalog file itself is copied from the APK assets by the Android host before this
	// runs (Go cannot read packed assets). It is rewritten on every APK update rather than
	// preserved, because unlike config.yaml it holds no user state.
	path := configPath(dataDir)
	// A leftover backup means the process died in the middle of a write. Recover it BEFORE the
	// stat below, or the truncated file it left behind is treated as a healthy config that only
	// needs migrating, and the backup is never looked at again.
	recoverConfigFromBackup(path)
	if _, err := os.Stat(path); err == nil {
		// The file predates this build, so it carries whatever the build that wrote it did:
		// no models.catalog, and possibly the thinking level this app used to hard-code.
		// Patch those keys in place and leave everything else the user may have tuned
		// (proxy, api-keys, sensitive words) exactly as it is.
		return migrateExistingConfig(path, catalogPath(dataDir), thinkingMode)
	} else if !os.IsNotExist(err) {
		return err
	}
	// Write the template, then run the same migration the upgrade path uses. The template carries
	// a hard-coded thinking rule (it predates the three modes), so a fresh install would
	// otherwise run includeThoughts while the settings screen says 不思考 — and only agree after
	// the first restart. Running the migration here makes first start and every later start
	// follow one rule.
	if err := writeFileAtomic(path, []byte(renderDefaultConfig(dataDir, proxyURL, allowLan))); err != nil {
		return err
	}
	return migrateExistingConfig(path, catalogPath(dataDir), thinkingMode)
}

// migrateExistingConfig brings a config written by an older build up to date without touching
// any key the user may have tuned.
//
// Two things age out: models.catalog, which an old file does not have (so the corrected model
// table never loads), and the thinkingLevel this app used to write next to includeThoughts,
// which overrides whatever effort the caller asks for. Rewriting the whole file would discard
// the user's proxy, api-keys and sensitive-word edits, so only those paths are touched, and the
// file is only rewritten when something actually changed.
func migrateExistingConfig(configPath, catalog, thinkingMode string) error {
	raw, errRead := os.ReadFile(configPath)
	if errRead != nil {
		return errRead
	}
	var root yaml.Node
	if errUnmarshal := yaml.Unmarshal(raw, &root); errUnmarshal != nil {
		return errUnmarshal
	}
	// config-version is the schema selector rather than a user setting: the engine's v8
	// validation keys off it. A document that lost it — a hand edit, a bad merge, a build that
	// swallowed the line into a comment — has to get it back instead of silently running
	// without it.
	versionChanged := lookupPath(&root, []string{"config-version"}) != configVersion
	catalogChanged := lookupPath(&root, []string{"models", "catalog"}) != catalog
	// Keep the payload rule in step with the mode the user picked in the app. Passing "" means
	// the caller does not own that setting (the engine's own startup path), so the file is left
	// alone; passing a mode rewrites our rule to match. This also removes the effort an earlier
	// build hard-coded, so an upgraded install does not keep truncating small budgets.
	thinkingChanged := false
	doc := &root
	if doc.Kind == yaml.DocumentNode && len(doc.Content) > 0 {
		doc = doc.Content[0]
	}
	if doc.Kind == yaml.DocumentNode {
		// Still a document after the unwrap means it had no content: an empty or comment-only
		// file. There is no root mapping to patch, and the version check below would report a
		// change for a document that has nothing in it.
		return fmt.Errorf("config has no root node")
	}
	if thinkingMode != "" && doc.Kind == yaml.MappingNode {
		before, _ := yaml.Marshal(&root)
		if err := applyThinkingVisibility(doc, thinkingMode); err != nil {
			return err
		}
		after, _ := yaml.Marshal(&root)
		thinkingChanged = string(before) != string(after)
	}
	// A file that already carries a repeated banner needs a rewrite even when the keys above
	// are fine: the earlier build appended the banner on every migration, so installs updated
	// several times are sitting on several copies with nothing else to fix.
	bannerRepeated := hasRepeatedHeadComments(raw)
	if !versionChanged && !catalogChanged && !thinkingChanged && !bannerRepeated {
		return nil
	}
	// setPath works on a mapping node. Unmarshalling produces a DocumentNode wrapping it, and
	// handing the document straight to setPath makes it see "not a map" and replace the whole
	// tree with an empty one — that wiped a live config down to a single key, so the engine
	// then started on ":0" because server.host and server.port were gone.
	if doc.Kind != yaml.MappingNode {
		return fmt.Errorf("config root is not a YAML mapping")
	}
	if versionChanged {
		if err := setPath(doc, []string{"config-version"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!int", Value: configVersion}); err != nil {
			return err
		}
	}
	if catalogChanged {
		value := &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: catalog}
		if err := setPath(doc, []string{"models", "catalog"}, value); err != nil {
			return err
		}
	}
	out, errMarshal := yaml.Marshal(&root)
	if errMarshal != nil {
		return errMarshal
	}
	// yaml.v3 keeps the document's head comment, so marshalling already writes the banner
	// back. Prepending the leading comment block on top of that duplicated it once per
	// migration — a config touched four times carried four copies of the same five-line
	// banner, which is exactly what the in-app editor showed.
	// collapseDuplicateHeadComments also repairs files that already accumulated copies.
	return writeFileAtomic(configPath, collapseDuplicateHeadComments(out))
}

// hasRepeatedHeadComments reports whether the document's leading comment block contains a
// run that repeats immediately, which is how the banner accumulated.
func hasRepeatedHeadComments(doc []byte) bool {
	lines := strings.Split(string(doc), "\n")
	end := 0
	for end < len(lines) && strings.HasPrefix(strings.TrimSpace(lines[end]), "#") {
		end++
	}
	head := lines[:end]
	return len(dropRepeatedRuns(head)) != len(head)
}

// collapseDuplicateHeadComments removes repeated identical runs of comment lines at the top
// of a document.
//
// It repairs configs written by an earlier build, which prepended the banner on every
// migration: three migrations left the same five lines three times in a row, and because
// they were contiguous they read as one long block. The loop below repeatedly drops a run
// that repeats immediately after itself, so a banner followed by a genuinely different
// comment keeps both — only exact repeats go.
func collapseDuplicateHeadComments(doc []byte) []byte {
	lines := strings.Split(string(doc), "\n")
	// The comment run at the top of the file is the only place the banner can hide.
	end := 0
	for end < len(lines) && strings.HasPrefix(strings.TrimSpace(lines[end]), "#") {
		end++
	}
	head := dropRepeatedRuns(lines[:end])
	tail := lines[end:]
	if len(tail) == 0 {
		return []byte(strings.Join(head, "\n"))
	}
	// The newline between the comment block and the first real line is load-bearing: dropping
	// it glued "…idle timers." onto "config-version: 8", turning the key into a comment and
	// silently deleting it from the document.
	return []byte(strings.Join(append(head, tail...), "\n"))
}

// dropRepeatedRuns removes a leading run that repeats itself immediately, repeatedly, so
// every distinct comment block appears once.
func dropRepeatedRuns(lines []string) []string {
	for {
		dropped := false
		// Try the longest run first: the banner is several lines, and matching the whole
		// banner is what distinguishes a real repeat from two comments that happen to share
		// a first line.
		for size := len(lines) / 2; size >= 1; size-- {
			if len(lines) < size*2 {
				continue
			}
			if !equalLines(lines[:size], lines[size:size*2]) {
				continue
			}
			lines = append(append([]string(nil), lines[:size]...), lines[size*2:]...)
			dropped = true
			break
		}
		if !dropped {
			return lines
		}
	}
}

func equalLines(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// lookupPath returns the scalar value at path, or "" when it is absent.
func lookupPath(root *yaml.Node, path []string) string {
	cur := root
	if cur != nil && cur.Kind == yaml.DocumentNode && len(cur.Content) > 0 {
		cur = cur.Content[0]
	}
	for _, key := range path {
		if cur == nil || cur.Kind != yaml.MappingNode {
			return ""
		}
		var next *yaml.Node
		for j := 0; j+1 < len(cur.Content); j += 2 {
			if cur.Content[j].Value == key {
				next = cur.Content[j+1]
				break
			}
		}
		cur = next
	}
	if cur == nil {
		return ""
	}
	return cur.Value
}

// configBackupSuffix is the sibling copy of the file as it was BEFORE the write that is in
// flight. It exists only during a write and is removed once the new bytes are on disk; a leftover
// means the process died mid-write and [recoverConfigFromBackup] puts the old content back.
const configBackupSuffix = ".lastgood"

// writeFileAtomic replaces path's contents in place, keeping a durable copy until it is done.
//
// It used to stage a temp file and os.Rename() it over the destination. That is atomic, but it
// swaps the inode, and the engine's fsnotify watch is bound to the inode: measured on the
// emulator, the first rename produced no reload event at all and every later one was invisible
// too, so every settings change and every custom-provider save sat in the file until the next
// restart. (The comment on UpdateQuickSettings claimed "the atomic rename triggers the engine
// file watcher" — it never did.) An in-place write keeps the watched inode alive; four
// consecutive in-place writes produced four reload events.
//
// What is lost with the rename is the all-or-nothing guarantee, so the file's CURRENT content is
// copied aside first: if the truncate-and-write is interrupted, [recoverConfigFromBackup] puts
// that copy back instead of leaving the engine with half a document.
//
// The backup holds the OLD bytes, not the new ones. It used to hold the new bytes, which made the
// recovery path destructive: a write that failed half way left a parseable PREFIX of the new
// document in the backup, and the next start "restored" that prefix over a perfectly good file.
func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := ensureDir(dir); err != nil {
		return err
	}
	// Refuse to install a document the engine cannot read. Every caller builds its bytes by
	// marshalling a YAML tree, so an unparsable result means a bug here, and writing it would
	// take a working gateway down until the next release.
	if len(bytes.TrimSpace(data)) == 0 {
		return fmt.Errorf("refusing to write an empty %s", filepath.Base(path))
	}
	var probe yaml.Node
	if errParse := yaml.Unmarshal(data, &probe); errParse != nil {
		return fmt.Errorf("refusing to write unparsable %s: %w", filepath.Base(path), errParse)
	}

	backup := path + configBackupSuffix
	current, errCurrent := os.ReadFile(path)
	switch {
	case errCurrent == nil:
		// Stage the old bytes. A failure here means there is nothing to fall back on, so the new
		// document is not written at all.
		if errBackup := writeDurable(backup, current); errBackup != nil {
			return fmt.Errorf("stage %s: %w", filepath.Base(backup), errBackup)
		}
	case os.IsNotExist(errCurrent):
		// Nothing to lose: a brand-new file needs no fallback.
		_ = os.Remove(backup)
	default:
		return errCurrent
	}

	if err := writeDurable(path, data); err != nil {
		// The backup is deliberately left in place: the next start recovers from it.
		return err
	}
	_ = os.Remove(backup)
	return nil
}

// writeDurable truncates and rewrites one file, then flushes it to disk.
//
// Truncating rather than creating keeps the inode, which is the whole point (see
// writeFileAtomic). The single Write of a config-sized buffer is what makes the truncate window
// small; the backup file is what makes it recoverable.
func writeDurable(path string, data []byte) error {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Sync(); err != nil {
		_ = f.Close()
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Chmod(path, 0o600)
}

// recoverConfigFromBackup restores the copy a write stages before it overwrites the file, when
// that copy was never cleaned up — which means the write that produced it never finished.
//
// The presence of the file is the signal, not the state of the live config: a truncated YAML is
// still valid YAML (half a document parses fine — the tail is what is missing), so "does the live
// file parse" cannot tell a good file from a half-written one. A completed write always removes
// its backup, so a leftover backup is by definition an unfinished write.
//
// The backup is only restored when the engine itself accepts it. Staging can be interrupted too,
// and a half-written copy is exactly as broken as a half-written config; restoring one over a
// readable file would turn a crash into data loss. The check goes through the SDK rather than
// yaml.Unmarshal because "it parses" is not the same as "the engine will start on it" — a
// document whose api-keys block lost its keys parses fine and then refuses to load, which is how
// a recoverable crash turned into a gateway that would not come up.
func recoverConfigFromBackup(path string) bool {
	backup := path + configBackupSuffix
	raw, errBackup := os.ReadFile(backup)
	if errBackup != nil {
		return false
	}
	// Probe a COPY: LoadConfig rewrites the file it validates (it bcrypt-hashes a plaintext
	// management secret and normalises the layout), and the bytes restored here should be the
	// ones that were staged, not a normalised derivative of them.
	probe, errProbe := os.CreateTemp(filepath.Dir(path), ".lastgood-probe-*")
	if errProbe != nil {
		return false
	}
	probePath := probe.Name()
	_ = probe.Close()
	defer func() { _ = os.Remove(probePath) }()
	if errWrite := os.WriteFile(probePath, raw, 0o600); errWrite != nil {
		return false
	}
	if _, errEngine := sdkconfig.LoadConfig(probePath); errEngine != nil {
		// The backup itself is unusable, so there is nothing to restore; drop it so it cannot
		// block or confuse a later write.
		_ = os.Remove(backup)
		log.Warnf("discarding an unusable config backup: %v", errEngine)
		return false
	}
	if errRestore := writeDurable(path, raw); errRestore != nil {
		return false
	}
	_ = os.Remove(backup)
	return true
}

func updateQuickSettings(dataDir, proxyURL string, allowLan bool, sensitiveWordsCSV string, thinkingMode string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	// The whole read-modify-write runs under configMu; see its comment for what two interleaved
	// writers cost.
	configMu.Lock()
	defer configMu.Unlock()

	path := configPath(dataDir)
	if _, err := os.Stat(path); err != nil {
		if !os.IsNotExist(err) {
			return err
		}
		if err := ensureDefaultConfig(dataDir, proxyURL, allowLan, thinkingMode); err != nil {
			return err
		}
	}

	recoverConfigFromBackup(path)
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}
	if len(doc.Content) == 0 {
		return fmt.Errorf("parse config: document is empty")
	}
	root := doc.Content[0]
	// The root has to be a mapping, and refusing is the whole point: setPath no longer repairs a
	// sequence root by replacing it, and this path writes four keys — silently rewriting whatever
	// the user had down to those four is far worse than an error the settings screen can show.
	if root == nil || root.Kind != yaml.MappingNode {
		return fmt.Errorf("config.yaml 根节点不是映射（实际是 %s），没有写入", kindName(root))
	}

	host := loopbackHost
	if allowLan {
		host = anyHost
	}
	if err := setPath(root, []string{"server", "host"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: host}); err != nil {
		return err
	}
	if err := setPath(root, []string{"requests", "proxy-url"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: strings.TrimSpace(proxyURL)}); err != nil {
		return err
	}
	if err := setPath(root, []string{"oauth", "auth-dir"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: authDir(dataDir)}); err != nil {
		return err
	}

	seq := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
	for _, w := range parseSensitiveWords(sensitiveWordsCSV) {
		seq.Content = append(seq.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: w})
	}
	if err := setPath(root, []string{"oauth", "providers", "antigravity", "sensitive-words"}, seq); err != nil {
		return err
	}

	if err := applyThinkingVisibility(root, thinkingMode); err != nil {
		return err
	}

	out, err := yaml.Marshal(&doc)
	if err != nil {
		return err
	}
	return writeFileAtomic(path, out)
}

// Thinking modes. The switch is three-way rather than on/off because the measured behaviour
// does not split that way: visible reasoning exists only at medium effort and above, and those
// are exactly the settings that ruin a short-token budget.
const (
	// ThinkingCompact spends nothing on thinking. At max_tokens=300 (SillyTavern's default)
	// this returns 167-208 characters of reply with no truncation.
	ThinkingCompact = "compact"
	// ThinkingVisible asks for the chain of thought. Measured at max_tokens=300: 12-19
	// characters of reply and finish_reason max_tokens — it needs a budget of about 1000 or
	// more to be useful, which the settings screen says next to the option.
	ThinkingVisible = "visible"
	// ThinkingOff writes no rule. The model still thinks on its own (340-394 chars measured),
	// it just does not send it back — so a small budget is still consumed.
	ThinkingOff = "off"
)

// normalizeThinkingMode maps a stored or historical value onto one of the three modes.
//
// The previous build stored a boolean ("true" showed the chain of thought), so "true" has to
// keep meaning "visible" instead of silently falling back to the default and turning the
// user's choice off after an upgrade.
func normalizeThinkingMode(mode string) string {
	switch strings.TrimSpace(strings.ToLower(mode)) {
	case ThinkingVisible, "true", "on", "1":
		return ThinkingVisible
	case ThinkingOff, "false", "0":
		return ThinkingOff
	default:
		return ThinkingCompact
	}
}

// applyThinkingVisibility writes the payload rule for the requested thinking mode.
//
// Implemented as a requests.payload.override rule so the behaviour is visible in config.yaml
// and survives an upstream sync. Whatever the mode, only the rule this function owns is
// replaced: a user's own override entries are left untouched.
func applyThinkingVisibility(root *yaml.Node, mode string) error {
	path := []string{"requests", "payload", "override"}
	// Always drop our own rule first, then re-add the one this mode needs. Rewriting in place
	// rather than appending keeps config.yaml from growing a rule per mode change.
	removeThinkingRule(root, path)

	resolved := normalizeThinkingMode(mode)
	params := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
	switch resolved {
	case ThinkingVisible:
		params.Content = append(params.Content,
			&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: thinkingParamPath},
			&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!bool", Value: "true"},
			&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: thinkingLevelPath},
			&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: thinkingLevelLow},
		)
	case ThinkingCompact:
		params.Content = append(params.Content,
			&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: thinkingBudgetPath},
			&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!int", Value: "0"},
		)
	default:
		return nil
	}

	rule := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
	models := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq", Content: []*yaml.Node{
		{Kind: yaml.MappingNode, Tag: "!!map", Content: []*yaml.Node{
			{Kind: yaml.ScalarNode, Tag: "!!str", Value: "name"},
			{Kind: yaml.ScalarNode, Tag: "!!str", Value: "*"},
		}},
	}}
	if err := setPath(rule, []string{"models"}, models); err != nil {
		return err
	}
	if err := setPath(rule, []string{"params"}, params); err != nil {
		return err
	}

	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		seq = &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		// setPath COPIES the value struct into the tree, so the local seq is a different pointer
		// from the one the document now holds; appending to it afterwards would write the rule
		// into a node nobody can see. Look the node up again instead. This is not hypothetical:
		// with requests.payload.override missing or shaped like a mapping instead of a list (both
		// happen in hand-edited configs), the rule was silently never written and the mode switch
		// did nothing at all.
		if err := setPath(root, path, seq); err != nil {
			return err
		}
		seq = lookupNode(root, path)
		if seq == nil {
			return fmt.Errorf("write %s: path disappeared", strings.Join(path, "."))
		}
	}
	seq.Content = append(seq.Content, rule)
	return nil
}

// managedThinkingParams are the payload keys this app is allowed to write or remove. Anything
// else in a rule means the user built that rule, not us.
var managedThinkingParams = map[string]bool{
	thinkingParamPath:  true,
	thinkingLevelPath:  true,
	thinkingBudgetPath: true,
}

// ownThinkingRule reports whether an override entry is the one this app manages.
//
// Ownership is deliberately strict, because "remove our rule before writing the new one" would
// otherwise delete a user's hand-written override: the entry must match every model (the
// wildcard we always write) and its params may contain nothing but our own keys.
func ownThinkingRule(rule *yaml.Node) bool {
	if rule.Kind != yaml.MappingNode {
		return false
	}
	models := lookupNode(rule, []string{"models"})
	if models == nil || models.Kind != yaml.SequenceNode || len(models.Content) != 1 {
		return false
	}
	only := models.Content[0]
	if only.Kind != yaml.MappingNode {
		return false
	}
	if name := lookupNode(only, []string{"name"}); name == nil || name.Value != "*" {
		return false
	}
	params := lookupNode(rule, []string{"params"})
	if params == nil || params.Kind != yaml.MappingNode || len(params.Content) == 0 {
		return false
	}
	for i := 0; i+1 < len(params.Content); i += 2 {
		// The keys are literal dotted paths ("generationConfig.thinkingConfig.includeThoughts"),
		// not a nested tree, so each one is a single scalar.
		if !managedThinkingParams[params.Content[i].Value] {
			return false
		}
	}
	return true
}

// hasThinkingRule reports whether the managed thinking rule is already present.
func hasThinkingRule(root *yaml.Node, path []string) bool {
	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		return false
	}
	for _, rule := range seq.Content {
		if ownThinkingRule(rule) {
			return true
		}
	}
	return false
}

// removeThinkingRule deletes just the rule this app manages, leaving every user entry in place.
func removeThinkingRule(root *yaml.Node, path []string) {
	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		return
	}
	kept := seq.Content[:0]
	for _, rule := range seq.Content {
		if !ownThinkingRule(rule) {
			kept = append(kept, rule)
		}
	}
	seq.Content = kept
	if len(seq.Content) == 0 {
		removePath(root, path)
	}
}

func lookupNode(root *yaml.Node, path []string) *yaml.Node {
	cur := root
	if cur != nil && cur.Kind == yaml.DocumentNode && len(cur.Content) > 0 {
		cur = cur.Content[0]
	}
	for _, key := range path {
		if cur == nil || cur.Kind != yaml.MappingNode {
			return nil
		}
		var next *yaml.Node
		for j := 0; j+1 < len(cur.Content); j += 2 {
			if cur.Content[j].Value == key {
				next = cur.Content[j+1]
				break
			}
		}
		cur = next
	}
	return cur
}

func removePath(root *yaml.Node, path []string) {
	if len(path) == 0 {
		return
	}
	parent := lookupNode(root, path[:len(path)-1])
	if parent == nil || parent.Kind != yaml.MappingNode {
		return
	}
	key := path[len(path)-1]
	kept := parent.Content[:0]
	for j := 0; j+1 < len(parent.Content); j += 2 {
		// Every copy goes, matching setPath, which writes every copy. A duplicated key is not a
		// valid document — the engine's own loader fails it with "mapping key %q already
		// defined" — so "which copy did you mean" has no good answer; the two operations at
		// least have to agree, or a remove-then-set pair leaves one copy behind carrying the
		// value the caller thought it had just deleted.
		if parent.Content[j].Value == key {
			continue
		}
		kept = append(kept, parent.Content[j], parent.Content[j+1])
	}
	parent.Content = kept
}

func parseSensitiveWords(csv string) []string {
	out := make([]string, 0, len(defaultSensitiveWords))
	seen := make(map[string]bool, len(defaultSensitiveWords))
	for _, part := range strings.Split(csv, ",") {
		w := strings.TrimSpace(part)
		if w == "" || seen[w] {
			continue
		}
		seen[w] = true
		out = append(out, w)
	}
	if len(out) == 0 {
		return append([]string(nil), defaultSensitiveWords...)
	}
	return out
}

// setPath writes value at path, creating the intermediate mappings it needs.
//
// Two rules keep it from destroying a document it does not fully understand. It refuses a root
// that is not a mapping instead of turning it into one — the previous version did exactly that,
// and since setPath is the tool every writer uses, one call on a config whose root had become a
// sequence (a bad merge, an external tool) replaced the entire user configuration with the three
// or four keys that writer happened to set. And an intermediate node that holds something other
// than a mapping is an error, not something to flatten: "server: []" means the file is not what
// the caller thinks it is, and silently overwriting it loses whatever was there.
func setPath(root *yaml.Node, path []string, value *yaml.Node) error {
	if root == nil {
		return fmt.Errorf("set %s: no document", strings.Join(path, "."))
	}
	if root.Kind != yaml.MappingNode {
		return fmt.Errorf("set %s: document root is %s, not a mapping", strings.Join(path, "."), kindName(root))
	}
	// cur is a WORKING SET, not a single node: a key that appears twice in one mapping makes the
	// document invalid at the engine's loader ("mapping key %q already defined"), so there is no
	// "right" copy to pick. Every copy gets the same value instead, which is what makes
	// set and removePath agree — removePath deletes all of them, and a remove-then-set pair that
	// touched only the first would leave the value the caller meant to replace sitting in the
	// second copy, where the engine's error message then points at it.
	cur := []*yaml.Node{root}
	for i, key := range path {
		last := i == len(path)-1
		next := make([]*yaml.Node, 0, len(cur))
		// The first copy installed takes the caller's node as-is; further copies are cloned so
		// two positions in the tree never share one Content slice (editing one later would then
		// silently edit the other).
		installed := false
		take := func() *yaml.Node {
			if installed {
				return cloneNode(value)
			}
			installed = true
			return value
		}
		for _, node := range cur {
			found := false
			for j := 0; j+1 < len(node.Content); j += 2 {
				if node.Content[j].Value != key {
					continue
				}
				found = true
				child := node.Content[j+1]
				if last {
					overwriteNode(child, take())
					continue
				}
				if child.Kind == yaml.ScalarNode && child.Tag == "!!null" && child.Value == "" {
					// An explicitly empty key ("server:" with nothing under it) is the one case
					// where building the mapping is what the user meant.
					child.Kind, child.Tag, child.Value = yaml.MappingNode, "!!map", ""
				} else if child.Kind != yaml.MappingNode {
					return fmt.Errorf("set %s: %s is %s, not a mapping", strings.Join(path, "."), key, kindName(child))
				}
				next = append(next, child)
			}
			if found {
				continue
			}
			if last {
				node.Content = append(node.Content,
					&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, take())
				continue
			}
			child := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
			node.Content = append(node.Content,
				&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, child)
			next = append(next, child)
		}
		if last {
			return nil
		}
		cur = next
	}
	return nil
}

// overwriteNode replaces a node's content while keeping what identifies it to the rest of the
// document.
//
// A plain *child = *value drops Anchor, Alias and all three comment fields, because they are part
// of the struct being overwritten. Losing the comments is cosmetic; losing the anchor is not: any
// other node written as "*h" then points at an anchor that no longer exists, yaml.Marshal happily
// emits the dangling reference, and the engine refuses the file it just had written for it —
// "unknown anchor 'h' referenced" on the next start.
func overwriteNode(child, value *yaml.Node) {
	if child == nil || value == nil {
		return
	}
	anchor := child.Anchor
	head, line, foot := child.HeadComment, child.LineComment, child.FootComment
	*child = *value
	child.Anchor = anchor
	child.HeadComment = head
	child.LineComment = line
	child.FootComment = foot
	// Alias is deliberately not carried over. A node that was itself "*h" has no content of its
	// own, and yaml.Marshal would emit the alias again, silently dropping the value just written.
	child.Alias = nil
}

// ---------------------------------------------------------------------------- custom providers
//
// Users who pay for an OpenAI-compatible service (DeepSeek, Kimi, OpenRouter, a local llama.cpp…)
// want it behind the same gateway as the OAuth accounts, so one base URL in SillyTavern reaches
// every model they own. The kernel already supports that through api-keys.openai-compatibility;
// what was missing was a way to edit it without hand-writing YAML, so these three exports carry
// one group list in and out as JSON (gobind cannot marshal structs).

// customProviderModel is one entry of a group's models list. Alias is what clients ask for and
// Name is what the upstream is told; an empty alias means "same as name".
type customProviderModel struct {
	Name  string `json:"name"`
	Alias string `json:"alias,omitempty"`
}

// customProvider is one api-keys.openai-compatibility group.
type customProvider struct {
	Name     string                `json:"name"`
	BaseURL  string                `json:"baseUrl"`
	Keys     []string              `json:"keys,omitempty"`
	Models   []customProviderModel `json:"models,omitempty"`
	Disabled bool                  `json:"disabled,omitempty"`
}

// Field caps for a custom provider.
//
// These are not taste: everything here is written into config.yaml verbatim, and the file is
// re-parsed by the engine at every start. An unbounded name or a 200 KB model list is a config
// the settings screen can no longer load (the whole document goes through the WebView bridge as
// one JSON string), and C0 control characters are the one class yaml.v3 will happily quarantine
// into double quotes while the engine's own loader and the panel both choke on them.
const (
	maxProviderNameLen  = 64
	maxModelNameLen     = 128
	maxModelAliasLen    = 128
	maxProviderKeyLen   = 512
	maxProviderKeys     = 32
	maxProviderModels   = 256
	maxProviderCount    = 64
	maxProviderURLBytes = 2048
)

// validateProviderFields rejects a provider the settings screen must never be able to save.
//
// The old check was a bare prefix test, so "http://", "http:///x" and "https:// " all passed:
// they are written, the engine loads them, and every request through that group fails at dial
// time with an error the panel cannot attribute to the field the user edited.
func validateProviderFields(index int, name string, p customProvider) error {
	base := strings.TrimSpace(p.BaseURL)
	if base == "" {
		return fmt.Errorf("%s 缺少接口地址", name)
	}
	if err := validateProviderBaseURL(base); err != nil {
		return fmt.Errorf("%s 的接口地址%s", name, err)
	}
	if err := checkFieldText(name, maxProviderNameLen, "提供商名称"); err != nil {
		return fmt.Errorf("第 %d 个提供商：%w", index+1, err)
	}
	if len(p.Keys) > maxProviderKeys {
		return fmt.Errorf("%s 的 Key 最多 %d 个（现在 %d 个）", name, maxProviderKeys, len(p.Keys))
	}
	for _, k := range p.Keys {
		if err := checkFieldText(k, maxProviderKeyLen, "Key"); err != nil {
			return fmt.Errorf("%s：%w", name, err)
		}
	}
	if len(p.Models) > maxProviderModels {
		return fmt.Errorf("%s 的模型最多 %d 个（现在 %d 个）", name, maxProviderModels, len(p.Models))
	}
	for _, m := range p.Models {
		if err := checkFieldText(m.Name, maxModelNameLen, "模型名"); err != nil {
			return fmt.Errorf("%s：%w", name, err)
		}
		if err := checkFieldText(m.Alias, maxModelAliasLen, "模型别名"); err != nil {
			return fmt.Errorf("%s：%w", name, err)
		}
	}
	return nil
}

// validateProviderBaseURL checks the whole URL, not just its prefix.
//
// Requirements, all of them load-bearing for "does a request through this group ever work":
// a http/https scheme, a non-empty host, and a host that is either loopback, a bare hostname
// with a dot, or a literal IP. A single-label host is allowed only as "localhost": "https://x/v1"
// resolves through search domains on Android and fails in a way the panel would report as a
// provider outage.
func validateProviderBaseURL(raw string) error {
	if len(raw) > maxProviderURLBytes {
		return fmt.Errorf("太长（最多 %d 字节）", maxProviderURLBytes)
	}
	for _, r := range raw {
		if r < 0x20 || r == 0x7f {
			return fmt.Errorf("含有控制字符")
		}
	}
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("无法解析：%w", err)
	}
	scheme := strings.ToLower(u.Scheme)
	if scheme != "http" && scheme != "https" {
		return fmt.Errorf("要以 http:// 或 https:// 开头")
	}
	// The character class above already rejected every space, but url.Parse accepts a URL whose
	// path starts with one after percent-decoding ("https://x/v1 "), and that lands in YAML as
	// trailing whitespace the user cannot see.
	if strings.TrimSpace(u.Path) != u.Path || strings.TrimSpace(u.RawQuery) != u.RawQuery {
		return fmt.Errorf("不能以空格结尾")
	}
	host := u.Hostname()
	if host == "" {
		return fmt.Errorf("缺少主机名")
	}
	if u.User != nil && u.User.Username() == "" {
		return fmt.Errorf("主机名是空的")
	}
	if net.ParseIP(host) != nil || host == "localhost" {
		return nil
	}
	if !strings.Contains(host, ".") {
		return fmt.Errorf("里的主机名 %q 不完整", host)
	}
	return nil
}

// checkFieldText rejects text that is over the cap or carries C0 control characters.
func checkFieldText(v string, max int, what string) error {
	if len(v) > max {
		return fmt.Errorf("%s 最长 %d 字节（现在 %d 字节）", what, max, len(v))
	}
	for i := 0; i < len(v); i++ {
		if v[i] < 0x20 || v[i] == 0x7f {
			return fmt.Errorf("%s 含有控制字符（第 %d 个字节是 0x%02x）", what, i+1, v[i])
		}
	}
	return nil
}

// customProviderList is the JSON envelope both directions use. Error is only ever set on read.
type customProviderList struct {
	Providers []customProvider `json:"providers"`
	Error     string           `json:"error,omitempty"`
}

// GetCustomProviders returns the configured OpenAI-compatible providers as JSON.
func GetCustomProviders(dataDir string) string {
	list := customProviderList{Providers: []customProvider{}}
	providers, err := readCustomProviders(dataDir)
	if err != nil {
		list.Error = err.Error()
	} else if providers != nil {
		list.Providers = providers
	}
	out, errMarshal := json.Marshal(list)
	if errMarshal != nil {
		return "{\"providers\":[],\"error\":\"encode failed\"}"
	}
	return string(out)
}

// SetCustomProviders replaces the whole group list with the JSON payload and reloads the engine.
func SetCustomProviders(dataDir string, payload string) string {
	var in customProviderList
	if err := json.Unmarshal([]byte(payload), &in); err != nil {
		return "解析失败：" + err.Error()
	}
	if err := writeCustomProviders(dataDir, in.Providers); err != nil {
		return err.Error()
	}
	return ""
}

func readCustomProviders(dataDir string) ([]customProvider, error) {
	if strings.TrimSpace(dataDir) == "" {
		return nil, fmt.Errorf("dataDir is required")
	}
	path := configPath(dataDir)
	// No recovery here. Reading the settings screen must not rewrite config.yaml: the restore
	// path was reached from this getter, so merely opening the provider editor could replace a
	// good file with a stale backup. Recovery belongs to startup and to the writers below.
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	var doc yaml.Node
	if errUnmarshal := yaml.Unmarshal(raw, &doc); errUnmarshal != nil {
		return nil, fmt.Errorf("解析 config.yaml 失败：%w", errUnmarshal)
	}
	groups := lookupNode(docRoot(&doc), []string{"api-keys", "openai-compatibility"})
	if groups == nil || groups.Kind != yaml.SequenceNode {
		return nil, nil
	}
	out := make([]customProvider, 0, len(groups.Content))
	for _, item := range groups.Content {
		if item == nil || item.Kind != yaml.MappingNode {
			continue
		}
		p := customProvider{
			Name:     strings.TrimSpace(scalarAt(item, "name")),
			BaseURL:  strings.TrimSpace(scalarAt(item, "base-url")),
			Disabled: strings.EqualFold(scalarAt(item, "disabled"), "true"),
		}
		// A file written by this app uses keys:, a hand-written v8 file may use
		// api-key-entries:, and the legacy layout put one key per group. Read all three so the
		// UI never shows a provider that works as if it had no key.
		if keys := lookupNode(item, []string{"keys"}); keys != nil && keys.Kind == yaml.SequenceNode {
			for _, k := range keys.Content {
				if v := strings.TrimSpace(scalarAt(k, "api-key")); v != "" {
					p.Keys = append(p.Keys, v)
				}
			}
		} else if entries := lookupNode(item, []string{"api-key-entries"}); entries != nil && entries.Kind == yaml.SequenceNode {
			for _, k := range entries.Content {
				if v := strings.TrimSpace(scalarAt(k, "api-key")); v != "" {
					p.Keys = append(p.Keys, v)
				}
			}
		} else if v := strings.TrimSpace(scalarAt(item, "api-key")); v != "" {
			p.Keys = append(p.Keys, v)
		}
		if models := lookupNode(item, []string{"models"}); models != nil && models.Kind == yaml.SequenceNode {
			for _, m := range models.Content {
				name := strings.TrimSpace(scalarAt(m, "name"))
				if name == "" {
					continue
				}
				p.Models = append(p.Models, customProviderModel{Name: name, Alias: strings.TrimSpace(scalarAt(m, "alias"))})
			}
		}
		out = append(out, p)
	}
	return out, nil
}

func writeCustomProviders(dataDir string, providers []customProvider) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	configMu.Lock()
	defer configMu.Unlock()
	path := configPath(dataDir)
	recoverConfigFromBackup(path)
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("先启动一次网关，让 config.yaml 生成：%w", err)
	}
	var doc yaml.Node
	if errUnmarshal := yaml.Unmarshal(raw, &doc); errUnmarshal != nil {
		return fmt.Errorf("解析 config.yaml 失败：%w", errUnmarshal)
	}
	root := docRoot(&doc)
	if root == nil || root.Kind != yaml.MappingNode {
		return fmt.Errorf("config.yaml 根节点不是映射")
	}

	existing := map[string]*yaml.Node{}
	existingModels := map[string]map[string]*yaml.Node{}
	if groups := lookupNode(root, []string{"api-keys", "openai-compatibility"}); groups != nil && groups.Kind == yaml.SequenceNode {
		for _, item := range groups.Content {
			if item == nil || item.Kind != yaml.MappingNode {
				continue
			}
			name := strings.TrimSpace(scalarAt(item, "name"))
			// Keyed case-insensitively, because the duplicate check below is: renaming "DeepSeek"
			// to "deepseek" is a rename to this screen, and looking the old group up by the exact
			// spelling threw away everything the screen does not edit (priority, headers,
			// display-name) instead of carrying it over.
			key := strings.ToLower(name)
			if name != "" {
				existing[key] = item
			}
			byModel := map[string]*yaml.Node{}
			if models := lookupNode(item, []string{"models"}); models != nil && models.Kind == yaml.SequenceNode {
				for _, m := range models.Content {
					if key := strings.TrimSpace(scalarAt(m, "name")); key != "" {
						byModel[key] = m
					}
				}
			}
			existingModels[key] = byModel
		}
	}

	if len(providers) > maxProviderCount {
		return fmt.Errorf("最多 %d 个提供商（现在 %d 个）", maxProviderCount, len(providers))
	}
	seq := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
	seen := make(map[string]bool, len(providers))
	for index, p := range providers {
		name := strings.TrimSpace(p.Name)
		if name == "" {
			return fmt.Errorf("第 %d 个提供商缺少名称", index+1)
		}
		if seen[strings.ToLower(name)] {
			return fmt.Errorf("提供商名称重复：%s", name)
		}
		seen[strings.ToLower(name)] = true
		if err := validateProviderFields(index, name, p); err != nil {
			return err
		}
		base := strings.TrimSpace(p.BaseURL)

		node := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
		// Start from the group already on disk so fields this screen does not manage
		// (headers, priority, request-retry, prefix…) survive the round trip.
		if old := existing[strings.ToLower(name)]; old != nil {
			node = cloneNode(old)
		}
		if err := setPath(node, []string{"name"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: name}); err != nil {
			return err
		}
		if err := setPath(node, []string{"base-url"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: base}); err != nil {
			return err
		}
		if p.Disabled {
			if err := setPath(node, []string{"disabled"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!bool", Value: "true"}); err != nil {
				return err
			}
		} else {
			removePath(node, []string{"disabled"})
		}
		// keys: is the v8 spelling; the loader expands it into api-key-entries. Writing the
		// expanded form too would leave two lists the runtime has to agree about.
		removePath(node, []string{"api-key-entries"})
		keys := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		for _, k := range p.Keys {
			if v := strings.TrimSpace(k); v != "" {
				keys.Content = append(keys.Content, &yaml.Node{
					Kind: yaml.MappingNode, Tag: "!!map",
					Content: []*yaml.Node{
						{Kind: yaml.ScalarNode, Tag: "!!str", Value: "api-key"},
						{Kind: yaml.ScalarNode, Tag: "!!str", Value: v},
					},
				})
			}
		}
		// keys: is ALWAYS written, even empty. The engine's v8 expansion (internal/config/
		// config_v8.go expandV8Groups) rejects a group whose keys node is missing or is not a
		// sequence, and it validates before anything else runs — so a provider saved without a
		// key (a local llama.cpp or ollama endpoint, which is the obvious thing to configure
		// here) used to write a config the engine then refused to load at all: the gateway would
		// not start until the user found and deleted the block by hand. An empty list is
		// accepted by validateWeightSequenceNode (it iterates zero entries) and starts fine.
		if err := setPath(node, []string{"keys"}, keys); err != nil {
			return err
		}

		models := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		oldModels := existingModels[strings.ToLower(name)]
		for _, m := range p.Models {
			modelName := strings.TrimSpace(m.Name)
			if modelName == "" {
				continue
			}
			alias := strings.TrimSpace(m.Alias)
			if alias == "" {
				alias = modelName
			}
			mn := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
			if old := oldModels[modelName]; old != nil {
				mn = cloneNode(old)
			}
			if err := setPath(mn, []string{"name"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: modelName}); err != nil {
				return err
			}
			if err := setPath(mn, []string{"alias"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: alias}); err != nil {
				return err
			}
			models.Content = append(models.Content, mn)
		}
		if len(models.Content) == 0 {
			return fmt.Errorf("%s 至少要写一个模型名", name)
		}
		if err := setPath(node, []string{"models"}, models); err != nil {
			return err
		}
		seq.Content = append(seq.Content, node)
	}

	if len(seq.Content) == 0 {
		removePath(root, []string{"api-keys", "openai-compatibility"})
		removeEmptyPath(root, []string{"api-keys"})
	} else if err := setPath(root, []string{"api-keys", "openai-compatibility"}, seq); err != nil {
		return err
	}

	out, errMarshal := yaml.Marshal(&doc)
	if errMarshal != nil {
		return errMarshal
	}
	// The atomic rename is what the engine's file watcher sees; writing in place would let it
	// reload a half-written file.
	return writeFileAtomic(path, out)
}

// kindName describes a node kind for an error message, without dragging yaml.Kind's integer into
// the text the settings screen shows.
func kindName(node *yaml.Node) string {
	if node == nil {
		return "空"
	}
	switch node.Kind {
	case yaml.DocumentNode:
		return "文档"
	case yaml.MappingNode:
		return "映射"
	case yaml.SequenceNode:
		return "列表"
	case yaml.ScalarNode:
		return "标量"
	case yaml.AliasNode:
		return "别名"
	default:
		return "未知节点"
	}
}

func docRoot(doc *yaml.Node) *yaml.Node {
	if doc == nil {
		return nil
	}
	if doc.Kind == yaml.DocumentNode {
		if len(doc.Content) == 0 {
			return nil
		}
		return doc.Content[0]
	}
	return doc
}

// scalarAt returns a mapping child's scalar value, or "" when absent.
func scalarAt(node *yaml.Node, key string) string {
	if node == nil || node.Kind != yaml.MappingNode {
		return ""
	}
	for j := 0; j+1 < len(node.Content); j += 2 {
		if node.Content[j].Value == key {
			return node.Content[j+1].Value
		}
	}
	return ""
}

// cloneNode deep-copies a YAML subtree so an edit cannot mutate the tree it was read from.
func cloneNode(node *yaml.Node) *yaml.Node {
	if node == nil {
		return nil
	}
	dup := *node
	if len(node.Content) > 0 {
		dup.Content = make([]*yaml.Node, len(node.Content))
		for i, child := range node.Content {
			dup.Content[i] = cloneNode(child)
		}
	}
	return &dup
}

// removeEmptyPath drops a mapping that ended up with no children, so adding and then deleting
// the last custom provider does not leave an empty api-keys: block behind.
func removeEmptyPath(root *yaml.Node, path []string) {
	node := lookupNode(root, path)
	if node == nil {
		return
	}
	if node.Kind == yaml.MappingNode && len(node.Content) == 0 {
		removePath(root, path)
	}
}

// SetConfigText installs a config.yaml typed or pasted by the user in the full-text editor.
//
// The editor used to write the file directly from the UI thread, which meant one stray keystroke
// could take the gateway down and, worse, that the backup written there held the NEW bytes — the
// exact inversion that made this file's recovery path destructive (a failed write left a parseable
// prefix that the next start "restored" over a good config). Everything that writes config.yaml
// goes through writeFileAtomic for the same reason: it refuses text that does not parse, stages the
// OLD bytes as .lastgood, truncates and rewrites the same inode so the engine's watcher still
// fires, and drops the backup last.
//
// Returns "" on success and a readable error otherwise; on error the file is untouched.
func SetConfigText(dataDir, text string) string {
	if err := setConfigText(dataDir, text); err != nil {
		return err.Error()
	}
	return ""
}

func setConfigText(dataDir, text string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	if strings.TrimSpace(text) == "" {
		return fmt.Errorf("配置内容为空")
	}
	body := []byte(text)
	var doc yaml.Node
	if err := yaml.Unmarshal(body, &doc); err != nil {
		return fmt.Errorf("YAML 解析失败：%w", err)
	}
	root := docRoot(&doc)
	if root == nil {
		return fmt.Errorf("YAML 没有内容")
	}
	if root.Kind != yaml.MappingNode {
		// A v8 config is a mapping. Anything else parses but the engine will not start on it, and
		// accepting it here would let the editor save the gateway into a state it cannot boot from.
		return fmt.Errorf("根节点必须是映射（实际是 %s）", kindName(root))
	}
	if lookupPath(&doc, []string{"config-version"}) == "" {
		// Not fatal on its own (the engine tolerates a legacy file), but the editor is the only
		// place a user can produce a document without it, and silently switching the schema
		// selector off is not something the settings screen should allow.
		return fmt.Errorf("缺少 config-version")
	}

	configMu.Lock()
	defer configMu.Unlock()
	path := configPath(dataDir)
	// A leftover backup means an earlier write of ours was interrupted. Recover it first: the user
	// is about to replace this file, and merging into a half-written one would carry the damage
	// forward.
	recoverConfigFromBackup(path)
	return writeFileAtomic(path, body)
}

// ---------------------------------------------------------------------------- remote rule packs
//
// The Android updater used to fetch a rules file and append it to config.yaml as a comment block,
// which changed nothing and grew the file forever; it also wrote it back by renaming, which killed
// the engine's watcher. ApplyRulePack is the supported way in: it parses the remote document and
// merges ONLY the sections listed in rulePackPaths into the local file.

// rulePackPaths is the complete list of paths a remote rule pack may set.
//
// It is a whitelist rather than a blacklist on purpose: a remote file is a complete v8 config
// (server, access, management, oauth, routing, observability), so "copy everything except these
// keys" would mean the day upstream adds a section, the remote document starts controlling it.
// Anything not named here — server.host, server.port, access.api-keys, management.*,
// oauth.auth-dir, api-keys.openai-compatibility, config-version — can never be reached from a
// rule pack, not even to "restore" it.
var rulePackPaths = [][]string{
	{"requests", "payload"},
	{"requests", "streaming"},
	{"oauth", "providers", "antigravity", "sensitive-words"},
	{"routing"},
	{"observability"},
}

// rulePackMaxBytes bounds the remote document. A rules file is a few kilobytes; anything larger
// is a download error or an attempt to fill the device.
const rulePackMaxBytes = 512 << 10

// ApplyRulePack merges the whitelisted sections of a remote v8 YAML document into config.yaml.
// Returns "" on success and a readable error otherwise; the file is untouched on error.
func ApplyRulePack(dataDir string, text string) string {
	if err := applyRulePack(dataDir, text); err != nil {
		return err.Error()
	}
	return ""
}

func applyRulePack(dataDir, text string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	if strings.TrimSpace(text) == "" {
		return fmt.Errorf("规则内容为空")
	}
	if len(text) > rulePackMaxBytes {
		return fmt.Errorf("规则内容过大：%d 字节", len(text))
	}
	var remote yaml.Node
	if err := yaml.Unmarshal([]byte(text), &remote); err != nil {
		// Return before touching anything: a rule pack that does not parse must not be able to
		// leave a half-merged config behind.
		return fmt.Errorf("解析规则失败：%w", err)
	}
	remoteRoot := docRoot(&remote)
	if remoteRoot == nil || remoteRoot.Kind != yaml.MappingNode {
		return fmt.Errorf("规则根节点不是映射（实际是 %s）", kindName(remoteRoot))
	}

	configMu.Lock()
	defer configMu.Unlock()

	path := configPath(dataDir)
	recoverConfigFromBackup(path)
	raw, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return fmt.Errorf("先启动一次网关，让 config.yaml 生成：%w", err)
		}
		return err
	}
	var doc yaml.Node
	if errUnmarshal := yaml.Unmarshal(raw, &doc); errUnmarshal != nil {
		return fmt.Errorf("解析 config.yaml 失败：%w", errUnmarshal)
	}
	root := docRoot(&doc)
	if root == nil || root.Kind != yaml.MappingNode {
		return fmt.Errorf("config.yaml 根节点不是映射（实际是 %s），没有写入", kindName(root))
	}

	merged := 0
	for _, p := range rulePackPaths {
		node := lookupNode(remoteRoot, p)
		if node == nil {
			continue
		}
		value := cloneNode(node)
		if errSanitize := sanitizeRemoteNode(value, strings.Join(p, ".")); errSanitize != nil {
			return errSanitize
		}
		if err := setPath(root, p, value); err != nil {
			return err
		}
		merged++
	}
	if merged == 0 {
		// Nothing in the pack is allowed here. Report success rather than error: a pack that only
		// carries server:/access: is exactly what the updater ships, and failing it would make the
		// app retry forever.
		return nil
	}

	out, errMarshal := yaml.Marshal(&doc)
	if errMarshal != nil {
		return errMarshal
	}
	// writeFileAtomic writes in place through writeDurable — it stages the file's current bytes
	// as .lastgood and then truncates and rewrites the same inode, never a rename, because the
	// engine's fsnotify watch is bound to the inode and a rename would silence every later reload.
	return writeFileAtomic(path, out)
}

// sanitizeRemoteNode makes a copied remote subtree safe to emit inside our own document.
//
// Anchors and aliases are the dangerous part: the remote file may reference an anchor defined in
// a section we deliberately did NOT copy, and yaml.Marshal will happily write the dangling alias,
// producing a config the engine then refuses to load ("unknown anchor ... referenced"). Anchors
// are dropped and aliases are refused outright rather than resolved, so a rule pack cannot smuggle
// a value in through an indirection this function does not understand.
func sanitizeRemoteNode(node *yaml.Node, where string) error {
	if node == nil {
		return nil
	}
	if node.Kind == yaml.AliasNode {
		return fmt.Errorf("规则 %s 里含有 YAML 别名引用，已拒绝", where)
	}
	node.Anchor = ""
	for _, child := range node.Content {
		if err := sanitizeRemoteNode(child, where); err != nil {
			return err
		}
	}
	return nil
}
