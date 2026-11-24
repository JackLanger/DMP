package api

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/JackLanger/DMP/SecretService/internal/auth"
	"github.com/JackLanger/DMP/SecretService/internal/store"
)

var (
	usernamePattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$`)
)

func (a *API) authenticate(r *http.Request) (auth.Identity, error) {
	users := r.Header.Values("X-User")
	authorization := r.Header.Values("Authorization")
	if len(users) != 1 || len(authorization) != 1 || !usernamePattern.MatchString(users[0]) ||
		!strings.HasPrefix(authorization[0], "Bearer ") {
		return auth.Identity{}, auth.ErrInvalidCredentials
	}
	return a.validateIdentity(r.Context(), users[0], strings.TrimPrefix(authorization[0], "Bearer "))
}

func (a *API) validateIdentity(ctx context.Context, username, token string) (auth.Identity, error) {
	if !usernamePattern.MatchString(username) || token == "" || strings.ContainsAny(token, " \t\r\n") {
		return auth.Identity{}, auth.ErrInvalidCredentials
	}
	identity, err := a.identityProvider.Validate(ctx, token)
	if err != nil {
		return auth.Identity{}, err
	}
	if identity == nil || identity.UserID == "" || identity.Username == "" {
		return auth.Identity{}, errors.New("identity provider returned an invalid identity")
	}
	if !strings.EqualFold(username, identity.Username) {
		return auth.Identity{}, auth.ErrInvalidCredentials
	}
	result := *identity
	result.Tokens = nil
	return result, nil
}

// NewGateway exposes SecretService APIs and an authenticated proxy to a fixed FileServer origin.
func NewGateway(s *store.Store, adminSecret, serviceSecret, upstream string) (http.Handler, error) {
	return NewGatewayWithAuth(s, adminSecret, serviceSecret, upstream, auth.NewLocalProvider(s), auth.AllowAuthenticated{})
}

func NewGatewayWithAuth(s *store.Store, adminSecret, serviceSecret, upstream string, provider auth.IdentityProvider, authorizer auth.Authorizer) (http.Handler, error) {
	if provider == nil || authorizer == nil {
		return nil, errors.New("identity provider and authorizer are required")
	}
	target, err := url.Parse(upstream)
	if err != nil || target == nil || (target.Scheme != "http" && target.Scheme != "https") ||
		target.Hostname() == "" || target.User != nil || (target.Path != "" && target.Path != "/") ||
		target.RawQuery != "" || target.ForceQuery || target.Fragment != "" {
		return nil, fmt.Errorf("FileServer upstream must be an HTTP(S) origin without credentials, path, query or fragment")
	}
	a := newAPI(s, adminSecret, serviceSecret, provider, authorizer)
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.ResponseHeaderTimeout = 2 * time.Minute
	proxy := &httputil.ReverseProxy{
		Transport: transport,
		Rewrite: func(request *httputil.ProxyRequest) {
			request.SetURL(target)
			for _, name := range []string{"Authorization", "X-User", "X-Admin-Secret", "X-Service-Secret", "Cookie", "Origin", "X-Forwarded-User", "X-Remote-User"} {
				request.Out.Header.Del(name)
			}
		},
		ModifyResponse: func(response *http.Response) error {
			for name := range response.Header {
				if strings.HasPrefix(strings.ToLower(name), "access-control-") ||
					strings.EqualFold(name, "X-Content-Type-Options") ||
					strings.EqualFold(name, "X-Frame-Options") ||
					strings.EqualFold(name, "Referrer-Policy") ||
					strings.EqualFold(name, "Strict-Transport-Security") {
					response.Header.Del(name)
				}
			}
			return nil
		},
		ErrorHandler: func(w http.ResponseWriter, _ *http.Request, err error) {
			log.Printf("FileServer proxy failed: %v", err)
			http.Error(w, "FileServer unavailable", http.StatusBadGateway)
		},
	}
	routes := a.routes()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path := r.URL.Path
		if path != "/" && path != "/files" && !strings.HasPrefix(path, "/files/") {
			routes.ServeHTTP(w, r)
			return
		}
		escaped := strings.ToLower(r.URL.EscapedPath())
		if strings.Contains(escaped, "%2f") || strings.Contains(escaped, "%5c") || strings.Contains(path, "\\") {
			http.Error(w, "invalid path", http.StatusBadRequest)
			return
		}
		for segment := range strings.SplitSeq(path, "/") {
			if segment == "." || segment == ".." {
				http.Error(w, "invalid path", http.StatusBadRequest)
				return
			}
		}
		identity, err := a.authenticate(r)
		if err != nil {
			if errors.Is(err, auth.ErrInvalidCredentials) {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
			} else {
				log.Printf("FileServer authentication failed: %v", err)
				http.Error(w, "authentication unavailable", http.StatusServiceUnavailable)
			}
			return
		}
		err = a.authorizer.Authorize(r.Context(), identity, auth.Resource{Type: "file", ID: path}, auth.Action(r.Method))
		if err != nil {
			if errors.Is(err, auth.ErrForbidden) {
				http.Error(w, "forbidden", http.StatusForbidden)
			} else {
				log.Printf("FileServer authorization failed: %v", err)
				http.Error(w, "authorization unavailable", http.StatusServiceUnavailable)
			}
			return
		}
		proxy.ServeHTTP(w, r)
	}), nil
}
