package api

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/JackLanger/DMP/SecretService/internal/store"
)

func TestGatewayPropagatesCancellation(t *testing.T) {
	s, err := store.Open(context.Background(), ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := s.CreateUser(context.Background(), "alice", "long password 123"); err != nil {
		t.Fatal(err)
	}
	tokens, err := s.Login(context.Background(), "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	started, cancelled := make(chan struct{}), make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		close(started)
		<-r.Context().Done()
		close(cancelled)
	}))
	defer upstream.Close()
	handler, err := NewGateway(s, "admin", "service", upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	gateway := httptest.NewServer(handler)
	defer gateway.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r, err := http.NewRequestWithContext(ctx, http.MethodGet, gateway.URL+"/files/all", nil)
	if err != nil {
		t.Fatal(err)
	}
	r.Header.Set("X-User", "alice")
	r.Header.Set("Authorization", "Bearer "+tokens.AccessToken)
	result := make(chan error, 1)
	go func() {
		response, err := http.DefaultClient.Do(r)
		if response != nil {
			response.Body.Close()
		}
		result <- err
	}()
	select {
	case <-started:
	case <-time.After(5 * time.Second):
		cancel()
		t.Fatal("upstream request did not start")
	}
	cancel()
	select {
	case <-cancelled:
	case <-time.After(5 * time.Second):
		t.Fatal("upstream request was not cancelled")
	}
	if err := <-result; err == nil {
		t.Fatal("cancelled request succeeded")
	}
}

func TestGatewayStreamsRequestBeforeUploadCompletes(t *testing.T) {
	s, err := store.Open(context.Background(), ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if _, err := s.CreateUser(context.Background(), "alice", "long password 123"); err != nil {
		t.Fatal(err)
	}
	tokens, err := s.Login(context.Background(), "alice", "long password 123")
	if err != nil {
		t.Fatal(err)
	}
	received := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		buf := make([]byte, 4)
		if _, err := io.ReadFull(r.Body, buf); err != nil || string(buf) != "part" {
			t.Errorf("first upload chunk: %q, %v", buf, err)
		}
		close(received)
		_, _ = io.Copy(io.Discard, r.Body)
		w.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()
	handler, err := NewGateway(s, "admin", "service", upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	gateway := httptest.NewServer(handler)
	defer gateway.Close()
	reader, writer := io.Pipe()
	defer reader.Close()
	defer writer.Close()
	r, err := http.NewRequest(http.MethodPost, gateway.URL+"/files", reader)
	if err != nil {
		t.Fatal(err)
	}
	r.Header.Set("X-User", "alice")
	r.Header.Set("Authorization", "Bearer "+tokens.AccessToken)
	result := make(chan error, 1)
	go func() {
		response, err := http.DefaultClient.Do(r)
		if err == nil {
			response.Body.Close()
			if response.StatusCode != http.StatusNoContent {
				err = fmt.Errorf("unexpected status: %d", response.StatusCode)
			}
		}
		result <- err
	}()
	if _, err := writer.Write([]byte("part")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-received:
	case <-time.After(5 * time.Second):
		t.Fatal("proxy buffered the incomplete upload")
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}
