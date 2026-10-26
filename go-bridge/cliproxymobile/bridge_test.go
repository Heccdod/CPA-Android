package cliproxymobile

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	sdkconfig "github.com/router-for-me/CLIProxyAPI/v8/sdk/config"
)

// TestBridgeLifecycle walks the whole contract-B matrix: cold start, port binding,
// config hot reload, quick-settings rewrite and graceful shutdown.
func TestBridgeLifecycle(t *testing.T) {
	dir := t.TempDir()

	if msg := EnsureDefaultConfig(dir, "", false); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	cfgPath := filepath.Join(dir, ConfigFileName)
	raw, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatalf("read default config: %v", err)
	}
	text := string(raw)
	for _, want := range []string{
		"config-version: 8",
		"signature-cache-enabled: true",
		"antigravity-credits: true",
		"keepalive-seconds: 15",
		"Claude Agent SDK",
		"system-conventions",
		"Hermes Agent",
	} {
		if !strings.Contains(text, want) {
			t.Fatalf("default config missing %q", want)
		}
	}

	cfg, err := sdkconfig.LoadConfig(cfgPath)
	if err != nil {
		t.Fatalf("LoadConfig rejected the shipped template: %v", err)
	}
	if cfg.Port != DefaultPort {
		t.Fatalf("unexpected port %d", cfg.Port)
	}
	if cfg.Streaming.KeepAliveSeconds != 15 {
		t.Fatalf("keepalive not applied: %d", cfg.Streaming.KeepAliveSeconds)
	}

	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy failed: %s", msg)
	}
	defer func() { _ = StopProxy() }()

	if !IsRunning() {
		t.Fatal("IsRunning reported false right after StartProxy")
	}
	addr := GetListenAddress()
	if addr != fmt.Sprintf("127.0.0.1:%d", DefaultPort) {
		t.Fatalf("unexpected listen address %q", addr)
	}
	if err := probe(addr); err != nil {
		t.Fatalf("gateway not listening on %s: %v", addr, err)
	}

	// The engine must already answer an authenticated API call.
	if err := getModels(addr); err != nil {
		t.Fatalf("GET /v1/models failed: %v", err)
	}

	// Hot reload: rewrite the config in place exactly like the Android L2 channel.
	if msg := UpdateQuickSettings(dir, "socks5://127.0.0.1:7890", false, "alpha,beta,Codex"); msg != "" {
		t.Fatalf("UpdateQuickSettings failed: %s", msg)
	}
	updated, err := os.ReadFile(cfgPath)
	if err != nil {
		t.Fatalf("re-read config: %v", err)
	}
	if !strings.Contains(string(updated), "socks5://127.0.0.1:7890") {
		t.Fatalf("proxy-url not persisted:\n%s", string(updated))
	}
	if !strings.Contains(string(updated), "alpha") {
		t.Fatalf("sensitive words not persisted:\n%s", string(updated))
	}
	if _, err := sdkconfig.LoadConfig(cfgPath); err != nil {
		t.Fatalf("rewritten config is not loadable: %v", err)
	}

	// Give the watcher time to reload; the gateway must stay alive and serve.
	time.Sleep(1500 * time.Millisecond)
	if !IsRunning() {
		t.Fatal("engine died after a hot reload")
	}
	if err := getModels(addr); err != nil {
		t.Fatalf("gateway stopped serving after hot reload: %v", err)
	}

	// RestartProxy must keep the same listen address and stay serving.
	if msg := RestartProxy(dir); msg != "" {
		t.Fatalf("RestartProxy failed: %s", msg)
	}
	if err := probe(GetListenAddress()); err != nil {
		t.Fatalf("gateway not listening after restart: %v", err)
	}

	if msg := StopProxy(); msg != "" {
		t.Fatalf("StopProxy failed: %s", msg)
	}
	if IsRunning() {
		t.Fatal("IsRunning still true after StopProxy")
	}
	if err := probe(addr); err == nil {
		t.Fatal("port still accepting connections after StopProxy")
	}
}

// TestUpdateQuickSettingsRestoresDefaults verifies the empty CSV fallback.
func TestUpdateQuickSettingsRestoresDefaults(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", true); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	if msg := UpdateQuickSettings(dir, "", true, ""); msg != "" {
		t.Fatalf("UpdateQuickSettings failed: %s", msg)
	}
	raw, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatalf("read config: %v", err)
	}
	text := string(raw)
	if !strings.Contains(text, "host: 0.0.0.0") {
		t.Fatalf("LAN host not applied:\n%s", text)
	}
	if !strings.Contains(text, "Claude Agent SDK") {
		t.Fatalf("default sensitive words not restored:\n%s", text)
	}
	if _, err := sdkconfig.LoadConfig(filepath.Join(dir, ConfigFileName)); err != nil {
		t.Fatalf("config unreadable: %v", err)
	}
}

func probe(addr string) error {
	conn, err := net.DialTimeout("tcp", addr, 300*time.Millisecond)
	if err != nil {
		return err
	}
	return conn.Close()
}

func getModels(addr string) error {
	req, err := http.NewRequest(http.MethodGet, "http://"+addr+"/v1/models", nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+defaultAPIKey)
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d: %s", resp.StatusCode, string(body))
	}
	return nil
}

