package cliproxymobile

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	sdkconfig "github.com/router-for-me/CLIProxyAPI/v8/sdk/config"
	"gopkg.in/yaml.v3"
)

// readProviders is the test-side decoder for what the settings screen receives.
func readProviders(t *testing.T, dataDir string) customProviderList {
	t.Helper()
	raw := GetCustomProviders(dataDir)
	var list customProviderList
	if err := json.Unmarshal([]byte(raw), &list); err != nil {
		t.Fatalf("GetCustomProviders returned non-JSON %q: %v", raw, err)
	}
	if list.Error != "" {
		t.Fatalf("GetCustomProviders error: %s", list.Error)
	}
	return list
}

func writeProviders(t *testing.T, dataDir string, providers []customProvider) {
	t.Helper()
	payload, err := json.Marshal(customProviderList{Providers: providers})
	if err != nil {
		t.Fatal(err)
	}
	if msg := SetCustomProviders(dataDir, string(payload)); msg != "" {
		t.Fatalf("SetCustomProviders failed: %s", msg)
	}
}

func freshConfig(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if msg := EnsureDefaultConfig(dir, "", false, ThinkingCompact); msg != "" {
		t.Fatalf("EnsureDefaultConfig failed: %s", msg)
	}
	return dir
}

// TestCustomProvidersRoundTrip proves a group written through the bridge is readable back and,
// more importantly, that the engine itself parses it: the check goes through the same
// LoadConfig the runtime uses rather than trusting our own YAML.
func TestCustomProvidersRoundTrip(t *testing.T) {
	dir := freshConfig(t)
	if got := readProviders(t, dir); len(got.Providers) != 0 {
		t.Fatalf("a fresh config already has providers: %+v", got.Providers)
	}

	writeProviders(t, dir, []customProvider{
		{
			Name:    "deepseek",
			BaseURL: "https://api.deepseek.com/v1",
			Keys:    []string{"sk-deepseek-one", "sk-deepseek-two"},
			Models: []customProviderModel{
				{Name: "deepseek-chat"},
				{Name: "deepseek-reasoner", Alias: "reasoner"},
			},
		},
	})

	got := readProviders(t, dir)
	if len(got.Providers) != 1 {
		t.Fatalf("providers = %d, want 1", len(got.Providers))
	}
	p := got.Providers[0]
	if p.Name != "deepseek" || p.BaseURL != "https://api.deepseek.com/v1" {
		t.Fatalf("group = %+v", p)
	}
	if len(p.Keys) != 2 || p.Keys[0] != "sk-deepseek-one" {
		t.Fatalf("keys = %v", p.Keys)
	}
	if len(p.Models) != 2 || p.Models[0].Name != "deepseek-chat" || p.Models[1].Alias != "reasoner" {
		t.Fatalf("models = %+v", p.Models)
	}

	// The engine's own loader is the authority on whether this is a valid v8 document.
	cfg, err := sdkconfig.LoadConfig(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatalf("engine rejected the written config: %v", err)
	}
	if len(cfg.OpenAICompatibility) != 1 {
		t.Fatalf("engine saw %d compat providers, want 1", len(cfg.OpenAICompatibility))
	}
	compat := cfg.OpenAICompatibility[0]
	if compat.BaseURL != "https://api.deepseek.com/v1" {
		t.Fatalf("engine base-url = %q", compat.BaseURL)
	}
	if len(compat.APIKeyEntries) != 2 || compat.APIKeyEntries[1].APIKey != "sk-deepseek-two" {
		t.Fatalf("engine did not expand keys: %+v", compat.APIKeyEntries)
	}
	if len(compat.Models) != 2 || compat.Models[1].Alias != "reasoner" {
		t.Fatalf("engine models = %+v", compat.Models)
	}
}

