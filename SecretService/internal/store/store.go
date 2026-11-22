package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"regexp"
	"time"

	"golang.org/x/crypto/bcrypt"
	_ "modernc.org/sqlite"
)

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrInvalidInput       = errors.New("invalid input")
	ErrForbidden          = errors.New("forbidden")
	usernamePattern       = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,63}$`)
)

const (
	accessLifetime  = 15 * time.Minute
	refreshLifetime = 30 * 24 * time.Hour
)

type Store struct {
	db  *sql.DB
	now func() time.Time
}

type Tokens struct {
	AccessToken      string    `json:"accessToken"`
	RefreshToken     string    `json:"refreshToken"`
	AccessExpiresAt  time.Time `json:"accessExpiresAt"`
	RefreshExpiresAt time.Time `json:"refreshExpiresAt"`
}

type Principal struct {
	UserID   string   `json:"userId"`
	Username string   `json:"username"`
	GroupIDs []string `json:"groupIds"`
}

type Group struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	AdminID string `json:"adminId"`
}

type GroupMember struct {
	UserID   string `json:"userId"`
	Username string `json:"username"`
}

func Open(ctx context.Context, dataSource string) (*Store, error) {
	db, err := sql.Open("sqlite", dataSource)
	if err != nil {
		return nil, err
	}
	// A single connection also ensures SQLite pragmas and in-memory databases
	// apply consistently to all operations.
	db.SetMaxOpenConns(1)
	s := &Store{db: db, now: time.Now}
	if err := s.initialize(ctx); err != nil {
		return nil, errors.Join(err, db.Close())
	}
	return s, nil
}

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) initialize(ctx context.Context) error {
	const schema = `
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY NOT NULL,
  username TEXT NOT NULL COLLATE NOCASE UNIQUE,
  password_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS groups (
  id TEXT PRIMARY KEY NOT NULL,
  name TEXT NOT NULL,
  admin_id TEXT NOT NULL REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS group_members (
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  PRIMARY KEY (group_id, user_id)
);
CREATE TABLE IF NOT EXISTS sessions (
  id TEXT PRIMARY KEY NOT NULL,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  refresh_hash BLOB NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  UNIQUE (user_id, refresh_hash)
);
CREATE TABLE IF NOT EXISTS access_tokens (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash BLOB NOT NULL,
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, token_hash)
);
CREATE INDEX IF NOT EXISTS access_tokens_expiry ON access_tokens(expires_at);
CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(refresh_expires_at);`
	_, err := s.db.ExecContext(ctx, schema)
	return err
}

func randomValue() (string, error) {
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(bytes[:]), nil
}

func digest(value string) []byte {
	sum := sha256.Sum256([]byte(value))
	return sum[:]
}

func (s *Store) CreateUser(ctx context.Context, username, password string) (string, error) {
	if !usernamePattern.MatchString(username) || len(password) < 12 || len(password) > 72 {
		return "", ErrInvalidInput
	}
	id, err := randomValue()
	if err != nil {
		return "", err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return "", err
	}
	_, err = s.db.ExecContext(ctx, `INSERT INTO users (id, username, password_hash) VALUES (?, ?, ?)`, id, username, string(hash))
	if err != nil {
		return "", fmt.Errorf("create user: %w", err)
	}
	return id, nil
}

func (s *Store) Login(ctx context.Context, username, password string) (Tokens, error) {
	var id, passwordHash string
	err := s.db.QueryRowContext(ctx, `SELECT id, password_hash FROM users WHERE username = ?`, username).Scan(&id, &passwordHash)
	if errors.Is(err, sql.ErrNoRows) {
		// Perform a bcrypt comparison for unknown users as well.
		_ = bcrypt.CompareHashAndPassword([]byte("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"), []byte(password))
		return Tokens{}, ErrInvalidCredentials
	}
	if err != nil {
		return Tokens{}, err
	}
	if bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte(password)) != nil {
		return Tokens{}, ErrInvalidCredentials
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Tokens{}, err
	}
	defer func(tx *sql.Tx) {
		err := tx.Rollback()
		if err != nil {

		}
	}(tx)
	sessionID, err := randomValue()
	if err != nil {
		return Tokens{}, err
	}
	tokens, err := s.newTokens()
	if err != nil {
		return Tokens{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO sessions (id, user_id, refresh_hash, refresh_expires_at) VALUES (?, ?, ?, ?)`,
		sessionID, id, digest(tokens.RefreshToken), tokens.RefreshExpiresAt.Unix())
	if err != nil {
		return Tokens{}, err
	}
	if err = insertAccess(ctx, tx, id, sessionID, tokens); err != nil {
		return Tokens{}, err
	}
	if err = tx.Commit(); err != nil {
		return Tokens{}, err
	}
	return tokens, nil
}

