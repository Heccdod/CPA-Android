package cliproxymobile

import (
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/router-for-me/CLIProxyAPI/v8/sdk/cliproxy"
	sdkconfig "github.com/router-for-me/CLIProxyAPI/v8/sdk/config"
	log "github.com/sirupsen/logrus"
	"gopkg.in/yaml.v3"
)

// auditConfig writes src verbatim as config.yaml in a fresh dir and returns the dir.
func auditConfig(t *testing.T, src string) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, ConfigFileName), []byte(src), 0o600); err != nil {
		t.Fatal(err)
	}
	return dir
}

func auditRead(t *testing.T, dir string) string {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	return string(raw)
}

// --------------------------------------------------------------------- 1. setPath keeps anchors

// A document that anchors a value and references it elsewhere must survive a settings write. The
// old overwrite (*child = *value) stripped the anchor and left the alias dangling, so the file the
// app had just written was one the engine refused to load: "unknown anchor 'h' referenced".
func TestSetPathKeepsAnchorAndAliasValid(t *testing.T) {
	dir := auditConfig(t, "config-version: 8\nserver:\n  host: &h 127.0.0.1  # the loopback anchor\n  port: 8317\nproxy-copy: *h\n")

	if msg := UpdateQuickSettings(dir, "", true, "w", ThinkingCompact); msg != "" {
		t.Fatalf("UpdateQuickSettings: %s", msg)
	}
	body := auditRead(t, dir)
	var probe yaml.Node
	if err := yaml.Unmarshal([]byte(body), &probe); err != nil {
		t.Fatalf("the writer emitted a document its own parser rejects: %v\n%s", err, body)
	}
	if _, err := sdkconfig.LoadConfig(filepath.Join(dir, ConfigFileName)); err != nil {
		t.Fatalf("engine rejects the written config: %v\n%s", err, body)
	}
	var cfg struct {
		Server struct {
			Host string `yaml:"host"`
		} `yaml:"server"`
		ProxyCopy string `yaml:"proxy-copy"`
	}
	if err := yaml.Unmarshal([]byte(body), &cfg); err != nil {
		t.Fatal(err)
	}
	if cfg.Server.Host != anyHost {
		t.Fatalf("server.host = %q, want the value just written (%q)", cfg.Server.Host, anyHost)
	}
	if cfg.ProxyCopy != anyHost {
		t.Fatalf("the alias resolved to %q, want it to follow the anchor", cfg.ProxyCopy)
	}
}

// Comments next to an overwritten value used to disappear with it. They are the only thing that
// explains why a key holds what it holds, so they have to survive the write.
func TestSetPathKeepsComments(t *testing.T) {
	var doc yaml.Node
	src := "server:\n  # bound to loopback on purpose\n  host: 127.0.0.1  # only this phone\n  port: 8317\n"
	if err := yaml.Unmarshal([]byte(src), &doc); err != nil {
		t.Fatal(err)
	}
	root := docRoot(&doc)
	if err := setPath(root, []string{"server", "host"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "0.0.0.0"}); err != nil {
		t.Fatalf("setPath: %v", err)
	}
	out, err := yaml.Marshal(&doc)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"only this phone", "bound to loopback on purpose"} {
		if !strings.Contains(string(out), want) {
			t.Fatalf("comment %q was dropped:\n%s", want, out)
		}
	}
	if !strings.Contains(string(out), "0.0.0.0") {
		t.Fatalf("the value was not written:\n%s", out)
	}
}

// --------------------------------------------------------------------- 2. root / intermediate guards

// A config whose root is not a mapping must be refused, not replaced by the keys this screen
// happens to write. That failure mode silently deleted every setting the user had.
func TestUpdateQuickSettingsRefusesNonMappingRoot(t *testing.T) {
	for _, src := range []string{"- a\n- b\n- c\n", "just a scalar\n", "!!binary aGVsbG8=\n"} {
		dir := auditConfig(t, src)
		msg := UpdateQuickSettings(dir, "http://proxy", true, "w", ThinkingCompact)
		if msg == "" {
			t.Fatalf("a non-mapping root was accepted: %q", src)
		}
		if got := auditRead(t, dir); got != src {
			t.Fatalf("the file was rewritten anyway:\nbefore=%q\nafter=%q", src, got)
		}
	}
}

// "server: []" is a broken file, and overwriting it loses whatever the user had there.
func TestSetPathRefusesNonMappingIntermediate(t *testing.T) {
	var doc yaml.Node
	if err := yaml.Unmarshal([]byte("config-version: 8\nserver: []\n"), &doc); err != nil {
		t.Fatal(err)
	}
	err := setPath(docRoot(&doc), []string{"server", "host"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "x"})
	if err == nil {
		t.Fatal("setPath flattened a sequence in the middle of a path")
	}
	// The empty-key spelling the default template uses ("payload:" with nothing under it) still
	// has to work; making it an error would break every fresh install.
	var fresh yaml.Node
	if err := yaml.Unmarshal([]byte("config-version: 8\nserver:\n"), &fresh); err != nil {
		t.Fatal(err)
	}
	if err := setPath(docRoot(&fresh), []string{"server", "host"}, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "127.0.0.1"}); err != nil {
		t.Fatalf("an empty key is not a broken one: %v", err)
	}
}