// TestCustomProvidersPreserveUntouchedFields proves the editor only owns what it shows. A group
// hand-tuned with headers/priority must keep them after a save that only renames a model.
func TestCustomProvidersPreserveUntouchedFields(t *testing.T) {
	dir := freshConfig(t)
	path := filepath.Join(dir, ConfigFileName)
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	extra := "\napi-keys:\n  openai-compatibility:\n" +
		"    - name: \"openrouter\"\n" +
		"      base-url: \"https://openrouter.ai/api/v1\"\n" +
		"      priority: 7\n" +
		"      prefix: \"or\"\n" +
		"      headers:\n" +
		"        X-Custom: keep-me\n" +
		"      keys:\n" +
		"        - api-key: \"sk-or-one\"\n" +
		"      models:\n" +
		"        - name: \"moonshotai/kimi-k2\"\n" +
		"          alias: \"kimi-k2\"\n" +
		"          display-name: \"Kimi K2\"\n"
	if err := os.WriteFile(path, append(raw, []byte(extra)...), 0o600); err != nil {
		t.Fatal(err)
	}

	got := readProviders(t, dir)
	if len(got.Providers) != 1 || got.Providers[0].Models[0].Alias != "kimi-k2" {
		t.Fatalf("failed to read the hand-written group: %+v", got.Providers)
	}

	// Save it back unchanged, which is what tapping 保存 without edits does.
	writeProviders(t, dir, got.Providers)

	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	text := string(body)
	// Parsed rather than grepped: yaml.v3 quotes scalars that could be read as another type
	// (prefix: "or" comes back quoted), so a substring check on the raw text is a check on the
	// marshaller's quoting rules, not on whether the value survived.
	var doc yaml.Node
	if errParse := yaml.Unmarshal(body, &doc); errParse != nil {
		t.Fatalf("rewritten config does not parse: %v\n%s", errParse, text)
	}
	group := lookupNode(docRoot(&doc), []string{"api-keys", "openai-compatibility"})
	if group == nil || group.Kind != yaml.SequenceNode || len(group.Content) != 1 {
		t.Fatalf("provider group lost:\n%s", text)
	}
	g := group.Content[0]
	if got := scalarAt(g, "priority"); got != "7" {
		t.Fatalf("priority = %q, want 7", got)
	}
	if got := scalarAt(g, "prefix"); got != "or" {
		t.Fatalf("prefix = %q, want or", got)
	}
	if headers := lookupNode(g, []string{"headers"}); headers == nil || scalarAt(headers, "X-Custom") != "keep-me" {
		t.Fatalf("headers lost:\n%s", text)
	}
	if models := lookupNode(g, []string{"models"}); models == nil || len(models.Content) == 0 ||
		scalarAt(models.Content[0], "display-name") != "Kimi K2" {
		t.Fatalf("display-name lost:\n%s", text)
	}
	// keys: must stay the single spelling, otherwise the loader expands a second list.
	if strings.Contains(text, "api-key-entries") {
		t.Fatalf("wrote the expanded api-key-entries alongside keys:\n%s", text)
	}
}

