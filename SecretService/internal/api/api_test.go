package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/JackLanger/DMP/SecretService/internal/store"
	"github.com/gin-gonic/gin"
)

func TestAuthenticationAndGroups(t *testing.T) {
	gin.SetMode(gin.TestMode)
	s, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "auth.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	handler := New(s, "administrator secret", "file server secret")
	request := func(method, path, body string, headers map[string]string, want int) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		for key, value := range headers {
			req.Header.Set(key, value)
		}
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, req)
		if response.Code != want {
			t.Fatalf("%s %s: got %d, want %d: %s", method, path, response.Code, want, response.Body)
		}
		return response
	}
	user := `{"username":"alice","password":"long password 123"}`
	request("POST", "/v1/accounts", user, nil, http.StatusUnauthorized)
	request("POST", "/v1/accounts", user, map[string]string{"X-Admin-Secret": "administrator secret"}, http.StatusCreated)
	request("POST", "/v1/accounts", user, map[string]string{"X-Admin-Secret": "administrator secret"}, http.StatusConflict)
	request("POST", "/v1/login", `{"username":"alice","password":"incorrect"}`, nil, http.StatusUnauthorized)
	login := request("POST", "/v1/login", user, nil, http.StatusOK)
	var tokens store.Tokens
	if err := json.Unmarshal(login.Body.Bytes(), &tokens); err != nil {
		t.Fatal(err)
	}
	validationBody, err := json.Marshal(credentials{Username: "alice", Token: tokens.AccessToken})
	if err != nil {
		t.Fatal(err)
	}
	request("POST", "/v1/introspect", string(validationBody), nil, http.StatusForbidden)
	request("POST", "/v1/introspect", string(validationBody), map[string]string{"X-Service-Secret": "file server secret"}, http.StatusOK)
	headers := map[string]string{"X-User": "alice", "Authorization": "Bearer " + tokens.AccessToken}
	request("POST", "/v1/groups", `{"name":"friends"}`, headers, http.StatusCreated)
	list := request("GET", "/v1/groups", "", headers, http.StatusOK)
	var groups []store.Group
	if err := json.Unmarshal(list.Body.Bytes(), &groups); err != nil || len(groups) != 1 {
		t.Fatalf("group list: %s, error: %v", list.Body, err)
	}
	request("GET", "/v1/groups/"+groups[0].ID+"/members", "", headers, http.StatusOK)
	request("GET", "/v1/groups/nonexistent/members", "", headers, http.StatusForbidden)
	refreshBody, err := json.Marshal(credentials{Username: "alice", Token: tokens.RefreshToken})
	if err != nil {
		t.Fatal(err)
	}
	refreshed := request("POST", "/v1/refresh", string(refreshBody), nil, http.StatusOK)
	request("POST", "/v1/refresh", string(refreshBody), nil, http.StatusUnauthorized)
	var rotated store.Tokens
	if err := json.Unmarshal(refreshed.Body.Bytes(), &rotated); err != nil {
		t.Fatal(err)
	}
	logoutBody, err := json.Marshal(credentials{Username: "alice", Token: rotated.RefreshToken})
	if err != nil {
		t.Fatal(err)
	}
	request("POST", "/v1/logout", string(logoutBody), nil, http.StatusNoContent)
	request("GET", "/v1/groups", "", headers, http.StatusUnauthorized)
}
