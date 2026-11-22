package store

import (
	"context"
	"errors"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func testStore(t *testing.T) (*Store, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "secret.db")
	s, err := Open(context.Background(), path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.Close(); err != nil {
			t.Error(err)
		}
	})
	return s, path
}

func TestOpenReportsInitializationFailure(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	s, err := Open(ctx, ":memory:")
	if s != nil || !errors.Is(err, context.Canceled) {
		t.Fatalf("Open with canceled context: store=%v, error=%v", s, err)
	}
}

func TestTokenLifecycle(t *testing.T) {
	ctx := context.Background()
	s, _ := testStore(t)
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }
	id, err := s.CreateUser(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.Login(ctx, "alice", "incorrect password"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("wrong password: %v", err)
	}
	if _, err := s.Login(ctx, "unknown", "incorrect password"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("unknown user: %v", err)
	}
	tokens, err := s.Login(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	principal, err := s.Validate(ctx, "alice", tokens.AccessToken)
	if err != nil || principal.UserID != id {
		t.Fatalf("valid token: %+v, %v", principal, err)
	}
	if _, err := s.Validate(ctx, "other", tokens.AccessToken); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("wrong user accepted: %v", err)
	}
	var stored []byte
	if err := s.db.QueryRowContext(ctx, `SELECT token_hash FROM access_tokens`).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if string(stored) == tokens.AccessToken {
		t.Fatal("access token stored in plaintext")
	}
	now = now.Add(accessLifetime)
	if _, err := s.Validate(ctx, "alice", tokens.AccessToken); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("expired access token accepted: %v", err)
	}
	rotated, err := s.Refresh(ctx, "alice", tokens.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	if rotated.RefreshToken == tokens.RefreshToken || rotated.RefreshExpiresAt != now.Add(refreshLifetime) {
		t.Fatal("refresh token did not rotate with a renewed lifetime")
	}
	if _, err := s.Refresh(ctx, "alice", tokens.RefreshToken); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("reused refresh token accepted: %v", err)
	}
	if err := s.Logout(ctx, "alice", rotated.RefreshToken); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Validate(ctx, "alice", rotated.AccessToken); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("logged-out token accepted: %v", err)
	}
}

func TestConcurrentRefreshHasOneWinner(t *testing.T) {
	ctx := context.Background()
	s, _ := testStore(t)
	if _, err := s.CreateUser(ctx, "alice", "long password 123"); err != nil {
		t.Fatal(err)
	}
	tokens, err := s.Login(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	var winners atomic.Int32
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := s.Refresh(ctx, "alice", tokens.RefreshToken)
			if err == nil {
				winners.Add(1)
			} else if !errors.Is(err, ErrInvalidCredentials) {
				t.Errorf("refresh: %v", err)
			}
		}()
	}
	wg.Wait()
	if winners.Load() != 1 {
		t.Fatalf("expected one successful refresh, got %d", winners.Load())
	}
}

func TestPersistedTokensAndExpiry(t *testing.T) {
	ctx := context.Background()
	s, path := testStore(t)
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }
	if _, err := s.CreateUser(ctx, "alice", "long password 123"); err != nil {
		t.Fatal(err)
	}

	tokens, err := s.Login(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	other, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	other.now = func() time.Time { return now }
	if _, err := other.Validate(ctx, "alice", tokens.AccessToken); err != nil {
		t.Fatalf("persisted token: %v", err)
	}
	now = now.Add(refreshLifetime)
	if _, err := other.Refresh(ctx, "alice", tokens.RefreshToken); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("expired refresh token accepted: %v", err)
	}
	if err := other.Cleanup(ctx); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := other.db.QueryRowContext(ctx, `SELECT count(*) FROM sessions`).Scan(&count); err != nil || count != 0 {
		t.Fatalf("cleanup: count=%d, error=%v", count, err)
	}
}

func TestGroupMembershipIsCurrentAndAdminControlled(t *testing.T) {
	ctx := context.Background()
	s, _ := testStore(t)
	alice, err := s.CreateUser(ctx, "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	bob, err := s.CreateUser(ctx, "bob", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	group, err := s.CreateGroup(ctx, alice, "Friends")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.AddGroupMember(ctx, bob, group, "bob"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("non-admin added member: %v", err)
	}
	if err := s.AddGroupMember(ctx, alice, group, "bob"); err != nil {
		t.Fatal(err)
	}
	tokens, err := s.Login(ctx, "bob", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	principal, err := s.Validate(ctx, "bob", tokens.AccessToken)
	if err != nil || len(principal.GroupIDs) != 1 || principal.GroupIDs[0] != group {
		t.Fatalf("group membership: %+v, %v", principal, err)
	}
	if err := s.RemoveGroupMember(ctx, bob, group, "alice"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("non-admin removed member: %v", err)
	}
	if err := s.RemoveGroupMember(ctx, alice, group, "alice"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("admin removed self: %v", err)
	}
	if err := s.RemoveGroupMember(ctx, alice, group, "bob"); err != nil {
		t.Fatal(err)
	}
	principal, err = s.Validate(ctx, "bob", tokens.AccessToken)
	if err != nil || len(principal.GroupIDs) != 0 {
		t.Fatalf("removed membership: %+v, %v", principal, err)
	}
}