func (s *Store) newTokens() (Tokens, error) {
	access, err := randomValue()
	if err != nil {
		return Tokens{}, err
	}
	refresh, err := randomValue()
	if err != nil {
		return Tokens{}, err
	}
	now := s.now().UTC().Truncate(time.Second)
	return Tokens{access, refresh, now.Add(accessLifetime), now.Add(refreshLifetime)}, nil
}

func insertAccess(ctx context.Context, tx *sql.Tx, userID, sessionID string, tokens Tokens) error {
	_, err := tx.ExecContext(ctx, `INSERT INTO access_tokens (user_id, token_hash, session_id, expires_at) VALUES (?, ?, ?, ?)`,
		userID, digest(tokens.AccessToken), sessionID, tokens.AccessExpiresAt.Unix())
	return err
}

func (s *Store) Refresh(ctx context.Context, username, refreshToken string) (Tokens, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Tokens{}, err
	}
	defer func(tx *sql.Tx) {
		err := tx.Rollback()
		if err != nil {

		}
	}(tx)
	var userID, sessionID string
	err = tx.QueryRowContext(ctx, `SELECT u.id, s.id FROM sessions s JOIN users u ON u.id = s.user_id
WHERE u.username = ? AND s.refresh_hash = ? AND s.refresh_expires_at > ?`, username, digest(refreshToken), s.now().Unix()).Scan(&userID, &sessionID)
	if errors.Is(err, sql.ErrNoRows) {
		return Tokens{}, ErrInvalidCredentials
	}
	if err != nil {
		return Tokens{}, err
	}
	tokens, err := s.newTokens()
	if err != nil {
		return Tokens{}, err
	}
	_, err = tx.ExecContext(ctx, `UPDATE sessions SET refresh_hash = ?, refresh_expires_at = ? WHERE id = ?`,
		digest(tokens.RefreshToken), tokens.RefreshExpiresAt.Unix(), sessionID)
	if err != nil {
		return Tokens{}, err
	}
	if err = insertAccess(ctx, tx, userID, sessionID, tokens); err != nil {
		return Tokens{}, err
	}
	if err = tx.Commit(); err != nil {
		return Tokens{}, err
	}
	return tokens, nil
}

