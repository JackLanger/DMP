package api

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/JackLanger/DMP/SecretService/internal/auth"
	"github.com/JackLanger/DMP/SecretService/internal/store"
)

type testProvider struct {
	credentials auth.Credentials
	token       string
	identity    *auth.Identity
	err         error
}

func (p *testProvider) Authenticate(_ context.Context, credentials auth.Credentials) (*auth.Identity, error) {
	p.credentials = credentials
	return p.identity, p.err
}

func (p *testProvider) Validate(_ context.Context, token string) (*auth.Identity, error) {
	p.token = token
	return p.identity, p.err
}

type testAuthorizer struct {
	identity auth.Identity
	resource auth.Resource
	action   auth.Action
	err      error
	calls    int
}

type testSessionProvider struct {
	*testProvider
	username string
	token    string
}

func (p *testSessionProvider) Refresh(_ context.Context, username, token string) (*auth.Tokens, error) {
	p.username, p.token = username, token
	return &auth.Tokens{AccessToken: "rotated"}, nil
}

func (p *testSessionProvider) Logout(_ context.Context, username, token string) error {
	p.username, p.token = username, token
	return nil
}

func (a *testAuthorizer) Authorize(_ context.Context, identity auth.Identity, resource auth.Resource, action auth.Action) error {
	a.identity, a.resource, a.action = identity, resource, action
	a.calls++
	return a.err
}

func TestCustomAuthenticationAndAuthorization(t *testing.T) {
	s, err := store.Open(context.Background(), ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	userID, err := s.CreateUser(context.Background(), "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	provider := &testProvider{identity: &auth.Identity{
		UserID: userID, Username: "alice", GroupIDs: []string{},
		Tokens: &auth.Tokens{AccessToken: "custom.token", RefreshToken: "refresh"},
	}}
	authorizer := &testAuthorizer{}
	upstreamCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		upstreamCalls++
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	handler, err := NewGatewayWithAuth(s, "admin", "service", upstream.URL, provider, authorizer)
	if err != nil {
		t.Fatal(err)
	}
	request := func(method, path, body string, headers map[string]string) *httptest.ResponseRecorder {
		t.Helper()
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		for key, value := range headers {
			r.Header.Set(key, value)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	login := request("POST", "/v1/login", `{"username":"alice","password":"password"}`, nil)
	if login.Code != http.StatusOK || !strings.Contains(login.Body.String(), `"accessToken":"custom.token"`) ||
		provider.credentials != (auth.Credentials{Username: "alice", Password: "password"}) {
		t.Fatalf("login: status=%d body=%s credentials=%+v", login.Code, login.Body, provider.credentials)
	}
	headers := map[string]string{"X-User": "ALICE", "Authorization": "Bearer custom.token"}
	response := request("GET", "/files/test", "", headers)
	if response.Code != http.StatusNoContent || upstreamCalls != 1 || provider.token != "custom.token" ||
		authorizer.identity.UserID != userID || authorizer.identity.Tokens != nil ||
		authorizer.resource != (auth.Resource{Type: "file", ID: "/files/test"}) || authorizer.action != auth.Action("GET") {
		t.Fatalf("file: status=%d calls=%d authorizer=%+v", response.Code, upstreamCalls, authorizer)
	}
	response = request("POST", "/v1/introspect", `{"username":"alice","token":"custom.token"}`, map[string]string{"X-Service-Secret": "service"})
	if response.Code != http.StatusOK || strings.Contains(response.Body.String(), "custom.token") {
		t.Fatalf("introspect: status=%d body=%s", response.Code, response.Body)
	}
	response = request("PUT", "/v1/groups/group-id/members/bob", "", headers)
	if response.Code != http.StatusForbidden || authorizer.resource != (auth.Resource{Type: "group", ID: "group-id", Subject: "bob"}) ||
		authorizer.action != auth.ActionAddMember {
		t.Fatalf("group: status=%d authorizer=%+v", response.Code, authorizer)
	}
	authorizer.err = auth.ErrForbidden
	response = request("GET", "/files/test", "", headers)
	if response.Code != http.StatusForbidden || upstreamCalls != 1 {
		t.Fatalf("denied file: status=%d calls=%d", response.Code, upstreamCalls)
	}
	response = request("GET", "/v1/groups", "", headers)
	if response.Code != http.StatusForbidden {
		t.Fatalf("denied groups: %d", response.Code)
	}
	authorizer.err = errors.New("policy unavailable")
	response = request("GET", "/files/test", "", headers)
	if response.Code != http.StatusServiceUnavailable || upstreamCalls != 1 {
		t.Fatalf("policy error: status=%d calls=%d", response.Code, upstreamCalls)
	}
	authorizer.err = nil
	calls := authorizer.calls
	headers["X-User"] = "bob"
	response = request("GET", "/files/test", "", headers)
	if response.Code != http.StatusUnauthorized || authorizer.calls != calls {
		t.Fatalf("wrong user: status=%d calls=%d", response.Code, authorizer.calls)
	}
	provider.err = auth.ErrInvalidCredentials
	response = request("POST", "/v1/login", `{"username":"alice","password":"bad"}`, nil)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("invalid login: %d", response.Code)
	}
	provider.err = errors.New("provider unavailable")
	headers["X-User"] = "alice"
	response = request("GET", "/files/test", "", headers)
	if response.Code != http.StatusServiceUnavailable || upstreamCalls != 1 {
		t.Fatalf("provider error: status=%d calls=%d", response.Code, upstreamCalls)
	}
	provider.err, provider.identity = nil, nil
	response = request("GET", "/files/test", "", headers)
	if response.Code != http.StatusServiceUnavailable || upstreamCalls != 1 {
		t.Fatalf("nil identity: status=%d calls=%d", response.Code, upstreamCalls)
	}
	response = request("POST", "/v1/login", `{"username":"alice","password":"password"}`, nil)
	if response.Code != http.StatusInternalServerError {
		t.Fatalf("nil login identity: %d", response.Code)
	}
	for _, path := range []string{"/v1/refresh", "/v1/logout"} {
		response = request("POST", path, `{"username":"alice","token":"refresh"}`, nil)
		if response.Code != http.StatusNotImplemented {
			t.Fatalf("%s without session manager: %d", path, response.Code)
		}
	}
}

func TestAuthDependenciesRequired(t *testing.T) {
	if _, err := NewWithAuth(nil, "", "", nil, auth.AllowAuthenticated{}); err == nil {
		t.Fatal("accepted nil provider")
	}
	if _, err := NewGatewayWithAuth(nil, "", "", "http://localhost", &testProvider{}, nil); err == nil {
		t.Fatal("accepted nil authorizer")
	}
}

func TestCustomSessionManager(t *testing.T) {
	provider := &testSessionProvider{testProvider: &testProvider{}}
	handler, err := NewWithAuth(nil, "admin", "service", provider, auth.AllowAuthenticated{})
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		path string
		want int
	}{
		{"/v1/refresh", http.StatusOK},
		{"/v1/logout", http.StatusNoContent},
	} {
		req := httptest.NewRequest("POST", tc.path, strings.NewReader(`{"username":"alice","token":"refresh"}`))
		req.Header.Set("Content-Type", "application/json")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, req)
		if response.Code != tc.want || provider.username != "alice" || provider.token != "refresh" {
			t.Fatalf("%s: status=%d username=%s token=%s", tc.path, response.Code, provider.username, provider.token)
		}
		if tc.path == "/v1/refresh" && !strings.Contains(response.Body.String(), `"accessToken":"rotated"`) {
			t.Fatalf("refresh response: %s", response.Body)
		}
	}
}
