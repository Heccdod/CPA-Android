// Package cliproxymobile exposes the embedded CLIProxyAPI engine to Android through
// gomobile. Every exported symbol uses only primitive types (string / bool) because
// gobind cannot marshal structs, context.Context, errors or multi-value returns.
package cliproxymobile

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
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
	// legacyAPIKey is what earlier builds wrote. It is still accepted so an upgraded install does
	// not lose the client that was already configured against it, and is replaced on the next
	// settings write.
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
	dataDir    string
	listenAddr string
	logFile    *os.File
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
	time.Sleep(150 * time.Millisecond)
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
// reasoning-visibility rule. The atomic rename triggers the engine file watcher (L2 hot reload).
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
	state.mu.Lock()
	// A nil service means nothing is running. Do not treat a live-looking pointer as "already
	// started" without checking: recordError clears it on failure, and the probe below catches
	// the case where the engine died without going through recordError at all.
	already := state.service != nil && probeListen(state.listenAddr, livenessTimeout) == nil
	if already {
		state.mu.Unlock()
		return nil
	}
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
		if staleLog != nil {
			_ = staleLog.Close()
		}
	}

	if err := ensureDir(dataDir); err != nil {
		return fmt.Errorf("create data dir: %w", err)
	}
	if err := ensureDir(authDir(dataDir)); err != nil {
		return fmt.Errorf("create auth dir: %w", err)
	}
	if err := ensureDefaultConfig(dataDir, "", false, ""); err != nil {
		return err
	}

	path := configPath(dataDir)
	cfg, err := sdkconfig.LoadConfig(path)
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}
	if cfg == nil {
		return fmt.Errorf("load config: empty result")
	}
	cfg.AuthDir = authDir(dataDir)

	listen := net.JoinHostPort(bindHost(cfg.Host), itoa(bindPort(cfg.Port)))

	svc, err := cliproxy.NewBuilder().
		WithConfig(cfg).
		WithConfigPath(path).
		WithLocalManagementPassword(ManagementKey).
		Build()
	if err != nil {
		return fmt.Errorf("build engine: %w", err)
	}

	attachLogger(dataDir)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		if errRun := svc.Run(ctx); errRun != nil && !strings.Contains(errRun.Error(), "context canceled") {
			recordError(errRun)
		}
	}()

	if err := waitForListen(listen, readyTimeout); err != nil {
		cancel()
		<-done
		// The start failed, so the logger this attempt attached is no longer wanted. Closing it
		// here (rather than waiting for the next start to overwrite it) keeps the descriptor count
		// flat across repeated failures, which is what a flapping network produces.
		state.mu.Lock()
		if state.logFile != nil {
			_ = state.logFile.Close()
			state.logFile = nil
		}
		state.mu.Unlock()
		return fmt.Errorf("engine did not become ready on %s: %w", listen, err)
	}

	state.mu.Lock()
	atomic.AddInt64(&builds, 1)
	state.service = svc
	state.cancel = cancel
	state.done = done
	state.dataDir = dataDir
	state.listenAddr = listen
	lastError = ""
	state.mu.Unlock()
	return nil
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
func recordError(err error) {
	state.mu.Lock()
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
	state.mu.Unlock()

	if svc == nil || cancel == nil {
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
		if logFile != nil {
			_ = logFile.Close()
		}
		return fmt.Errorf("engine shutdown timed out after %s", stopTimeout)
	}
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
	deadline := time.Now().Add(timeout)
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
	if thinkingMode != "" && doc.Kind == yaml.MappingNode {
		before, _ := yaml.Marshal(&root)
		applyThinkingVisibility(doc, thinkingMode)
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
		setPath(doc, []string{"config-version"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!int", Value: configVersion})
	}
	if catalogChanged {
		value := &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: catalog}
		setPath(doc, []string{"models", "catalog"}, value)
	}
	out, errMarshal := yaml.Marshal(&root)
	if errMarshal != nil {
		return errMarshal
	}
	// yaml.v3 keeps the document's head comment, so marshalling already writes the banner
	// back. Prepending headComments(raw) on top of that duplicated it once per migration —
	// a config touched four times carried four copies of the same five-line banner, which
	// is exactly what the in-app editor showed. collapseDuplicateHeadComments also repairs
	// files that already accumulated copies.
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

// headComments returns the leading comment lines of a YAML file, newline-terminated.
func headComments(raw []byte) []byte {
	var out []byte
	for _, line := range strings.Split(string(raw), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "#") || trimmed == "" {
			out = append(out, line...)
			out = append(out, '\n')
			continue
		}
		break
	}
	return out
}

func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := ensureDir(dir); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".config-*.yaml")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() { _ = os.Remove(tmpName) }()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tmpName, 0o600); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

func updateQuickSettings(dataDir, proxyURL string, allowLan bool, sensitiveWordsCSV string, thinkingMode string) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	path := configPath(dataDir)
	if _, err := os.Stat(path); err != nil {
		if !os.IsNotExist(err) {
			return err
		}
		if err := ensureDefaultConfig(dataDir, proxyURL, allowLan, thinkingMode); err != nil {
			return err
		}
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(raw, &doc); err != nil {
		return fmt.Errorf("parse config: %w", err)
	}
	if len(doc.Content) == 0 {
		if err := yaml.Unmarshal([]byte("config-version: 8\n"), &doc); err != nil {
			return err
		}
	}
	root := doc.Content[0]

	host := loopbackHost
	if allowLan {
		host = anyHost
	}
	setPath(root, []string{"server", "host"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: host})
	setPath(root, []string{"requests", "proxy-url"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: strings.TrimSpace(proxyURL)})
	setPath(root, []string{"oauth", "auth-dir"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: authDir(dataDir)})

	seq := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
	for _, w := range parseSensitiveWords(sensitiveWordsCSV) {
		seq.Content = append(seq.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: w})
	}
	setPath(root, []string{"oauth", "providers", "antigravity", "sensitive-words"}, seq)

	applyThinkingVisibility(root, thinkingMode)

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
func applyThinkingVisibility(root *yaml.Node, mode string) {
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
		return
	}

	rule := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
	models := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq", Content: []*yaml.Node{
		{Kind: yaml.MappingNode, Tag: "!!map", Content: []*yaml.Node{
			{Kind: yaml.ScalarNode, Tag: "!!str", Value: "name"},
			{Kind: yaml.ScalarNode, Tag: "!!str", Value: "*"},
		}},
	}}
	setPath(rule, []string{"models"}, models)
	setPath(rule, []string{"params"}, params)

	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		seq = &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		setPath(root, path, seq)
	}
	seq.Content = append(seq.Content, rule)
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

func setPath(root *yaml.Node, path []string, value *yaml.Node) {
	if root == nil {
		return
	}
	if root.Kind != yaml.MappingNode {
		root.Kind = yaml.MappingNode
		root.Tag = "!!map"
		root.Content = nil
	}
	cur := root
	for i, key := range path {
		last := i == len(path)-1
		var child *yaml.Node
		for j := 0; j+1 < len(cur.Content); j += 2 {
			if cur.Content[j].Value == key {
				child = cur.Content[j+1]
				break
			}
		}
		if last {
			if child != nil {
				*child = *value
				return
			}
			cur.Content = append(cur.Content,
				&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, value)
			return
		}
		if child == nil {
			child = &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
			cur.Content = append(cur.Content,
				&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, child)
		} else if child.Kind != yaml.MappingNode {
			*child = yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
		}
		cur = child
	}
}
