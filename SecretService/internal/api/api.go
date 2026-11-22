package api

import (
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/JackLanger/DMP/SecretService/internal/auth"
	"github.com/JackLanger/DMP/SecretService/internal/store"
	"github.com/gin-gonic/gin"
	"modernc.org/sqlite"
)

type API struct {
	store            *store.Store
	identityProvider auth.IdentityProvider
	authorizer       auth.Authorizer
	adminSecret      [32]byte
	serviceSecret    [32]byte
	limiter          *rateLimiter
}

type credentials struct {
	Username string `json:"username" binding:"required"`
	Password string `json:"password"`
	Token    string `json:"token"`
}

func New(s *store.Store, adminSecret, serviceSecret string) http.Handler {
	return newAPI(s, adminSecret, serviceSecret, auth.NewLocalProvider(s), auth.AllowAuthenticated{}).routes()
}

func NewWithAuth(s *store.Store, adminSecret, serviceSecret string, provider auth.IdentityProvider, authorizer auth.Authorizer) (http.Handler, error) {
	if provider == nil || authorizer == nil {
		return nil, errors.New("identity provider and authorizer are required")
	}
	return newAPI(s, adminSecret, serviceSecret, provider, authorizer).routes(), nil
}

func newAPI(s *store.Store, adminSecret, serviceSecret string, provider auth.IdentityProvider, authorizer auth.Authorizer) *API {
	return &API{store: s, identityProvider: provider, authorizer: authorizer,
		adminSecret: sha256.Sum256([]byte(adminSecret)), serviceSecret: sha256.Sum256([]byte(serviceSecret)),
		limiter: &rateLimiter{entries: map[string]window{}}}
}

func (a *API) routes() http.Handler {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 8192)
		c.Next()
	})
	r.POST("/v1/accounts", a.limit, a.adminOnly, a.createUser)
	r.POST("/v1/login", a.limit, a.login)
	r.POST("/v1/refresh", a.limit, a.refresh)
	r.POST("/v1/logout", a.limit, a.logout)
	r.POST("/v1/introspect", a.serviceOnly, a.introspect)
	g := r.Group("/v1/groups", a.userOnly)
	g.GET("", a.authorizeGroup(auth.ActionList), a.listGroups)
	g.POST("", a.authorizeGroup(auth.ActionCreate), a.createGroup)
	g.GET("/:id/members", a.authorizeGroup(auth.ActionListMembers), a.listMembers)
	g.PUT("/:id/members/:username", a.authorizeGroup(auth.ActionAddMember), a.addMember)
	g.DELETE("/:id/members/:username", a.authorizeGroup(auth.ActionRemoveMember), a.removeMember)
	return r
}

func secretMatches(value string, expected [32]byte) bool {
	actual := sha256.Sum256([]byte(value))
	return value != "" && subtle.ConstantTimeCompare(actual[:], expected[:]) == 1
}

func (a *API) adminOnly(c *gin.Context) {
	if !secretMatches(c.GetHeader("X-Admin-Secret"), a.adminSecret) {
		c.AbortWithStatus(http.StatusUnauthorized)
		return
	}
	c.Next()
}

func (a *API) serviceOnly(c *gin.Context) {
	if !secretMatches(c.GetHeader("X-Service-Secret"), a.serviceSecret) {
		c.AbortWithStatus(http.StatusForbidden)
		return
	}
	c.Next()
}

func (a *API) userOnly(c *gin.Context) {
	p, err := a.authenticate(c.Request)
	if errors.Is(err, auth.ErrInvalidCredentials) {
		c.AbortWithStatus(http.StatusUnauthorized)
		return
	}
	if err != nil {
		a.failure(c, err)
		c.Abort()
		return
	}
	c.Set("principal", p)
	c.Next()
}

func principal(c *gin.Context) auth.Identity {
	return c.MustGet("principal").(auth.Identity)
}

func (a *API) authorizeGroup(action auth.Action) gin.HandlerFunc {
	return func(c *gin.Context) {
		resource := auth.Resource{Type: "group", ID: c.Param("id"), Subject: c.Param("username")}
		if err := a.authorizer.Authorize(c.Request.Context(), principal(c), resource, action); err != nil {
			a.failure(c, err)
			c.Abort()
			return
		}
		c.Next()
	}
}

func (a *API) failure(c *gin.Context, err error) {
	var sqliteError *sqlite.Error
	switch {
	case errors.Is(err, auth.ErrInvalidCredentials), errors.Is(err, store.ErrInvalidCredentials):
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid credentials"})
	case errors.Is(err, auth.ErrForbidden), errors.Is(err, store.ErrForbidden):
		c.JSON(http.StatusForbidden, gin.H{"error": "operation not permitted"})
	case errors.Is(err, auth.ErrSessionsUnsupported):
		c.JSON(http.StatusNotImplemented, gin.H{"error": "session management unsupported"})
	case errors.Is(err, store.ErrInvalidInput):
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid input"})
	case errors.As(err, &sqliteError) && (sqliteError.Code() == 2067 || sqliteError.Code() == 1555):
		c.JSON(http.StatusConflict, gin.H{"error": "resource already exists"})
	default:
		log.Printf("SecretService operation failed: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "internal error"})
	}
}

func bind(c *gin.Context, request any) bool {
	if err := c.ShouldBindJSON(request); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid request"})
		return false
	}
	return true
}