// TestCustomProvidersDeleteLastLeavesNoEmptyBlock proves removing the final provider takes the
// whole api-keys: block with it. An empty mapping there is valid YAML but reads as "the user
// configured no providers" to a human opening the file.
func TestCustomProvidersDeleteLastLeavesNoEmptyBlock(t *testing.T) {
	dir := freshConfig(t)
	writeProviders(t, dir, []customProvider{
		{Name: "local", BaseURL: "http://127.0.0.1:11434/v1", Keys: []string{"x"},
			Models: []customProviderModel{{Name: "llama3"}}},
	})
	writeProviders(t, dir, nil)

	body, err := os.ReadFile(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	// access.api-keys (client auth) must stay; it is a different section that happens to share
	// the name. Only the provider block has to disappear.
	for _, line := range strings.Split(string(body), "\n") {
		if strings.HasPrefix(line, "api-keys:") || strings.Contains(line, "openai-compatibility") {
			t.Fatalf("left a provider block behind: %q\n%s", line, body)
		}
	}
	if !strings.Contains(string(body), "access:") {
		t.Fatalf("removing providers also took access.api-keys with it:\n%s", body)
	}
	if len(readProviders(t, dir).Providers) != 0 {
		t.Fatal("providers survived a clear")
	}
}

// TestCustomProvidersRejectBadInput covers the three ways a form can be wrong. Each has to fail
// before the file is touched: a config that half-parses stops the gateway from starting.
func TestCustomProvidersRejectBadInput(t *testing.T) {
	dir := freshConfig(t)
	path := filepath.Join(dir, ConfigFileName)
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	cases := []struct {
		name     string
		provider customProvider
		want     string
	}{
		{"no name", customProvider{BaseURL: "https://x/v1", Models: []customProviderModel{{Name: "m"}}}, "名称"},
		{"no base url", customProvider{Name: "x", Models: []customProviderModel{{Name: "m"}}}, "接口地址"},
		{"bad scheme", customProvider{Name: "x", BaseURL: "api.example.com/v1", Models: []customProviderModel{{Name: "m"}}}, "http"},
		{"no models", customProvider{Name: "x", BaseURL: "https://x.example/v1"}, "模型"},
	}
	for _, tc := range cases {
		payload, errMarshal := json.Marshal(customProviderList{Providers: []customProvider{tc.provider}})
		if errMarshal != nil {
			t.Fatal(errMarshal)
		}
		msg := SetCustomProviders(dir, string(payload))
		if !strings.Contains(msg, tc.want) {
			t.Fatalf("%s: error %q does not mention %q", tc.name, msg, tc.want)
		}
		after, errRead := os.ReadFile(path)
		if errRead != nil {
			t.Fatal(errRead)
		}
		if string(after) != string(before) {
			t.Fatalf("%s: a rejected save still rewrote the file", tc.name)
		}
	}

	if msg := SetCustomProviders(dir, "{not json"); !strings.Contains(msg, "解析失败") {
		t.Fatalf("bad JSON error = %q", msg)
	}
}

// TestCustomProvidersDuplicateNamesRejected: two groups with the same name make the model list
// ambiguous (owned_by collides) and the panel cannot tell them apart.
func TestCustomProvidersDuplicateNamesRejected(t *testing.T) {
	dir := freshConfig(t)
	payload, _ := json.Marshal(customProviderList{Providers: []customProvider{
		{Name: "dup", BaseURL: "https://a.example/v1", Keys: []string{"1"}, Models: []customProviderModel{{Name: "m"}}},
		{Name: "DUP", BaseURL: "https://b.example/v1", Keys: []string{"2"}, Models: []customProviderModel{{Name: "n"}}},
	}})
	if msg := SetCustomProviders(dir, string(payload)); !strings.Contains(msg, "重复") {
		t.Fatalf("duplicate names were accepted: %q", msg)
	}
}

// TestCustomProvidersAliasDefaultsToName: leaving alias blank is how the UI says "same as name";
// a blank alias in the file would register a model nobody can call.
func TestCustomProvidersAliasDefaultsToName(t *testing.T) {
	dir := freshConfig(t)
	writeProviders(t, dir, []customProvider{
		{Name: "kimi", BaseURL: "https://api.moonshot.cn/v1", Keys: []string{"k"},
			Models: []customProviderModel{{Name: "kimi-k2"}}},
	})
	cfg, err := sdkconfig.LoadConfig(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.OpenAICompatibility) != 1 || len(cfg.OpenAICompatibility[0].Models) != 1 {
		t.Fatalf("engine did not see the model: %+v", cfg.OpenAICompatibility)
	}
	if alias := cfg.OpenAICompatibility[0].Models[0].Alias; alias != "kimi-k2" {
		t.Fatalf("alias = %q, want the model name", alias)
	}
}

// TestCustomProviderServesRequests is the end-to-end proof: a fake OpenAI-compatible upstream
// stood up on loopback must be reachable through the gateway, with its alias listed by
// /v1/models and its key forwarded upstream. Without this the editor is only a YAML form.
func TestCustomProviderServesRequests(t *testing.T) {
	var (
		mu       sync.Mutex
		seenAuth string
		seenPath string
	)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seenPath = r.URL.Path
		seenAuth = r.Header.Get("Authorization")
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"deepseek-chat","choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`)
	}))
	defer upstream.Close()

	dir := freshConfig(t)
	writeProviders(t, dir, []customProvider{
		{Name: "fakedeepseek", BaseURL: upstream.URL + "/v1", Keys: []string{"sk-upstream-key"},
			Models: []customProviderModel{{Name: "deepseek-chat"}}},
	})
	if msg := StartProxy(dir); msg != "" {
		t.Fatalf("StartProxy failed: %s", msg)
	}
	defer StopProxy()

	key, err := apiKeyFromConfig(filepath.Join(dir, ConfigFileName))
	if err != nil {
		t.Fatal(err)
	}
	addr := GetListenAddress()
	client := &http.Client{Timeout: 15 * time.Second}

	// The model list has to carry the alias, otherwise a client cannot select it at all.
	lists := waitForBody(t, client, addr, "/v1/models", key, func(body string) bool {
		return strings.Contains(body, "deepseek-chat")
	})
	if !strings.Contains(lists, "fakedeepseek") {
		t.Fatalf("alias listed without its provider:\n%s", lists)
	}

	// The completion goes through for real. It is a POST, so it cannot use waitForBody; the
	// retry loop is here because the client pool is rebuilt asynchronously after the config
	// reload the write triggered.
	body := `{"model":"deepseek-chat","messages":[{"role":"user","content":"ping"}],"max_tokens":8}`
	var chat string
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		req, errReq := http.NewRequest(http.MethodPost, "http://"+addr+"/v1/chat/completions", strings.NewReader(body))
		if errReq != nil {
			t.Fatal(errReq)
		}
		req.Header.Set("Authorization", "Bearer "+key)
		req.Header.Set("Content-Type", "application/json")
		resp, errDo := client.Do(req)
		if errDo == nil {
			raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
			_ = resp.Body.Close()
			chat = string(raw)
			if strings.Contains(chat, "pong") {
				break
			}
		}
		time.Sleep(300 * time.Millisecond)
	}
	if !strings.Contains(chat, "pong") {
		t.Fatalf("completion never reached the upstream, last body:\n%s", chat)
	}

	mu.Lock()
	auth, path := seenAuth, seenPath
	mu.Unlock()
	if auth != "Bearer sk-upstream-key" {
		t.Fatalf("upstream saw Authorization %q, want the configured key", auth)
	}
	if !strings.HasSuffix(path, "/chat/completions") {
		t.Fatalf("upstream path = %q", path)
	}
}

// waitForBody polls an endpoint until want() accepts the body, and returns that body.
func waitForBody(t *testing.T, client *http.Client, addr, path, key string, want func(string) bool) string {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	var last string
	for time.Now().Before(deadline) {
		req, err := http.NewRequest(http.MethodGet, "http://"+addr+path, nil)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer "+key)
		resp, errDo := client.Do(req)
		if errDo == nil {
			raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
			_ = resp.Body.Close()
			last = string(raw)
			if want(last) {
				return last
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("%s never satisfied the condition, last body:\n%s", path, last)
	return ""
}
