package auth

import (
	"context"
	"errors"
	"time"
)

var (
	ErrInvalidCredentials  = errors.New("invalid credentials")
	ErrForbidden           = errors.New("forbidden")
	ErrSessionsUnsupported = errors.New("session management unsupported")
)

type Credentials struct {
	Username string
	Password string
}

type Tokens struct {
	AccessToken      string    `json:"accessToken"`
	RefreshToken     string    `json:"refreshToken"`
	AccessExpiresAt  time.Time `json:"accessExpiresAt"`
	RefreshExpiresAt time.Time `json:"refreshExpiresAt"`
}

type Identity struct {
	UserID   string   `json:"userId"`
	Username string   `json:"username"`
	GroupIDs []string `json:"groupIds"`
	Tokens   *Tokens  `json:"-"`
}

type Resource struct {
	Type    string
	ID      string
	Subject string
}

type Action string

const (
	ActionList         Action = "list"
	ActionCreate       Action = "create"
	ActionListMembers  Action = "list-members"
	ActionAddMember    Action = "add-member"
	ActionRemoveMember Action = "remove-member"
)

type IdentityProvider interface {
	Authenticate(ctx context.Context, credentials Credentials) (*Identity, error)
	Validate(ctx context.Context, token string) (*Identity, error)
}

type Authorizer interface {
	Authorize(ctx context.Context, identity Identity, resource Resource, action Action) error
}

// SessionManager is optional; providers without it do not support refresh or logout.
type SessionManager interface {
	Refresh(ctx context.Context, username, token string) (*Tokens, error)
	Logout(ctx context.Context, username, token string) error
}

// AllowAuthenticated preserves the existing gateway policy. Group operations
// remain subject to the store's membership and administrator checks.
type AllowAuthenticated struct{}

func (AllowAuthenticated) Authorize(_ context.Context, _ Identity, _ Resource, _ Action) error {
	return nil
}
