package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"crypto/tls"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/skip2/go-qrcode"
)

const (
	protocolVersion = 1
	onlineWindow    = 15 * time.Second
	pairCodeTTL     = 5 * time.Minute
	pairRetryLimit  = 12
)

//go:embed assets/pairing.html
var pairingHTML string

var pairingTemplate = template.Must(template.New("pairing").Parse(pairingHTML))

type config struct {
	home       string
	codexHome  string
	bind       string
	statusPort int
	uiPort     int
	discover   bool
}

func readConfig() (config, error) {
	cfg := config{
		home:      os.Getenv("CODEX_STATUS_HOME"),
		codexHome: os.Getenv("CODEX_STATUS_SOURCE"),
		bind:      os.Getenv("CODEX_STATUS_BIND"),
		discover:  os.Getenv("CODEX_STATUS_DISABLE_DISCOVERY") != "1",
	}
	if cfg.home == "" {
		base, err := os.UserCacheDir()
		if err != nil {
			return cfg, err
		}
		cfg.home = filepath.Join(base, "CodexStatusScreen")
	}
	if cfg.codexHome == "" {
		cfg.codexHome = os.Getenv("CODEX_HOME")
		if cfg.codexHome == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				return cfg, err
			}
			cfg.codexHome = filepath.Join(home, ".codex")
		}
	}
	if cfg.bind == "" {
		cfg.bind = "0.0.0.0"
	}
	var err error
	cfg.statusPort, err = envPort("CODEX_STATUS_PORT", 8767)
	if err != nil {
		return cfg, err
	}
	cfg.uiPort, err = envPort("CODEX_STATUS_UI_PORT", 8768)
	if cfg.statusPort == cfg.uiPort {
		return cfg, errors.New("采集端口和配对页端口需要使用不同的端口")
	}
	return cfg, err
}

func envPort(name string, fallback int) (int, error) {
	value := os.Getenv(name)
	if value == "" {
		return fallback, nil
	}
	port, err := strconv.Atoi(value)
	if err != nil || port < 1 || port > 65535 {
		return 0, fmt.Errorf("%s 端口号无效", name)
	}
	return port, nil
}

func (cfg config) managementHost() string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.uiPort))
}

type pairCode struct {
	value    string
	expires  time.Time
	failures int
}

type collector struct {
	cfg    config
	id     *identity
	source *codexSource

	mu     sync.RWMutex
	latest snapshot
	ready  bool
	code   pairCode
	seen   map[string]time.Time
}

type connectionStatus struct {
	online  int
	paired  int
	ready   bool
	partial bool
}

func (status connectionStatus) sourceLabel() string {
	switch {
	case !status.ready:
		return "等待 Codex 会话日志"
	case status.partial:
		return "部分 Codex 日志可读"
	default:
		return "Codex 日志只读采集中"
	}
}

func newCollector(cfg config, id *identity) *collector {
	return &collector{
		cfg: cfg, id: id, source: newCodexSource(cfg.codexHome),
		latest: emptySnapshot(time.Now()), seen: make(map[string]time.Time),
	}
}

func (c *collector) sample(ctx context.Context) {
	ticker := time.NewTicker(1200 * time.Millisecond)
	defer ticker.Stop()
	for {
		status, err := c.source.snapshot(time.Now())
		c.mu.Lock()
		c.ready = err == nil
		if err == nil {
			c.latest = status
		}
		c.mu.Unlock()
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (c *collector) connections(now time.Time) connectionStatus {
	c.mu.RLock()
	defer c.mu.RUnlock()
	var online int
	for _, last := range c.seen {
		if now.Sub(last) <= onlineWindow {
			online++
		}
	}
	return connectionStatus{
		online: online, paired: len(c.id.authorized), ready: c.ready, partial: c.latest.Partial,
	}
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}

func (c *collector) handleAPI(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodGet && r.URL.RequestURI() == "/health":
		c.mu.RLock()
		ready := c.ready
		c.mu.RUnlock()
		writeJSON(w, http.StatusOK, struct {
			Version int  `json:"version"`
			Ready   bool `json:"ready"`
		}{protocolVersion, ready})
	case r.Method == http.MethodPost && r.URL.RequestURI() == "/v1/pair":
		c.handlePair(w, r)
	case r.Method == http.MethodGet && r.URL.RequestURI() == "/v1/status":
		c.handleStatus(w, r)
	default:
		writeError(w, http.StatusNotFound, "Unknown route")
	}
}

func (c *collector) handleStatus(w http.ResponseWriter, r *http.Request) {
	token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !ok || len(token) != 43 {
		writeError(w, http.StatusUnauthorized, "请重新配对设备")
		return
	}
	hash := digest([]byte(token))
	c.mu.Lock()
	_, authorized := c.id.authorized[hash]
	if !authorized {
		c.mu.Unlock()
		writeError(w, http.StatusUnauthorized, "请重新配对设备")
		return
	}
	c.seen[hash] = time.Now()
	status, ready := c.latest, c.ready
	c.mu.Unlock()
	if status.ObservedAt == 0 {
		status = emptySnapshot(time.Now())
	}
	writeJSON(w, http.StatusOK, struct {
		Version     int        `json:"version"`
		Device      deviceInfo `json:"device"`
		SourceReady bool       `json:"sourceReady"`
		snapshot
	}{protocolVersion, c.id.file.deviceInfo, ready, status})
}

func (c *collector) handlePair(w http.ResponseWriter, r *http.Request) {
	var request struct {
		Code string `json:"code"`
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 2048))
	if err != nil || json.Unmarshal(data, &request) != nil {
		request.Code = ""
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.code.expires.After(time.Now()) || c.code.failures >= pairRetryLimit {
		writeError(w, http.StatusUnauthorized, "配对码已失效，请刷新电脑配对页")
		return
	}
	if subtle.ConstantTimeCompare([]byte(c.code.value), []byte(request.Code)) != 1 {
		c.code.failures++
		writeError(w, http.StatusUnauthorized, "配对码不正确")
		return
	}
	token, err := c.id.pair()
	if err != nil {
		log.Printf("保存配对记录: %v", err)
		writeError(w, http.StatusInternalServerError, "采集器暂时不可用")
		return
	}
	c.code = pairCode{}
	writeJSON(w, http.StatusOK, struct {
		Version int        `json:"version"`
		Device  deviceInfo `json:"device"`
		Token   string     `json:"token"`
	}{protocolVersion, c.id.file.deviceInfo, token})
}

func (c *collector) currentCode(now time.Time) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if now.Before(c.code.expires) && c.code.failures < pairRetryLimit {
		return c.code.value, nil
	}
	random, err := rand.Int(rand.Reader, big.NewInt(90_000_000))
	if err != nil {
		return "", err
	}
	c.code = pairCode{value: strconv.FormatInt(10_000_000+random.Int64(), 10), expires: now.Add(pairCodeTTL)}
	return c.code.value, nil
}

