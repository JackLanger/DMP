package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadEnvironmentFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ".env")
	if err := os.WriteFile(path, []byte("DMP_TEST_DOTENV_VALUE=\"value with spaces\"\nDMP_TEST_DOTENV_EXISTING=from-file\nDMP_TEST_DOTENV_EMPTY=from-file\n"), 0600); err != nil {
		t.Fatal(err)
	}

	t.Setenv("DMP_TEST_DOTENV_VALUE", "")
	if err := os.Unsetenv("DMP_TEST_DOTENV_VALUE"); err != nil {
		t.Fatal(err)
	}
	t.Setenv("DMP_TEST_DOTENV_EXISTING", "from-environment")
	t.Setenv("DMP_TEST_DOTENV_EMPTY", "")

	if err := loadEnvironmentFile(path); err != nil {
		t.Fatal(err)
	}
	if got := os.Getenv("DMP_TEST_DOTENV_VALUE"); got != "value with spaces" {
		t.Fatalf("file value = %q", got)
	}
	if got := os.Getenv("DMP_TEST_DOTENV_EXISTING"); got != "from-environment" {
		t.Fatalf("existing environment was replaced: %q", got)
	}
	if got := os.Getenv("DMP_TEST_DOTENV_EMPTY"); got != "" {
		t.Fatalf("empty existing environment was replaced: %q", got)
	}
}

func TestEnvironmentFilePath(t *testing.T) {
	root := t.TempDir()
	service := filepath.Join(root, "SecretService")
	if err := os.Mkdir(service, 0700); err != nil {
		t.Fatal(err)
	}
	for _, directory := range []string{root, service} {
		got, err := environmentFilePath(directory)
		if err != nil || got != filepath.Join(service, ".env") {
			t.Fatalf("path for %s = %s, error %v", directory, got, err)
		}
	}
}

func TestLoadEnvironmentFileErrors(t *testing.T) {
	dir := t.TempDir()
	if err := loadEnvironmentFile(filepath.Join(dir, "missing.env")); err != nil {
		t.Fatalf("missing file: %v", err)
	}

	path := filepath.Join(dir, "invalid.env")
	if err := os.WriteFile(path, []byte("SECRET=\"unterminated\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := loadEnvironmentFile(path); err == nil || err.Error() != "invalid SecretService .env file" {
		t.Fatalf("invalid file: %v", err)
	}
	if err := loadEnvironmentFile(dir); err == nil || err.Error() != "cannot read SecretService .env file" {
		t.Fatalf("unreadable file: %v", err)
	}
}

func TestCORSForTransfers(t *testing.T) {
	handler := withCORS(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}), map[string]bool{"https://app.example": true})
	r := httptest.NewRequest("OPTIONS", "/files/transfers/id", nil)
	r.Header.Set("Origin", "https://app.example")
	r.Header.Set("Access-Control-Request-Method", "PATCH")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != http.StatusNoContent || !strings.Contains(w.Header().Get("Access-Control-Allow-Methods"), "PATCH") ||
		!strings.Contains(w.Header().Get("Access-Control-Allow-Headers"), "Content-Range") ||
		!strings.Contains(w.Header().Get("Access-Control-Expose-Headers"), "Upload-Offset") {
		t.Fatalf("preflight: %d %v", w.Code, w.Header())
	}
	r.Header.Set("Origin", "https://untrusted.example")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatalf("untrusted origin: %d", w.Code)
	}
}