// --------------------------------------------------------------------- 3. .lastgood semantics

// The backup holds the file's PREVIOUS bytes. A half-written backup (the process died while
// staging it) must never be installed over a readable config — that turned a crash into data loss.
func TestPartialBackupDoesNotOverwriteGoodConfig(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	good, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	// Two ways a staged copy can be useless: cut off mid-document, and syntactically fine but
	// something the engine refuses.
	for _, broken := range [][]byte{
		good[:len(good)*2/3],
		[]byte("config-version: 8\napi-keys:\n  openai-compatibility:\n    - name: a\n"),
	} {
		if err := os.WriteFile(path+configBackupSuffix, broken, 0o600); err != nil {
			t.Fatal(err)
		}
		if recoverConfigFromBackup(path) {
			t.Fatalf("an unusable backup was restored (%d bytes)", len(broken))
		}
		after, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if string(after) != string(good) {
			t.Fatalf("the good config was overwritten by a broken backup")
		}
		if _, err := os.Stat(path + configBackupSuffix); !os.IsNotExist(err) {
			t.Fatal("an unusable backup was left behind to be retried forever")
		}
	}
}

// A good backup is still restored, and the recovery reaches the startup path.
func TestGoodBackupIsRestoredOnStart(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "socks5://127.0.0.1:1080", true, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	good, _ := os.ReadFile(path)
	cut := strings.Index(string(good), "access:")
	if cut < 0 {
		t.Fatal("no access block in the generated config")
	}
	// A crash between "stage the old bytes" and "truncate the live file".
	if err := os.WriteFile(path+configBackupSuffix, good, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(good[:cut]), 0o600); err != nil {
		t.Fatal(err)
	}

	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	// Byte equality is not the contract: the startup path migrates whatever it finds (that is what
	// ensureDefaultConfig is for), so compare the settings the truncated file had lost.
	for _, want := range []string{"socks5://127.0.0.1:1080", "access:", "api-keys:"} {
		if !strings.Contains(string(after), want) {
			t.Fatalf("startup did not restore the staged copy (%q missing):\n%s", want, after)
		}
	}
	if _, err := os.Stat(path + configBackupSuffix); !os.IsNotExist(err) {
		t.Fatal("the consumed backup was left behind")
	}
	if _, err := sdkconfig.LoadConfig(path); err != nil {
		t.Fatalf("restored config does not load: %v", err)
	}
}

// Reading the settings must not write to disk. It used to run the recovery, so opening the
// provider editor could replace a good config with a stale backup.
func TestReadsDoNotTouchConfigFile(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	good, _ := os.ReadFile(path)
	stale := []byte("config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 1\n")
	if err := os.WriteFile(path+configBackupSuffix, stale, 0o600); err != nil {
		t.Fatal(err)
	}
	_ = GetCustomProviders(dir)
	_ = ClientAPIKey(dir)
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != string(good) {
		t.Fatalf("a read path rewrote config.yaml:\n%s", after)
	}
}