func (s *Store) Validate(ctx context.Context, username, token string) (Principal, error) {
	var principal Principal
	err := s.db.QueryRowContext(ctx, `SELECT u.id, u.username FROM access_tokens a
JOIN users u ON u.id = a.user_id JOIN sessions s ON s.id = a.session_id
WHERE u.username = ? AND a.token_hash = ? AND a.expires_at > ? AND s.refresh_expires_at > ?`,
		username, digest(token), s.now().Unix(), s.now().Unix()).Scan(&principal.UserID, &principal.Username)
	if errors.Is(err, sql.ErrNoRows) {
		return Principal{}, ErrInvalidCredentials
	}
	if err != nil {
		return Principal{}, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT group_id FROM group_members WHERE user_id = ? ORDER BY group_id`, principal.UserID)
	if err != nil {
		return Principal{}, err
	}
	defer func(rows *sql.Rows) {
		err := rows.Close()
		if err != nil {

		}
	}(rows)
	principal.GroupIDs = []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return Principal{}, err
		}
		principal.GroupIDs = append(principal.GroupIDs, id)
	}
	if err := rows.Err(); err != nil {
		return Principal{}, err
	}
	return principal, nil
}

func (s *Store) Logout(ctx context.Context, username, refreshToken string) error {
	result, err := s.db.ExecContext(ctx, `DELETE FROM sessions WHERE user_id = (SELECT id FROM users WHERE username = ?) AND refresh_hash = ?`,
		username, digest(refreshToken))
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return ErrInvalidCredentials
	}
	return nil
}

func (s *Store) CreateGroup(ctx context.Context, adminID, name string) (string, error) {
	if len(name) == 0 || len(name) > 100 {
		return "", ErrInvalidInput
	}
	id, err := randomValue()
	if err != nil {
		return "", err
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer func(tx *sql.Tx) {
		err := tx.Rollback()
		if err != nil {

		}
	}(tx)
	if _, err := tx.ExecContext(ctx, `INSERT INTO groups (id, name, admin_id) VALUES (?, ?, ?)`, id, name, adminID); err != nil {
		return "", err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO group_members (group_id, user_id) VALUES (?, ?)`, id, adminID); err != nil {
		return "", err
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return id, nil
}

func (s *Store) ListGroups(ctx context.Context, userID string) ([]Group, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT g.id, g.name, g.admin_id FROM groups g
JOIN group_members m ON m.group_id = g.id WHERE m.user_id = ? ORDER BY g.name, g.id`, userID)
	if err != nil {
		return nil, err
	}
	defer func(rows *sql.Rows) {
		err := rows.Close()
		if err != nil {

		}
	}(rows)
	var groups []Group
	for rows.Next() {
		var group Group
		if err := rows.Scan(&group.ID, &group.Name, &group.AdminID); err != nil {
			return nil, err
		}
		groups = append(groups, group)
	}
	return groups, rows.Err()
}

func (s *Store) ListGroupMembers(ctx context.Context, userID, groupID string) ([]GroupMember, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT u.id, u.username FROM users u
JOIN group_members m ON m.user_id = u.id
JOIN group_members requester ON requester.group_id = m.group_id AND requester.user_id = ?
WHERE m.group_id = ? ORDER BY u.username`, userID, groupID)
	if err != nil {
		return nil, err
	}
	defer func(rows *sql.Rows) {
		err := rows.Close()
		if err != nil {

		}
	}(rows)
	var members []GroupMember
	for rows.Next() {
		var member GroupMember
		if err := rows.Scan(&member.UserID, &member.Username); err != nil {
			return nil, err
		}
		members = append(members, member)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if len(members) == 0 {
		return nil, ErrForbidden
	}
	return members, nil
}

func (s *Store) AddGroupMember(ctx context.Context, adminID, groupID, username string) error {
	result, err := s.db.ExecContext(ctx, `INSERT INTO group_members (group_id, user_id)
SELECT g.id, u.id FROM groups g JOIN users u ON u.username = ?
WHERE g.id = ? AND g.admin_id = ?`, username, groupID, adminID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return ErrForbidden
	}
	return nil
}

func (s *Store) RemoveGroupMember(ctx context.Context, adminID, groupID, username string) error {
	result, err := s.db.ExecContext(ctx, `DELETE FROM group_members
WHERE group_id = ? AND user_id = (SELECT id FROM users WHERE username = ?)
AND EXISTS (SELECT 1 FROM groups WHERE id = ? AND admin_id = ? AND admin_id != group_members.user_id)`,
		groupID, username, groupID, adminID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if count != 1 {
		return ErrForbidden
	}
	return nil
}

func (s *Store) Cleanup(ctx context.Context) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func(tx *sql.Tx) {
		err := tx.Rollback()
		if err != nil {

		}
	}(tx)
	now := s.now().Unix()
	if _, err = tx.ExecContext(ctx, `DELETE FROM access_tokens WHERE expires_at <= ?`, now); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `DELETE FROM sessions WHERE refresh_expires_at <= ?`, now); err != nil {
		return err
	}
	return tx.Commit()
}
