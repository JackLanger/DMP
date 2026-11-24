package auth

import (
	"context"
	"errors"
	"regexp"

	"github.com/JackLanger/DMP/SecretService/internal/store"
)

var localTokenPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

type LocalProvider struct {
	store *store.Store
}

func NewLocalProvider(s *store.Store) *LocalProvider {
	return &LocalProvider{store: s}
}

func (p *LocalProvider) Authenticate(ctx context.Context, credentials Credentials) (*Identity, error) {
	tokens, err := p.store.Login(ctx, credentials.Username, credentials.Password)
	if err != nil {
		return nil, localError(err)
	}
	identity, err := p.Validate(ctx, tokens.AccessToken)
	if err != nil {
		return nil, err
	}
	identity.Tokens = convertTokens(tokens)
	return identity, nil
}

func (p *LocalProvider) Validate(ctx context.Context, token string) (*Identity, error) {
	if !localTokenPattern.MatchString(token) {
		return nil, ErrInvalidCredentials
	}
	principal, err := p.store.ValidateToken(ctx, token)
	if err != nil {
		return nil, localError(err)
	}
	return &Identity{UserID: principal.UserID, Username: principal.Username, GroupIDs: principal.GroupIDs}, nil
}

func (p *LocalProvider) Refresh(ctx context.Context, username, token string) (*Tokens, error) {
	tokens, err := p.store.Refresh(ctx, username, token)
	if err != nil {
		return nil, localError(err)
	}
	return convertTokens(tokens), nil
}

func (p *LocalProvider) Logout(ctx context.Context, username, token string) error {
	return localError(p.store.Logout(ctx, username, token))
}

func convertTokens(t store.Tokens) *Tokens {
	return &Tokens{AccessToken: t.AccessToken, RefreshToken: t.RefreshToken,
		AccessExpiresAt: t.AccessExpiresAt, RefreshExpiresAt: t.RefreshExpiresAt}
}

func localError(err error) error {
	if errors.Is(err, store.ErrInvalidCredentials) {
		return ErrInvalidCredentials
	}
	return err
}