// writeFileAtomic refuses to install a document the engine cannot read.
func TestWriteFileAtomicRejectsUnparsableData(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ConfigFileName)
	if err := os.WriteFile(path, []byte("config-version: 8\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, bad := range [][]byte{[]byte(""), []byte("   \n"), []byte("a: [1,\n")} {
		if err := writeFileAtomic(path, bad); err == nil {
			t.Fatalf("wrote %q", bad)
		}
	}
	if got, _ := os.ReadFile(path); string(got) != "config-version: 8\n" {
		t.Fatalf("a rejected write changed the file: %q", got)
	}
}

// --------------------------------------------------------------------- 4. write concurrency

// The two writers both read the whole file, edit and write it back. Interleaved they lose one of
// the edits — a provider saved from the settings screen vanished on the next settings write.
func TestConcurrentConfigWritersDoNotLoseUpdates(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	payload, err := json.Marshal(customProviderList{Providers: []customProvider{{
		Name: "keepme", BaseURL: "https://a.example/v1", Keys: []string{"k"},
		Models: []customProviderModel{{Name: "m"}},
	}}})
	if err != nil {
		t.Fatal(err)
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 60; i++ {
			if msg := UpdateQuickSettings(dir, "", false, "w", ThinkingCompact); msg != "" {
				t.Errorf("UpdateQuickSettings: %s", msg)
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 60; i++ {
			if msg := SetCustomProviders(dir, string(payload)); msg != "" {
				t.Errorf("SetCustomProviders: %s", msg)
				return
			}
		}
	}()
	wg.Wait()

	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(body), "keepme") {
		t.Fatalf("the provider block was lost by a concurrent settings write:\n%s", body)
	}
	if _, err := sdkconfig.LoadConfig(path); err != nil {
		t.Fatalf("concurrent writes left an unloadable config: %v", err)
	}
}

// --------------------------------------------------------------------- 5. keyless providers

// A local llama.cpp/ollama endpoint has no key. The engine's v8 expansion requires the keys node
// to be a list, so an absent one made the whole config unloadable and the gateway would not start.
func TestKeylessProviderKeepsConfigLoadable(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	payload, err := json.Marshal(customProviderList{Providers: []customProvider{{
		Name: "ollama", BaseURL: "http://127.0.0.1:11434/v1",
		Models: []customProviderModel{{Name: "llama3"}},
	}}})
	if err != nil {
		t.Fatal(err)
	}
	if msg := SetCustomProviders(dir, string(payload)); msg != "" {
		t.Fatalf("save rejected: %s", msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	cfg, err := sdkconfig.LoadConfig(path)
	if err != nil {
		t.Fatalf("engine rejects a keyless provider: %v", err)
	}
	if len(cfg.OpenAICompatibility) != 1 || cfg.OpenAICompatibility[0].BaseURL != "http://127.0.0.1:11434/v1" {
		t.Fatalf("engine did not see the provider: %+v", cfg.OpenAICompatibility)
	}
	// And the gateway actually comes up on it.
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()
	if !IsRunning() {
		t.Fatal("gateway not serving with a keyless provider configured")
	}
}

// A case-only rename is a rename, not a new provider: fields this screen does not edit survive.
func TestCaseOnlyRenameKeepsUntouchedFields(t *testing.T) {
	dir := auditConfig(t, "config-version: 8\n"+
		"api-keys:\n  openai-compatibility:\n"+
		"    - name: \"DeepSeek\"\n      base-url: \"https://api.deepseek.com/v1\"\n"+
		"      priority: 9\n      headers:\n        X-Keep: me\n"+
		"      keys:\n        - api-key: \"sk-1\"\n"+
		"      models:\n        - name: \"deepseek-chat\"\n          display-name: \"Chat\"\n")

	var list customProviderList
	if err := json.Unmarshal([]byte(GetCustomProviders(dir)), &list); err != nil {
		t.Fatal(err)
	}
	if list.Error != "" {
		t.Fatal(list.Error)
	}
	list.Providers[0].Name = "deepseek"
	payload, _ := json.Marshal(list)
	if msg := SetCustomProviders(dir, string(payload)); msg != "" {
		t.Fatalf("save: %s", msg)
	}
	body := auditRead(t, dir)
	var doc yaml.Node
	if err := yaml.Unmarshal([]byte(body), &doc); err != nil {
		t.Fatal(err)
	}
	group := lookupNode(docRoot(&doc), []string{"api-keys", "openai-compatibility"})
	if group == nil || len(group.Content) != 1 {
		t.Fatalf("expected exactly one group:\n%s", body)
	}
	g := group.Content[0]
	if got := scalarAt(g, "priority"); got != "9" {
		t.Fatalf("priority = %q, want 9", got)
	}
	if headers := lookupNode(g, []string{"headers"}); headers == nil || scalarAt(headers, "X-Keep") != "me" {
		t.Fatalf("headers lost on a case-only rename:\n%s", body)
	}
}

// --------------------------------------------------------------------- 6. engine generations

// The goroutine of an engine that was already replaced must not touch the live one. Its late
// recordError used to clear the pointer and write "engine died", so the UI said 异常 while the
// gateway was still serving.
func TestStaleGenerationCannotKillLiveEngine(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()

	state.mu.Lock()
	stale := state.generation - 1
	state.mu.Unlock()
	recordError(stale, errors.New("stale engine died"))

	if got := Status(); got != StatusRunning {
		t.Fatalf("status = %q after a stale failure, want %q", got, StatusRunning)
	}
	if !IsRunning() {
		t.Fatal("the live engine stopped serving")
	}
}

// The generation that IS installed still reports: a real failure has to surface.
func TestCurrentGenerationStillRecordsFailure(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()
	SetDesiredRunning(true)

	state.mu.Lock()
	cur := state.generation
	state.mu.Unlock()
	recordError(cur, errors.New("gateway exploded"))

	if got := GetLastError(); got != "gateway exploded" {
		t.Fatalf("lastError = %q, want the failure recorded", got)
	}
	if got := Status(); got != StatusError {
		t.Fatalf("status = %q, want %q", got, StatusError)
	}
}

// --------------------------------------------------------------------- 7. start failure is visible

// A start that cannot even create its data directory left lastError empty, so Status() answered
// "starting" forever and the UI never offered the error.
func TestFailedStartRecordsError(t *testing.T) {
	base := t.TempDir()
	blocked := filepath.Join(base, "filesDir")
	if err := os.WriteFile(blocked, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if msg := StartProxy(blocked); msg == "" {
		t.Fatal("StartProxy reported success on an unusable dataDir")
	}
	defer StopProxy()
	if strings.TrimSpace(GetLastError()) == "" {
		t.Fatal("no lastError was recorded for a failed start")
	}
	if got := Status(); got != StatusError {
		t.Fatalf("status = %q, want %q", got, StatusError)
	}
}

// --------------------------------------------------------------------- 8. readiness budget

// The listener probe and the model poll each used to start their own deadline from the full
// timeout, so a start that never becomes ready took twice as long to fail.
func TestReadinessUsesOneBudget(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"data":[]}`)
	}))
	defer srv.Close()

	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "cred.json"), []byte("{}"), 0o600); err != nil {
		t.Fatal(err)
	}
	addr := strings.TrimPrefix(srv.URL, "http://")
	const budget = 600 * time.Millisecond
	start := time.Now()
	err := waitForReady(addr, budget, readinessProbe{apiKey: "k", authDir: dir})
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("waitForReady returned nil while the model list stayed empty")
	}
	if elapsed > 2*budget {
		t.Fatalf("waited %s for a %s budget: the two halves are not sharing one deadline", elapsed, budget)
	}
}

// --------------------------------------------------------------------- 9. logger lifetime

// Closing the log file has to detach logrus first, or every later line writes into a closed
// descriptor and disappears into a stderr nobody on Android can see.
func TestStopDetachesLogger(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	if msg := StopProxy(); msg != "" {
		t.Fatalf("StopProxy: %s", msg)
	}
	state.mu.Lock()
	open := state.logFile
	state.mu.Unlock()
	if open != nil {
		t.Fatal("stop left the log file handle installed")
	}
	if log.StandardLogger().Out != io.Discard {
		t.Fatal("logrus still points at a closed file after stop")
	}
}

// --------------------------------------------------------------------- 10. thinking rule

// With requests.payload.override missing (or shaped like a mapping), setPath copies the struct, so
// appending to the local node wrote the rule into a node the document does not hold: the mode
// switch silently did nothing.
func TestThinkingRuleWrittenWhenOverrideMissingOrWrong(t *testing.T) {
	for _, src := range []string{
		"config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n",
		"config-version: 8\nrequests:\n  payload:\n    override: {}\n",
	} {
		dir := auditConfig(t, src)
		if msg := UpdateQuickSettings(dir, "", false, "w", ThinkingCompact); msg != "" {
			t.Fatalf("update: %s", msg)
		}
		var doc yaml.Node
		if err := yaml.Unmarshal([]byte(auditRead(t, dir)), &doc); err != nil {
			t.Fatal(err)
		}
		seq := lookupNode(docRoot(&doc), []string{"requests", "payload", "override"})
		if seq == nil || seq.Kind != yaml.SequenceNode || len(seq.Content) == 0 {
			t.Fatalf("the compact rule was not written for config %q", src)
		}
	}
}

// --------------------------------------------------------------------- 11. ClientAPIKey

func TestClientAPIKeyReadsAccessKeys(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	key := ClientAPIKey(dir)
	if key == "" {
		t.Fatal("no client key returned for a freshly generated config")
	}
	if key == GetManagementKey() {
		t.Fatal("ClientAPIKey returned the management key; /v1 rejects it")
	}
	if !strings.HasPrefix(key, defaultAPIKeyPrefix) {
		t.Fatalf("client key = %q, want the generated prefix", key)
	}
	if got := ClientAPIKey(t.TempDir()); got != "" {
		t.Fatalf("a missing config returned %q", got)
	}
	if got := ClientAPIKey(""); got != "" {
		t.Fatalf("an empty dataDir returned %q", got)
	}

	// The key it returns is the one the engine accepts.
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()
	resp, err := http.Get("http://" + GetListenAddress() + "/v1/models")
	if err != nil {
		t.Fatal(err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("unauthenticated /v1/models = %d, want 401", resp.StatusCode)
	}
	req, _ := http.NewRequest(http.MethodGet, "http://"+GetListenAddress()+"/v1/models", nil)
	req.Header.Set("Authorization", "Bearer "+key)
	authed, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = authed.Body.Close()
	if authed.StatusCode != http.StatusOK {
		t.Fatalf("ClientAPIKey's key rejected by /v1/models: %d", authed.StatusCode)
	}
}

// --------------------------------------------------------------------- 12. ApplyRulePack

func rulePackServer() *httptest.Server {
	// Only used so the tests have a listener address helper; not required by the merge itself.
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
}

// The whitelist is the whole contract: a remote pack carries a full v8 config, and the parts that
// decide who may reach the gateway must not be reachable through it.
func TestApplyRulePackIgnoresNonWhitelistedKeys(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	before, _ := os.ReadFile(path)
	localKey := ClientAPIKey(dir)
	if localKey == "" {
		t.Fatal("no local client key to protect")
	}

	remote := "" +
		"server:\n  host: 0.0.0.0\n  port: 9999\n" +
		"access:\n  api-keys:\n    - attacker-key\n" +
		"management:\n  secret-key: pwn\n  allow-remote: true\n" +
		"oauth:\n  auth-dir: /tmp/elsewhere\n" +
		"api-keys:\n  openai-compatibility:\n    - name: evil\n      base-url: https://evil/v1\n" +
		"config-version: 7\n" +
		"observability:\n  logs:\n    debug: true\n"

	if msg := ApplyRulePack(dir, remote); msg != "" {
		t.Fatalf("ApplyRulePack: %s", msg)
	}

	if got := ClientAPIKey(dir); got != localKey {
		t.Fatalf("access.api-keys was replaced: %q -> %q", localKey, got)
	}
	var cfg struct {
		Server struct {
			Host string `yaml:"host"`
			Port int    `yaml:"port"`
		} `yaml:"server"`
		Management struct {
			SecretKey   string `yaml:"secret-key"`
			AllowRemote bool   `yaml:"allow-remote"`
		} `yaml:"management"`
		OAuth struct {
			AuthDir string `yaml:"auth-dir"`
		} `yaml:"oauth"`
		ConfigVersion string `yaml:"config-version"`
		Observability struct {
			Logs struct {
				Debug bool `yaml:"debug"`
			} `yaml:"logs"`
		} `yaml:"observability"`
		APIKeys map[string]any `yaml:"api-keys"`
	}
	body, _ := os.ReadFile(path)
	if err := yaml.Unmarshal(body, &cfg); err != nil {
		t.Fatalf("merged config does not parse: %v\n%s", err, body)
	}
	if cfg.Server.Host != loopbackHost || cfg.Server.Port != DefaultPort {
		t.Fatalf("server was rewritten: %+v", cfg.Server)
	}
	if cfg.Management.SecretKey != ManagementKey || cfg.Management.AllowRemote {
		t.Fatalf("management was rewritten: %+v", cfg.Management)
	}
	if cfg.OAuth.AuthDir != authDir(dir) {
		t.Fatalf("oauth.auth-dir = %q", cfg.OAuth.AuthDir)
	}
	if cfg.ConfigVersion != configVersion {
		t.Fatalf("config-version = %q", cfg.ConfigVersion)
	}
	if len(cfg.APIKeys) != 0 {
		t.Fatalf("api-keys.openai-compatibility was injected (baseline had %d bytes, now %d):\n%s",
			len(before), len(body), body)
	}
	// ...and the whitelisted part did land.
	if !cfg.Observability.Logs.Debug {
		t.Fatalf("observability (whitelisted) was not merged:\n%s", body)
	}
	if _, err := sdkconfig.LoadConfig(path); err != nil {
		t.Fatalf("engine rejects the merged config: %v", err)
	}
}

// requests.payload / streaming / sensitive-words are the sections the updater actually ships.
func TestApplyRulePackMergesWhitelistedSections(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	remote := "" +
		"requests:\n  payload:\n    override:\n      - models:\n          - name: \"gpt-*\"\n        params:\n          temperature: 0.2\n" +
		"  streaming:\n    keepalive-seconds: 30\n" +
		"oauth:\n  providers:\n    antigravity:\n      sensitive-words:\n        - remote-word\n        - another-word\n"

	if msg := ApplyRulePack(dir, remote); msg != "" {
		t.Fatalf("ApplyRulePack: %s", msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	body, _ := os.ReadFile(path)

	cfg, err := sdkconfig.LoadConfig(path)
	if err != nil {
		t.Fatalf("engine rejects the merged config: %v", err)
	}
	if got := cfg.Streaming.KeepAliveSeconds; got != 30 {
		t.Fatalf("streaming keepalive = %d, want 30\n%s", got, body)
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(body, &doc); err != nil {
		t.Fatal(err)
	}
	words := lookupNode(docRoot(&doc), []string{"oauth", "providers", "antigravity", "sensitive-words"})
	if words == nil || words.Kind != yaml.SequenceNode || len(words.Content) != 2 {
		t.Fatalf("sensitive-words not replaced:\n%s", body)
	}
	if words.Content[0].Value != "remote-word" {
		t.Fatalf("sensitive-words[0] = %q", words.Content[0].Value)
	}
	payload := lookupNode(docRoot(&doc), []string{"requests", "payload"})
	if payload == nil {
		t.Fatalf("requests.payload missing:\n%s", body)
	}
}

// A pack that cannot be parsed must leave the file byte-for-byte unchanged.
func TestApplyRulePackRejectsBadInput(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"not yaml":        "a: [1,\n",
		"sequence":        "- requests\n- payload\n",
		"scalar":          "just a string\n",
		"empty":           "   \n",
		"undefined alias": "requests:\n  payload: *nope\n",
		// An alias that points at an anchor INSIDE the pack: the anchor is dropped while merging,
		// so carrying the reference over would emit "unknown anchor" into the local file.
		"internal alias": "requests:\n  payload: &p\n    override: []\n  streaming: *p\n",
	}
	for name, text := range cases {
		if msg := ApplyRulePack(dir, text); msg == "" {
			t.Fatalf("%s: accepted %q", name, text)
		}
		after, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if string(after) != string(before) {
			t.Fatalf("%s: a rejected pack still rewrote the file", name)
		}
	}
	if msg := ApplyRulePack(dir, ""); msg == "" {
		t.Fatal("an empty pack was accepted")
	}
	if msg := ApplyRulePack(filepath.Join(dir, "nope"), "observability: {}\n"); msg == "" {
		t.Fatal("a missing config was accepted")
	}
}

// A pack whose only content is a non-whitelisted section is a no-op that succeeds: the updater
// ships exactly such files, and failing them would make the app retry forever.
func TestApplyRulePackWithoutWhitelistedSectionsIsNoop(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	before, _ := os.ReadFile(path)
	if msg := ApplyRulePack(dir, "server:\n  port: 1\naccess:\n  api-keys:\n    - x\n"); msg != "" {
		t.Fatalf("ApplyRulePack: %s", msg)
	}
	after, _ := os.ReadFile(path)
	if string(after) != string(before) {
		t.Fatalf("a pack with nothing to merge rewrote the file:\n%s", after)
	}
}

var _ = net.JoinHostPort
var _ = rulePackServer

// --------------------------------------------------------------------- 13. SetConfigText

// The editor's save path must refuse text the engine cannot boot from, and leave the file alone
// when it does.
func TestSetConfigTextRejectsBadInput(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"empty":         "   \n",
		"broken yaml":   "config-version: 8\nserver: [1,\n",
		"sequence root": "- config-version: 8\n",
		"scalar root":   "just a string\n",
		"no version":    "server:\n  host: 127.0.0.1\n",
	}
	for name, text := range cases {
		if msg := SetConfigText(dir, text); msg == "" {
			t.Fatalf("%s: accepted %q", name, text)
		}
		after, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if string(after) != string(before) {
			t.Fatalf("%s: a rejected save still rewrote the file", name)
		}
	}
	if msg := SetConfigText("", "config-version: 8\n"); msg == "" {
		t.Fatal("an empty dataDir was accepted")
	}
}

// A good save lands on disk, keeps the file loadable, and stages the OLD bytes as the backup —
// never the new ones. That inversion is what turned a failed write into data loss.
func TestSetConfigTextWritesAndStagesOldBytes(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "socks5://127.0.0.1:1080", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	path := filepath.Join(dir, ConfigFileName)
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	edited := strings.Replace(string(before), "socks5://127.0.0.1:1080", "http://127.0.0.1:7890", 1)
	if msg := SetConfigText(dir, edited); msg != "" {
		t.Fatalf("SetConfigText: %s", msg)
	}
	after, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(after) != edited {
		t.Fatalf("the saved text is not what landed on disk")
	}
	if _, err := os.Stat(path + configBackupSuffix); !os.IsNotExist(err) {
		t.Fatal("a successful save left its backup behind")
	}
	if _, err := sdkconfig.LoadConfig(path); err != nil {
		t.Fatalf("the saved config does not load: %v", err)
	}

	// Now prove the staged copy is the PREVIOUS content: interrupt the next write by making the
	// destination unwritable is awkward, so check writeFileAtomic's staging directly.
	if err := writeDurable(path+configBackupSuffix, before); err != nil {
		t.Fatal(err)
	}
	if !recoverConfigFromBackup(path) {
		t.Fatal("a valid staged copy was not restored")
	}
	restored, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(restored) != string(before) {
		t.Fatalf("restored bytes are not the previous content")
	}
}

// The full-text editor and the settings screen write the same file; neither may lose the other's
// edit.
func TestSetConfigTextCompetesWithSettingsWrites(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	base, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	payload, _ := json.Marshal(customProviderList{Providers: []customProvider{{
		Name: "keepme", BaseURL: "https://a.example/v1", Keys: []string{"k"},
		Models: []customProviderModel{{Name: "m"}},
	}}})
	if msg := SetCustomProviders(dir, string(payload)); msg != "" {
		t.Fatalf("seed: %s", msg)
	}
	text, _ := os.ReadFile(filepath.Join(dir, ConfigFileName))

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 40; i++ {
			if msg := UpdateQuickSettings(dir, "", false, "w", ThinkingCompact); msg != "" {
				t.Errorf("UpdateQuickSettings: %s", msg)
				return
			}
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 40; i++ {
			if msg := SetConfigText(dir, string(text)); msg != "" {
				t.Errorf("SetConfigText: %s", msg)
				return
			}
		}
	}()
	wg.Wait()

	body, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sdkconfig.LoadConfig(filepath.Join(dir, ConfigFileName)); err != nil {
		t.Fatalf("interleaved saves left an unloadable config: %v\n%s", err, body)
	}
	_ = base
}

// --------------------------------------------------------------------- 13. base-url far end

// The old check was a prefix test, so every string below was accepted, written, and then failed at
// dial time inside the engine — a provider group that looks configured and answers 502.
func TestBaseURLRejectsMalformed(t *testing.T) {
	bad := []string{
		"",
		"   ",
		"http://",
		"https://",
		"http:///x",
		"https:// ",
		"//api.deepseek.com/v1",
		"ftp://api.deepseek.com/v1",
		"api.deepseek.com/v1",
		"https://exa mple.com/v1",
		"https://user@/v1",
		"https://:8080/v1",
		"https://intranet/v1",
		"https://api.deepseek.com/v1 ",
		"https://api.deepseek.com/v1\x00",
		"https://api.deepseek.com/v1\n",
	}
	for _, raw := range bad {
		if err := validateProviderBaseURL(raw); err == nil {
			t.Errorf("accepted %q", raw)
		}
	}
	// The message has to name what is wrong, or the settings screen shows a dead end.
	if err := validateProviderBaseURL("ftp://x.example/v1"); err == nil || !strings.Contains(err.Error(), "http") {
		t.Fatalf("scheme error = %v, want it to mention http", err)
	}
	if err := validateProviderBaseURL("https://intranet/v1"); err == nil || !strings.Contains(err.Error(), "主机名") {
		t.Fatalf("host error = %v, want it to mention the host", err)
	}
}

// A local llama.cpp or ollama is the main reason this screen exists, and it is single-label plus
// loopback, which the stricter parser must still accept.
func TestBaseURLAcceptsLocalhost(t *testing.T) {
	good := []string{
		"http://localhost:11434/v1",
		"http://127.0.0.1:11434/v1",
		"http://[::1]:11434/v1",
		"https://api.deepseek.com/v1",
		"https://openrouter.ai/api/v1",
		"http://192.168.1.10:8080/v1",
		"HTTPS://API.DEEPSEEK.COM/v1",
		"https://api.moonshot.cn/v1/",
	}
	for _, raw := range good {
		if err := validateProviderBaseURL(raw); err != nil {
			t.Errorf("rejected %q: %v", raw, err)
		}
	}
}

// --------------------------------------------------------------------- 14. field caps

func TestProviderFieldLimits(t *testing.T) {
	long := func(n int) string { return strings.Repeat("x", n) }
	cases := []struct {
		name     string
		provider customProvider
		want     string
	}{
		{"name too long", customProvider{Name: long(maxProviderNameLen + 1), BaseURL: "https://a.example/v1",
			Models: []customProviderModel{{Name: "m"}}}, "名称"},
		{"name with NUL", customProvider{Name: "a\x00b", BaseURL: "https://a.example/v1",
			Models: []customProviderModel{{Name: "m"}}}, "控制字符"},
		{"name with newline", customProvider{Name: "a\nb", BaseURL: "https://a.example/v1",
			Models: []customProviderModel{{Name: "m"}}}, "控制字符"},
		{"model too long", customProvider{Name: "p", BaseURL: "https://a.example/v1",
			Models: []customProviderModel{{Name: long(maxModelNameLen + 1)}}}, "模型名"},
		{"alias too long", customProvider{Name: "p", BaseURL: "https://a.example/v1",
			Models: []customProviderModel{{Name: "m", Alias: long(maxModelAliasLen + 1)}}}, "模型别名"},
		{"key too long", customProvider{Name: "p", BaseURL: "https://a.example/v1",
			Keys: []string{long(maxProviderKeyLen + 1)}, Models: []customProviderModel{{Name: "m"}}}, "Key"},
		{"key with tab", customProvider{Name: "p", BaseURL: "https://a.example/v1",
			Keys: []string{"k\there"}, Models: []customProviderModel{{Name: "m"}}}, "控制字符"},
		{"too many keys", customProvider{Name: "p", BaseURL: "https://a.example/v1",
			Keys: repeatKeys(maxProviderKeys + 1), Models: []customProviderModel{{Name: "m"}}}, "Key"},
		{"too many models", customProvider{Name: "p", BaseURL: "https://a.example/v1",
			Models: repeatModels(maxProviderModels + 1)}, "模型"},
	}
	for _, tc := range cases {
		dir := t.TempDir()
		if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
			t.Fatal(msg)
		}
		path := filepath.Join(dir, ConfigFileName)
		before, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		payload, _ := json.Marshal(customProviderList{Providers: []customProvider{tc.provider}})
		msg := SetCustomProviders(dir, string(payload))
		if !strings.Contains(msg, tc.want) {
			t.Fatalf("%s: error %q does not mention %q", tc.name, msg, tc.want)
		}
		after, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if string(after) != string(before) {
			t.Fatalf("%s: a rejected save still rewrote the file", tc.name)
		}
	}

	// The caps themselves must not reject a realistic provider.
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatal(msg)
	}
	ok := customProvider{Name: long(maxProviderNameLen), BaseURL: "https://a.example/v1",
		Keys:   []string{long(maxProviderKeyLen)},
		Models: []customProviderModel{{Name: long(maxModelNameLen), Alias: long(maxModelAliasLen)}}}
	payload, _ := json.Marshal(customProviderList{Providers: []customProvider{ok}})
	if msg := SetCustomProviders(dir, string(payload)); msg != "" {
		t.Fatalf("a provider exactly at every limit was rejected: %s", msg)
	}
	if _, err := sdkconfig.LoadConfig(filepath.Join(dir, ConfigFileName)); err != nil {
		t.Fatalf("the engine cannot load a provider at the limits: %v", err)
	}
}

func repeatKeys(n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = "k"
	}
	return out
}

func repeatModels(n int) []customProviderModel {
	out := make([]customProviderModel, n)
	for i := range out {
		out[i] = customProviderModel{Name: "m" + itoa(i)}
	}
	return out
}

// --------------------------------------------------------------------- 15. duplicate keys

// A document with the same key twice is invalid at the engine's loader, so no caller has a "right"
// copy to pick; setPath and removePath at least have to agree on the whole set.
func TestDuplicateKeysAreTreatedConsistently(t *testing.T) {
	src := "config-version: 8\n" +
		"access:\n  api-keys:\n    - first\n  api-keys:\n    - second\n"
	var doc yaml.Node
	if err := yaml.Unmarshal([]byte(src), &doc); err != nil {
		t.Fatal(err)
	}
	root := docRoot(&doc)
	val := &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "written"}
	if err := setPath(root, []string{"access", "api-keys"}, val); err != nil {
		t.Fatal(err)
	}
	access := lookupNode(root, []string{"access"})
	seen := 0
	for j := 0; j+1 < len(access.Content); j += 2 {
		if access.Content[j].Value != "api-keys" {
			continue
		}
		seen++
		if got := access.Content[j+1].Value; got != "written" {
			t.Fatalf("copy %d still holds %q; setPath wrote only some of the duplicates", seen, got)
		}
	}
	if seen != 2 {
		t.Fatalf("found %d copies, want the 2 the document had", seen)
	}
	if access.Content[1] == access.Content[3] {
		t.Fatal("both duplicate keys point at the same node; editing one would silently edit both")
	}

	removePath(root, []string{"access", "api-keys"})
	if lookupNode(root, []string{"access", "api-keys"}) != nil {
		t.Fatal("removePath left a duplicate behind")
	}
}

// --------------------------------------------------------------------- 16. RotateClientAPIKey

func TestRotateClientAPIKey(t *testing.T) {
	dir := auditConfig(t, "config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n"+
		"access:\n  api-keys:\n    - "+legacyAPIKey+"\n")

	if msg := RotateClientAPIKey(dir); msg != "" {
		t.Fatalf("RotateClientAPIKey: %s", msg)
	}
	key := ClientAPIKey(dir)
	if key == "" || key == legacyAPIKey {
		t.Fatalf("after rotation the client key is %q", key)
	}
	if !strings.HasPrefix(key, defaultAPIKeyPrefix) {
		t.Fatalf("rotated key %q does not carry the app prefix", key)
	}
	body := auditRead(t, dir)
	if strings.Contains(body, legacyAPIKey) {
		t.Fatalf("the repository-published key is still in the file:\n%s", body)
	}

	// A second call must be a no-op: rewriting a key the user chose would break every client
	// already configured against it.
	before := auditRead(t, dir)
	if msg := RotateClientAPIKey(dir); msg != "" {
		t.Fatalf("second RotateClientAPIKey: %s", msg)
	}
	if after := auditRead(t, dir); after != before {
		t.Fatal("a no-op rotation rewrote the file")
	}

	// No access section at all: the app must still end up with a gate.
	empty := auditConfig(t, "config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n")
	if msg := RotateClientAPIKey(empty); msg != "" {
		t.Fatalf("RotateClientAPIKey on a config without access: %s", msg)
	}
	if got := ClientAPIKey(empty); got == "" || got == legacyAPIKey {
		t.Fatalf("no key was added: %q", got)
	}

	// The engine accepts the rotated key and rejects the old one.
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()
	target := "http://" + GetListenAddress() + "/v1/models"
	status := func(k string) int {
		req, err := http.NewRequest(http.MethodGet, target, nil)
		if err != nil {
			t.Fatal(err)
		}
		if k != "" {
			req.Header.Set("Authorization", "Bearer "+k)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = resp.Body.Close()
		return resp.StatusCode
	}
	if got := status(key); got != http.StatusOK {
		t.Fatalf("rotated key rejected by /v1/models: %d", got)
	}
	if got := status(legacyAPIKey); got != http.StatusUnauthorized {
		t.Fatalf("the legacy key is still accepted: %d", got)
	}
}

// The rotation must go through the in-place writer, or the engine's watcher (bound to the inode)
// never sees the new key and keeps enforcing the old one until the next restart.
func TestRotateClientAPIKeyReachesTheWatcher(t *testing.T) {
	dir := auditConfig(t, "config-version: 8\nserver:\n  host: 127.0.0.1\n  port: 8317\n"+
		"access:\n  api-keys:\n    - "+legacyAPIKey+"\n")
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy: %s", msg)
	}
	defer StopProxy()
	logPath := filepath.Join(dir, "cpa-engine.log")
	waitForLog(t, logPath, "file watcher started", 10*time.Second)
	reloads := func() int {
		raw, err := os.ReadFile(logPath)
		if err != nil {
			return -1
		}
		return strings.Count(string(raw), "config file changed")
	}
	before := reloads()
	if msg := RotateClientAPIKey(dir); msg != "" {
		t.Fatalf("RotateClientAPIKey: %s", msg)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if reloads() > before {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("the rotation never reached the engine's watcher (reloads stayed at %d)", before)
}

// --------------------------------------------------------------------- 17. probe outside the lock

// Status() and IsRunning() are called from the UI thread on a repaint timer. While the liveness
// dial ran under state.mu, a start that probed a dead address held every one of those callers for
// the whole livenessTimeout.
func TestProbeDoesNotHoldStateMu(t *testing.T) {
	state.mu.Lock()
	state.service = &cliproxy.Service{}
	state.listenAddr = "127.0.0.1:1" // closed port: the dial burns the full timeout
	state.generation = 0
	state.mu.Unlock()
	defer func() {
		state.mu.Lock()
		state.service = nil
		state.listenAddr = ""
		state.mu.Unlock()
	}()

	probeDone := make(chan time.Duration, 1)
	go func() {
		start := time.Now()
		_, _, live := probeEngineSnapshot()
		if live {
			t.Error("probeEngineSnapshot reported a closed port as live")
		}
		probeDone <- time.Since(start)
	}()

	// Let the goroutine reach the dial, then require the mutex to still be free.
	time.Sleep(30 * time.Millisecond)
	start := time.Now()
	state.mu.Lock()
	state.mu.Unlock()
	if elapsed := time.Since(start); elapsed > 40*time.Millisecond {
		t.Fatalf("state.mu was held for %s while the probe was dialling; the dial is back under the lock", elapsed)
	}
	select {
	case elapsed := <-probeDone:
		if elapsed < 100*time.Millisecond {
			t.Fatalf("the probe returned in %s; it never blocked, so the measurement proves nothing", elapsed)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("probeEngineSnapshot did not return")
	}
}
