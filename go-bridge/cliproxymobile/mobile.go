// Package cliproxymobile exposes the embedded CLIProxyAPI engine to Android through
// gomobile. Every exported symbol uses only primitive types (string / bool) because
// gobind cannot marshal structs, context.Context, errors or multi-value returns.
package cliproxymobile

import (
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
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
	// thinkingLevelPath used to be written alongside includeThoughts. Measured A/B on the
	// current upstream (same prompt, max_tokens 3000, gemini-3.8-flash-high): includeThoughts
	// alone produced 3257 reasoning chars for 853 completion tokens, while adding
	// thinkingLevel:high produced 2486 chars for 1241 tokens — strictly worse, because forcing
	// effort high overrides the level the caller asked for and burns budget on easy prompts.
	// It is now only written when the user explicitly picks a level.
	thinkingLevelPath = "generationConfig.thinkingConfig.thinkingLevel"
	// modelsFileName is the corrected model catalog the host copies into filesDir.
	modelsFileName = "models.json"
	// configVersion is the schema version the config file declares. ValidateV8Config keys off
	// it, so a document missing it is not a valid v8 config even if every other key is right.
	configVersion = "8"
	// DefaultPort is the gateway port used by every generated configuration.
	DefaultPort = 8317
	// ManagementKey is the local management secret seeded into the panel.
	ManagementKey = "cpa-android-local"
	// defaultAPIKey is the local proxy key accepted by the /v1 endpoints.
	defaultAPIKey = "cpa-android-local"

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
var lastError string

// StartProxy boots the embedded CLIProxyAPI engine.
// dataDir is Android  context.filesDir.absolutePath.
// It returns "" on success and a readable error description otherwise.
func StartProxy(dataDir string) string {
	if err := startProxy(dataDir); err != nil {
		return err.Error()
	}
	return ""
}

// StopProxy gracefully stops the running gateway.
func StopProxy() string {
	if err := stopProxy(); err != nil {
		return err.Error()
	}
	return ""
}

// RestartProxy stops the gateway and starts it again against dataDir.
func RestartProxy(dataDir string) string {
	if err := stopProxy(); err != nil {
		return err.Error()
	}
	time.Sleep(150 * time.Millisecond)
	if err := startProxy(dataDir); err != nil {
		return err.Error()
	}
	return ""
}

// IsRunning reports whether the HTTP gateway is currently serving.
func IsRunning() bool {
	state.mu.Lock()
	defer state.mu.Unlock()
	return state.service != nil
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

// EnsureDefaultConfig writes the hardened default config.yaml when it is missing.
// proxyURL seeds requests.proxy-url ("" keeps a direct connection).
// allowLan binds 0.0.0.0 instead of 127.0.0.1.
func EnsureDefaultConfig(dataDir string, proxyURL string, allowLan bool) string {
	if err := ensureDefaultConfig(dataDir, proxyURL, allowLan); err != nil {
		return err.Error()
	}
	return ""
}

// UpdateQuickSettings rewrites proxy-url, the bind host, the sensitive-word list and the
// reasoning-visibility rule. The atomic rename triggers the engine file watcher (L2 hot reload).
// sensitiveWordsCSV is comma separated; an empty value restores the defaults.
func UpdateQuickSettings(dataDir string, proxyURL string, allowLan bool, sensitiveWordsCSV string, thinkingVisible bool) string {
	if err := updateQuickSettings(dataDir, proxyURL, allowLan, sensitiveWordsCSV, thinkingVisible); err != nil {
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
	state.mu.Lock()
	already := state.service != nil
	state.mu.Unlock()
	if already {
		return nil
	}

	if err := ensureDir(dataDir); err != nil {
		return fmt.Errorf("create data dir: %w", err)
	}
	if err := ensureDir(authDir(dataDir)); err != nil {
		return fmt.Errorf("create auth dir: %w", err)
	}
	if err := ensureDefaultConfig(dataDir, "", false); err != nil {
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
		return fmt.Errorf("engine did not become ready on %s: %w", listen, err)
	}

	state.mu.Lock()
	state.service = svc
	state.cancel = cancel
	state.done = done
	state.dataDir = dataDir
	state.listenAddr = listen
	lastError = ""
	state.mu.Unlock()
	return nil
}

func recordError(err error) {
	state.mu.Lock()
	lastError = err.Error()
	state.mu.Unlock()
	log.Errorf("cpa engine stopped: %v", err)
}

func stopProxy() error {
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
	return nil
}

func attachLogger(dataDir string) {
	path := filepath.Join(dataDir, "cpa-engine.log")
	if f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
		state.mu.Lock()
		state.logFile = f
		state.mu.Unlock()
		log.SetOutput(io.MultiWriter(f))
		log.SetLevel(log.InfoLevel)
		log.SetFormatter(&log.TextFormatter{DisableColors: true, FullTimestamp: true})
		return
	}
	log.SetOutput(io.Discard)
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
		yamlScalar(catalogPath(dataDir)), yamlScalar(defaultAPIKey),
		yamlScalar(ManagementKey), yamlScalar(authDir(dataDir)), strings.Join(words, "\n"))
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

func ensureDefaultConfig(dataDir, proxyURL string, allowLan bool) error {
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
		return migrateExistingConfig(path, catalogPath(dataDir))
	} else if !os.IsNotExist(err) {
		return err
	}
	return writeFileAtomic(path, []byte(renderDefaultConfig(dataDir, proxyURL, allowLan)))
}

// migrateExistingConfig brings a config written by an older build up to date without touching
// any key the user may have tuned.
//
// Two things age out: models.catalog, which an old file does not have (so the corrected model
// table never loads), and the thinkingLevel this app used to write next to includeThoughts,
// which overrides whatever effort the caller asks for. Rewriting the whole file would discard
// the user's proxy, api-keys and sensitive-word edits, so only those paths are touched, and the
// file is only rewritten when something actually changed.
func migrateExistingConfig(configPath, catalog string) error {
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
	levelChanged := stripForcedThinkingLevel(&root, []string{"requests", "payload", "override"})
	// A file that already carries a repeated banner needs a rewrite even when the keys above
	// are fine: the earlier build appended the banner on every migration, so installs updated
	// several times are sitting on several copies with nothing else to fix.
	bannerRepeated := hasRepeatedHeadComments(raw)
	if !versionChanged && !catalogChanged && !levelChanged && !bannerRepeated {
		return nil
	}
	// setPath works on a mapping node. Unmarshalling produces a DocumentNode wrapping it, and
	// handing the document straight to setPath makes it see "not a map" and replace the whole
	// tree with an empty one — that wiped a live config down to a single key, so the engine
	// then started on ":0" because server.host and server.port were gone.
	doc := &root
	if doc.Kind == yaml.DocumentNode && len(doc.Content) > 0 {
		doc = doc.Content[0]
	}
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

func updateQuickSettings(dataDir, proxyURL string, allowLan bool, sensitiveWordsCSV string, thinkingVisible bool) error {
	if strings.TrimSpace(dataDir) == "" {
		return fmt.Errorf("dataDir is required")
	}
	path := configPath(dataDir)
	if _, err := os.Stat(path); err != nil {
		if !os.IsNotExist(err) {
			return err
		}
		if err := ensureDefaultConfig(dataDir, proxyURL, allowLan); err != nil {
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

	applyThinkingVisibility(root, thinkingVisible)

	out, err := yaml.Marshal(&doc)
	if err != nil {
		return err
	}
	return writeFileAtomic(path, out)
}

// applyThinkingVisibility turns the reasoning-visibility payload rule on or off.
//
// Showing the chain of thought costs thought tokens on every request, so it is a user choice
// rather than something baked into the build. Implemented as a requests.payload.override rule
// so the behaviour is visible in config.yaml and survives an upstream sync.
//
// includeThoughts is written alone. An earlier build also forced thinkingLevel: high on the
// theory that visibility without effort gets dropped; measured A/B on the current upstream shows
// the opposite — the pair costs more tokens and returns less reasoning, and hard-coding "high"
// silently overrides whatever effort the caller requested. A caller that wants a specific level
// still gets it, because nothing here overwrites generationConfig.thinkingConfig.thinkingLevel.
func applyThinkingVisibility(root *yaml.Node, visible bool) {
	path := []string{"requests", "payload", "override"}
	if !visible {
		// Drop only the rule this function owns; leave any user rule alone.
		removeThinkingRule(root, path)
		return
	}
	if hasThinkingRule(root, path) {
		// Configs written by an earlier build also carried thinkingLevel: high. Strip it so an
		// upgrade does not keep overriding the caller's effort — otherwise the fixed behaviour
		// only reaches freshly generated configs and existing installs stay on the old behaviour.
		stripForcedThinkingLevel(root, path)
		return
	}
	rule := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map"}
	models := &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq", Content: []*yaml.Node{
		{Kind: yaml.MappingNode, Tag: "!!map", Content: []*yaml.Node{
			{Kind: yaml.ScalarNode, Tag: "!!str", Value: "name"},
			{Kind: yaml.ScalarNode, Tag: "!!str", Value: "*"},
		}},
	}}
	params := &yaml.Node{Kind: yaml.MappingNode, Tag: "!!map", Content: []*yaml.Node{
		{Kind: yaml.ScalarNode, Tag: "!!str", Value: thinkingParamPath},
		{Kind: yaml.ScalarNode, Tag: "!!bool", Value: "true"},
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

// hasThinkingRule reports whether one of the override rules already sets includeThoughts.
func hasThinkingRule(root *yaml.Node, path []string) bool {
	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		return false
	}
	for _, rule := range seq.Content {
		if rule.Kind != yaml.MappingNode {
			continue
		}
		if params := lookupNode(rule, []string{"params"}); params != nil {
			// The param key is a literal dotted path ("generationConfig.thinkingConfig.
			// includeThoughts"), not a nested tree, so it has to be matched as one scalar.
			if lookupNode(params, []string{thinkingParamPath}) != nil {
				return true
			}
		}
	}
	return false
}

// stripForcedThinkingLevel removes the thinkingLevel our earlier builds hard-coded, leaving
// the visibility flag and any level the user set themselves.
//
// Only a rule that carries includeThoughts is touched: a user's own scale rule that sets just a
// level is not ours to edit.
func stripForcedThinkingLevel(root *yaml.Node, path []string) bool {
	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		return false
	}
	changed := false
	for _, rule := range seq.Content {
		if rule.Kind != yaml.MappingNode {
			continue
		}
		params := lookupNode(rule, []string{"params"})
		if params == nil {
			continue
		}
		if lookupNode(params, []string{thinkingParamPath}) == nil {
			continue
		}
		if lookupNode(params, []string{thinkingLevelPath}) == nil {
			continue
		}
		removePath(params, []string{thinkingLevelPath})
		changed = true
	}
	return changed
}

// removeThinkingRule deletes just the rule that sets includeThoughts.
func removeThinkingRule(root *yaml.Node, path []string) {
	seq := lookupNode(root, path)
	if seq == nil || seq.Kind != yaml.SequenceNode {
		return
	}
	kept := seq.Content[:0]
	for _, rule := range seq.Content {
		owned := false
		if rule.Kind == yaml.MappingNode {
			if params := lookupNode(rule, []string{"params"}); params != nil {
				owned = lookupNode(params, []string{thinkingParamPath}) != nil
			}
		}
		if !owned {
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
