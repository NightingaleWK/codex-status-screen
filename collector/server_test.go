package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestExistingPairingAndOnlineCount(t *testing.T) {
	home := t.TempDir()
	old, err := createIdentity()
	if err != nil {
		t.Fatal(err)
	}
	const originalToken = "abcdefghijklmnopqrstuvwxyz0123456789abcdefg"
	old.Clients = []string{digest([]byte(originalToken))}
	// The Node collector stored an unwrapped object in this format.
	raw, err := json.Marshal(map[string]any{
		"id": old.ID, "name": old.Name, "cert": old.PEMCert,
		"key": old.PEMKey, "pin": old.Pin, "clients": old.Clients,
	})
	if err != nil {
		t.Fatal(err)
	}
	writeTestFile(t, filepath.Join(home, "identity.json"), string(raw))
	id, err := openIdentity(home)
	if err != nil {
		t.Fatal(err)
	}
	app := newCollector(config{bind: "127.0.0.1", statusPort: 8767, uiPort: 8768}, id)

	request := httptest.NewRequest(http.MethodGet, "https://127.0.0.1:8767/v1/status", nil)
	recorder := httptest.NewRecorder()
	app.handleAPI(recorder, request)
	if recorder.Code != 401 || app.connections(time.Now()).online != 0 {
		t.Fatal("unauthenticated request changed connection state")
	}
	request.Header.Set("Authorization", "Bearer "+originalToken)
	recorder = httptest.NewRecorder()
	app.handleAPI(recorder, request)
	status := app.connections(time.Now())
	if recorder.Code != 200 || status.paired != 1 || status.online != 1 || id.file.Pin != old.Pin {
		t.Fatalf("existing pairing did not carry over: HTTP %d, connections %+v", recorder.Code, status)
	}
	if app.connections(time.Now().Add(onlineWindow+time.Second)).online != 0 {
		t.Fatal("stale paired phone is still counted as online")
	}

	portal := httptest.NewRecorder()
	app.handlePortal(portal, httptest.NewRequest(http.MethodGet, "http://127.0.0.1:8768/", nil))
	if portal.Code != 200 || !strings.Contains(portal.Body.String(), "data:image/png;base64,") {
		t.Fatal("pairing page did not show a QR code")
	}
	pairBody := `{"code":"` + app.code.value + `"}`
	for i, expected := range []int{200, 401} {
		recorder = httptest.NewRecorder()
		app.handleAPI(recorder, httptest.NewRequest(http.MethodPost, "https://127.0.0.1:8767/v1/pair", strings.NewReader(pairBody)))
		if recorder.Code != expected {
			t.Fatalf("pair request %d, got %d want %d", i, recorder.Code, expected)
		}
	}
	reloaded, err := openIdentity(home)
	if err != nil || len(reloaded.authorized) != 2 || reloaded.file.Pin != old.Pin {
		t.Fatalf("could not preserve identity after authorizing new phone: %v", err)
	}
}