func (c *collector) handlePortal(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || r.Host != c.cfg.managementHost() || r.URL.Path != "/" {
		writeJSON(w, http.StatusNotFound, struct{}{})
		return
	}
	code, err := c.currentCode(time.Now())
	if err != nil {
		writeError(w, http.StatusInternalServerError, "暂时无法生成配对码")
		return
	}
	ips := c.cfg.lanAddresses()
	ip := r.URL.Query().Get("host")
	if !slices.Contains(ips, ip) {
		ip = ips[0]
	}
	endpoint := "https://" + net.JoinHostPort(ip, strconv.Itoa(c.cfg.statusPort))
	payload, err := json.Marshal(struct {
		Version  int    `json:"v"`
		Endpoint string `json:"endpoint"`
		Pin      string `json:"pin"`
		Code     string `json:"code"`
	}{protocolVersion, endpoint, c.id.file.Pin, code})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "暂时无法生成配对码")
		return
	}
	image, err := qrcode.Encode(string(payload), qrcode.Medium, 278)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "暂时无法生成配对二维码")
		return
	}
	data := struct {
		Name, Status, Endpoint, Code, Pin string
		Image                             template.URL
		IPs                               []string
	}{c.id.file.Name, c.connections(time.Now()).sourceLabel(), endpoint, code, c.id.file.Pin[:16],
		template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(image)), ips}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
	if err := pairingTemplate.Execute(w, data); err != nil {
		log.Printf("配对页: %v", err)
	}
}

func (cfg config) lanAddresses() []string {
	if ip := net.ParseIP(cfg.bind); ip != nil && !ip.IsUnspecified() {
		return []string{cfg.bind}
	}
	interfaces, _ := net.Interfaces()
	priority := func(iface net.Interface) int {
		name := strings.ToLower(iface.Name)
		if strings.HasPrefix(name, "wlan") || strings.HasPrefix(name, "wi-fi") ||
			strings.HasPrefix(name, "ethernet") || strings.HasPrefix(name, "以太网") {
			return 0
		}
		return 1
	}
	slices.SortStableFunc(interfaces, func(a, b net.Interface) int { return priority(a) - priority(b) })
	var ips []string
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, _ := iface.Addrs()
		for _, address := range addresses {
			ip, _, _ := net.ParseCIDR(address.String())
			if ip.To4() != nil && ip.IsPrivate() && !slices.Contains(ips, ip.String()) {
				ips = append(ips, ip.String())
			}
		}
	}
	if len(ips) == 0 {
		ips = append(ips, "127.0.0.1")
	}
	return ips
}

func newHTTPServer(handler http.HandlerFunc) *http.Server {
	return &http.Server{
		Handler: handler, ReadHeaderTimeout: 4 * time.Second, ReadTimeout: 8 * time.Second,
		WriteTimeout: 8 * time.Second, IdleTimeout: 4 * time.Second, MaxHeaderBytes: 16 * 1024,
	}
}

func (c *collector) serve() (func(), error) {
	bind := net.JoinHostPort(c.cfg.bind, strconv.Itoa(c.cfg.statusPort))
	network := "tcp"
	if net.ParseIP(c.cfg.bind).To4() != nil {
		network = "tcp4"
	}
	apiListener, err := net.Listen(network, bind)
	if err != nil {
		return nil, fmt.Errorf("无法打开局域网端口 %d\n%w", c.cfg.statusPort, err)
	}
	portalListener, err := net.Listen("tcp4", c.cfg.managementHost())
	if err != nil {
		apiListener.Close()
		return nil, fmt.Errorf("无法打开本机配对页端口 %d\n%w", c.cfg.uiPort, err)
	}
	local := newHTTPServer(c.handlePortal)
	api := newHTTPServer(c.handleAPI)
	api.TLSConfig = &tls.Config{Certificates: []tls.Certificate{c.id.cert}, MinVersion: tls.VersionTLS12}
	go func() {
		if err := api.ServeTLS(apiListener, "", ""); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("状态接口: %v", err)
		}
	}()
	go func() {
		if err := local.Serve(portalListener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("配对端口: %v", err)
		}
	}()
	ctx, cancel := context.WithCancel(context.Background())
	go c.sample(ctx)
	return func() {
		cancel()
		api.Close()
		local.Close()
	}, nil
}