// TestManagementPanelAndOauthUrl proves the two contract-C endpoints really answer on the
// embedded engine: the bundled panel is served, and the Antigravity auth URL points at the
// localhost:51121 redirect the Go kernel catches itself (zero copy/paste).
func TestManagementPanelAndOauthUrl(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy failed: %s", msg)
	}
	defer func() { _ = StopProxy() }()
	addr := GetListenAddress()

	// The control panel asset is fetched on first access; assert the route answers with HTML.
	status, body := getWithKey(t, "http://"+addr+"/management.html", "")
	if status != http.StatusOK {
		t.Fatalf("GET /management.html = %d: %s", status, body)
	}
	if !strings.Contains(strings.ToLower(body), "<html") {
		t.Fatalf("management panel is not HTML: %.200s", body)
	}

	// The management API must accept the local password and return the v8 config tree.
	status, body = getWithKey(t, "http://"+addr+"/v8/management/config", ManagementKey)
	if status != http.StatusOK {
		t.Fatalf("GET /v8/management/config = %d: %s", status, body)
	}
	if !strings.Contains(body, "antigravity") {
		t.Fatalf("v8 config does not expose antigravity settings: %.400s", body)
	}

	// Auth URL hand-off used by the Android Custom Tab.
	status, body = getWithKey(t, "http://"+addr+"/v8/management/oauth/auth-url?provider=antigravity&is_webui=1", ManagementKey)
	if status != http.StatusOK {
		t.Fatalf("GET oauth/auth-url = %d: %s", status, body)
	}
	if !strings.Contains(body, "accounts.google.com") {
		t.Fatalf("auth url is not a Google consent URL: %.400s", body)
	}
	if !strings.Contains(body, "localhost%3A51121") && !strings.Contains(body, "localhost:51121") {
		t.Fatalf("auth url does not redirect to localhost:51121: %.400s", body)
	}
}

func getWithKey(t *testing.T, url, key string) (int, string) {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		t.Fatalf("build request: %v", err)
	}
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	client := &http.Client{Timeout: 60 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request %s: %v", url, err)
	}
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	return resp.StatusCode, string(body)
}

// TestOAuthCallbackCaptureLoop drives the zero-paste Antigravity loop without a browser:
// ask the management API for the consent URL, then POST the redirect the way Chrome would
// hit http://localhost:51121/oauth-callback and assert the engine consumed the code/state
// (session leaves "pending" and reports the token-exchange failure instead of hanging).
func TestOAuthCallbackCaptureLoop(t *testing.T) {
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy failed: %s", msg)
	}
	defer func() { _ = StopProxy() }()
	addr := GetListenAddress()

	status, body := getWithKey(t, "http://"+addr+"/v8/management/oauth/auth-url?provider=antigravity&is_webui=1", ManagementKey)
	if status != http.StatusOK {
		t.Fatalf("auth-url = %d: %s", status, body)
	}
	state := jsonField(t, body, "state")
	if state == "" {
		t.Fatalf("auth-url returned no state: %s", body)
	}

	// Chrome redirects here after the user signs in; the kernel forwarder relays it.
	callback := "http://127.0.0.1:51121/oauth-callback?code=fake-code&state=" + url.QueryEscape(state)
	client := &http.Client{
		Timeout: 20 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse // the forwarder answers 302; do not chase it
		},
	}
	var resp *http.Response
	var errDo error
	for i := 0; i < 40; i++ { // the forwarder is started asynchronously by the handler
		resp, errDo = client.Get(callback)
		if errDo == nil {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}
	if errDo != nil {
		t.Fatalf("localhost:51121/oauth-callback never accepted the redirect: %v", errDo)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusFound {
		t.Fatalf("callback forwarder = %d, want 302", resp.StatusCode)
	}
	if loc := resp.Header.Get("Location"); !strings.Contains(loc, "/antigravity/callback") {
		t.Fatalf("forwarder relocated to %q, want /antigravity/callback", loc)
	}

	// The kernel must have consumed the wait file: the session leaves the idle state and
	// either starts committing (status "wait") or reports the token-exchange failure.
	// "unknown or expired state" would mean the redirect never reached the handler.
	deadline := time.Now().Add(20 * time.Second)
	var lastBody string
	for time.Now().Before(deadline) {
		_, lastBody = getWithKey(t, "http://"+addr+"/v8/management/oauth/status?state="+url.QueryEscape(state), ManagementKey)
		if strings.Contains(lastBody, "\"status\":\"error\"") ||
			strings.Contains(lastBody, "\"status\":\"ok\"") ||
			strings.Contains(lastBody, "\"status\":\"wait\"") {
			t.Logf("callback captured, kernel state: %s", lastBody)
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("callback was not consumed, last status: %.400s", lastBody)
}

func jsonField(t *testing.T, body, key string) string {
	t.Helper()
	var payload map[string]any
	if err := json.Unmarshal([]byte(body), &payload); err != nil {
		t.Fatalf("decode %s: %v", body, err)
	}
	if v, ok := payload[key].(string); ok {
		return v
	}
	return ""
}
