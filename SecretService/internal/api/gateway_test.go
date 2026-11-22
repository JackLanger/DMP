package api

import (
	"bytes"
	"context"
	"database/sql"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/JackLanger/DMP/SecretService/internal/store"
)

func TestGatewayAuthenticationAndProxy(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "auth.db")
	s, err := store.Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	if _, err := s.CreateUser(ctx, "alice", "long password 123"); err != nil {
		t.Fatal(err)
	}
	tokens, err := s.Login(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		for _, name := range []string{"Authorization", "X-User", "X-Admin-Secret", "X-Service-Secret", "X-Forwarded-User", "X-Forwarded-For", "Cookie", "Origin"} {
			if r.Header.Get(name) != "" {
				t.Errorf("upstream received %s", name)
			}
		}
		if r.URL.RequestURI() != "/files/transfers/abc?x=1" || r.Method != http.MethodPatch {
			t.Errorf("unexpected upstream request: %s %s", r.Method, r.URL.RequestURI())
		}
		body, err := io.ReadAll(r.Body)
		if err != nil || len(body) != 64*1024 || r.Header.Get("Content-Range") != "bytes 0-65535/65536" {
			t.Errorf("body length %d, error %v, content range %q", len(body), err, r.Header.Get("Content-Range"))
		}
		w.Header().Set("Upload-Offset", "65536")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	handler, err := NewGateway(s, "admin", "service", upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	request := func(token string) *http.Request {
		r := httptest.NewRequest(http.MethodPatch, "/files/transfers/abc?x=1", bytes.NewReader(bytes.Repeat([]byte("x"), 64*1024)))
		r.Header.Set("X-User", "alice")
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("Content-Range", "bytes 0-65535/65536")
		return r
	}
	for _, invalid := range []*http.Request{
		httptest.NewRequest("GET", "/files/all", nil),
		request(strings.Repeat("x", 43)),
		request("invalid"),
	} {
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, invalid)
		if response.Code != http.StatusUnauthorized {
			t.Fatalf("invalid credentials: %d", response.Code)
		}
	}
	duplicate := request(tokens.AccessToken)
	duplicate.Header.Add("X-User", "bob")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, duplicate)
	if response.Code != http.StatusUnauthorized || calls.Load() != 0 {
		t.Fatalf("duplicate credentials: status=%d calls=%d", response.Code, calls.Load())
	}
	duplicate = request(tokens.AccessToken)
	duplicate.Header.Add("Authorization", "Bearer "+tokens.AccessToken)
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, duplicate)
	if response.Code != http.StatusUnauthorized || calls.Load() != 0 {
		t.Fatalf("duplicate authorization: status=%d calls=%d", response.Code, calls.Load())
	}
	largeJSON := httptest.NewRequest("POST", "/v1/login", strings.NewReader(strings.Repeat(" ", 8193)+`{}`))
	largeJSON.Header.Set("Content-Type", "application/json")
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, largeJSON)
	if response.Code != http.StatusBadRequest || calls.Load() != 0 {
		t.Fatalf("oversized JSON: status=%d calls=%d", response.Code, calls.Load())
	}
	good := request(tokens.AccessToken)
	for _, name := range []string{"X-Admin-Secret", "X-Service-Secret", "X-Forwarded-User", "X-Forwarded-For", "Cookie", "Origin"} {
		good.Header.Set(name, "untrusted")
	}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, good)
	if response.Code != http.StatusNoContent || response.Header().Get("Upload-Offset") != "65536" ||
		response.Header().Get("Access-Control-Allow-Origin") != "" || calls.Load() != 1 {
		t.Fatalf("proxy response: %d, headers %v, calls %d", response.Code, response.Header(), calls.Load())
	}
	db, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.ExecContext(ctx, `UPDATE access_tokens SET expires_at = 0`); err != nil {
		t.Fatal(err)
	}
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request(tokens.AccessToken))
	if response.Code != http.StatusUnauthorized || calls.Load() != 1 {
		t.Fatalf("expired token: status=%d calls=%d", response.Code, calls.Load())
	}
	tokens, err = s.Login(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Logout(ctx, "alice", tokens.RefreshToken); err != nil {
		t.Fatal(err)
	}
	response = httptest.NewRecorder()
	handler.ServeHTTP(response, request(tokens.AccessToken))
	if response.Code != http.StatusUnauthorized || calls.Load() != 1 {
		t.Fatalf("revoked token: status=%d calls=%d", response.Code, calls.Load())
	}
}

func TestGatewayPathsAndUpstreamErrors(t *testing.T) {
	s, err := store.Open(context.Background(), ":memory:")
	if err != nil {
		t.Fatal(err)
	}

	defer s.Close()
	for _, invalid := range []string{"", "/files", "http://example.test/path", "http://user@example.test", "http://example.test?x=1", "ftp://example.test"} {
		if _, err := NewGateway(s, "admin", "service", invalid); err == nil {
			t.Errorf("accepted upstream %q", invalid)
		}
	}
	server := httptest.NewServer(http.NotFoundHandler())
	address := server.URL
	server.Close()
	handler, err := NewGateway(s, "admin", "service", address)
	if err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	if _, err := s.CreateUser(ctx, "alice", "long password 123"); err != nil {
		t.Fatal(err)
	}
	tokens, err := s.Login(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path string
		want int
	}{
		{"/", http.StatusBadGateway},
		{"/files/all", http.StatusBadGateway},
		{"/files/%2e%2e/v1/login", http.StatusBadRequest},
		{"/files%2fall", http.StatusBadRequest},
		{"/not-files", http.StatusNotFound},
	} {
		t.Run(tc.path, func(t *testing.T) {
			r := httptest.NewRequest("GET", tc.path, nil)
			r.Header.Set("X-User", "alice")
			r.Header.Set("Authorization", "Bearer "+tokens.AccessToken)
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != tc.want {
				t.Fatalf("got %d, want %d", w.Code, tc.want)
			}
		})
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	r := httptest.NewRequest("GET", "/files/all", nil)
	r.Header.Set("X-User", "alice")
	r.Header.Set("Authorization", "Bearer "+tokens.AccessToken)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("closed database: got %d", w.Code)
	}
}
