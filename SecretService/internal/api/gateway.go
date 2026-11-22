package api

import (
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/JackLanger/DMP/SecretService/internal/store"
)

var (
	usernamePattern = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$`)
	tokenPattern    = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
)

func (a *API) authenticate(r *http.Request) (store.Principal, error) {
	users := r.Header.Values("X-User")
	auth := r.Header.Values("Authorization")
	if len(users) != 1 || len(auth) != 1 || !usernamePattern.MatchString(users[0]) ||
		!strings.HasPrefix(auth[0], "Bearer ") || !tokenPattern.MatchString(strings.TrimPrefix(auth[0], "Bearer ")) {
		return store.Principal{}, store.ErrInvalidCredentials
	}
	return a.store.Validate(r.Context(), users[0], strings.TrimPrefix(auth[0], "Bearer "))
}

// NewGateway exposes SecretService APIs and an authenticated proxy to a fixed FileServer origin.
func NewGateway(s *store.Store, adminSecret, serviceSecret, upstream string) (http.Handler, error) {
	target, err := url.Parse(upstream)
	if err != nil || target == nil || (target.Scheme != "http" && target.Scheme != "https") ||
		target.Hostname() == "" || target.User != nil || (target.Path != "" && target.Path != "/") ||
		target.RawQuery != "" || target.ForceQuery || target.Fragment != "" {
		return nil, fmt.Errorf("FileServer upstream must be an HTTP(S) origin without credentials, path, query or fragment")
	}
	a := newAPI(s, adminSecret, serviceSecret)
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
		if _, err := a.authenticate(r); err != nil {
			if errors.Is(err, store.ErrInvalidCredentials) {
				http.Error(w, "unauthorized", http.StatusUnauthorized)
			} else {
				log.Printf("FileServer authentication failed: %v", err)
				http.Error(w, "authentication unavailable", http.StatusServiceUnavailable)
			}
			return
		}
		proxy.ServeHTTP(w, r)
	}), nil
}