func (a *API) createUser(c *gin.Context) {
	var request credentials
	if !bind(c, &request) {
		return
	}
	id, err := a.store.CreateUser(c.Request.Context(), request.Username, request.Password)
	if err != nil {
		a.failure(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"userId": id})
}

func (a *API) login(c *gin.Context) {
	var request credentials
	if !bind(c, &request) {
		return
	}
	identity, err := a.identityProvider.Authenticate(c.Request.Context(), auth.Credentials{Username: request.Username, Password: request.Password})
	if err != nil {
		a.failure(c, err)
		return
	}
	if identity == nil || identity.Tokens == nil || identity.Tokens.AccessToken == "" {
		a.failure(c, errors.New("identity provider returned no login tokens"))
		return
	}
	c.JSON(http.StatusOK, identity.Tokens)
}

func (a *API) refresh(c *gin.Context) {
	var request credentials
	if !bind(c, &request) {
		return
	}
	sessions, ok := a.identityProvider.(auth.SessionManager)
	if !ok {
		a.failure(c, auth.ErrSessionsUnsupported)
		return
	}
	tokens, err := sessions.Refresh(c.Request.Context(), request.Username, request.Token)
	if err != nil {
		a.failure(c, err)
		return
	}
	if tokens == nil || tokens.AccessToken == "" {
		a.failure(c, errors.New("identity provider returned no refresh tokens"))
		return
	}
	c.JSON(http.StatusOK, tokens)
}

func (a *API) logout(c *gin.Context) {
	var request credentials
	if !bind(c, &request) {
		return
	}
	sessions, ok := a.identityProvider.(auth.SessionManager)
	if !ok {
		a.failure(c, auth.ErrSessionsUnsupported)
		return
	}
	if err := sessions.Logout(c.Request.Context(), request.Username, request.Token); err != nil {
		a.failure(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (a *API) introspect(c *gin.Context) {
	var request credentials
	if !bind(c, &request) {
		return
	}
	p, err := a.validateIdentity(c.Request.Context(), request.Username, request.Token)
	if err != nil {
		a.failure(c, err)
		return
	}
	c.JSON(http.StatusOK, p)
}

func (a *API) listGroups(c *gin.Context) {
	groups, err := a.store.ListGroups(c.Request.Context(), principal(c).UserID)
	if err != nil {
		a.failure(c, err)
		return
	}
	c.JSON(http.StatusOK, groups)
}

func (a *API) createGroup(c *gin.Context) {
	var request struct {
		Name string `json:"name" binding:"required"`
	}
	if !bind(c, &request) {
		return
	}
	id, err := a.store.CreateGroup(c.Request.Context(), principal(c).UserID, request.Name)
	if err != nil {
		a.failure(c, err)
		return
	}
	c.JSON(http.StatusCreated, gin.H{"id": id})
}

func (a *API) addMember(c *gin.Context) {
	err := a.store.AddGroupMember(c.Request.Context(), principal(c).UserID, c.Param("id"), c.Param("username"))
	if err != nil {
		a.failure(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

func (a *API) listMembers(c *gin.Context) {
	members, err := a.store.ListGroupMembers(c.Request.Context(), principal(c).UserID, c.Param("id"))
	if err != nil {
		a.failure(c, err)
		return
	}
	c.JSON(http.StatusOK, members)
}

func (a *API) removeMember(c *gin.Context) {
	err := a.store.RemoveGroupMember(c.Request.Context(), principal(c).UserID, c.Param("id"), c.Param("username"))
	if err != nil {
		a.failure(c, err)
		return
	}
	c.Status(http.StatusNoContent)
}

type window struct {
	start time.Time
	count int
}

type rateLimiter struct {
	mu      sync.Mutex
	entries map[string]window
}

func (a *API) limit(c *gin.Context) {
	host, _, err := net.SplitHostPort(c.Request.RemoteAddr)
	if err != nil {
		host = c.Request.RemoteAddr
	}
	now := time.Now()
	a.limiter.mu.Lock()
	entry := a.limiter.entries[host]
	if now.Sub(entry.start) >= time.Minute {
		entry = window{start: now}
	}
	entry.count++
	if len(a.limiter.entries) >= 4096 {
		for key, existing := range a.limiter.entries {
			if now.Sub(existing.start) >= time.Minute {
				delete(a.limiter.entries, key)
			}
		}
	}
	allowed := entry.count <= 30 && (len(a.limiter.entries) < 4096 || !a.limiter.entries[host].start.IsZero())
	if allowed {
		a.limiter.entries[host] = entry
	}
	a.limiter.mu.Unlock()
	if !allowed {
		c.AbortWithStatus(http.StatusTooManyRequests)
		return
	}
	c.Next()
}
