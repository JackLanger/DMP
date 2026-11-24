package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	core "go-core"

	"github.com/JackLanger/DMP/SecretService/internal/api"
	"github.com/JackLanger/DMP/SecretService/internal/store"
	"github.com/gin-gonic/gin"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	if err := core.LoadEnvironment(); err != nil {
		return err
	}
	admin := os.Getenv("SECRET_ADMIN_SECRET")
	service := os.Getenv("SECRET_SERVICE_SECRET")
	cert := os.Getenv("SECRET_TLS_CERT")
	key := os.Getenv("SECRET_TLS_KEY")
	if len(admin) < 32 || len(service) < 32 || admin == service || cert == "" || key == "" {
		return errors.New("distinct admin and service secrets of at least 32 characters and TLS certificate/key are required")
	}

	path := os.Getenv("SECRET_DATABASE_PATH")
	if path == "" {
		path = "data/secret.db"
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	db, err := store.Open(ctx, path)
	if err != nil {
		return err
	}
	defer func(db *store.Store) {
		err := db.Close()
		if err != nil {
			log.Printf("failed to close database: %v", err)
		}
	}(db)
	gin.SetMode(gin.ReleaseMode)
	address := os.Getenv("SECRET_LISTEN_ADDRESS")
	if address == "" {
		address = ":8443"
	}
	handler, err := api.NewGateway(db, admin, service, os.Getenv("FILESERVER_UPSTREAM_URL"))
	if err != nil {
		return err
	}
	origins := map[string]bool{}
	for origin := range strings.SplitSeq(os.Getenv("SECRET_ALLOWED_ORIGINS"), ",") {
		if origin = strings.TrimSpace(origin); origin != "" {
			origins[origin] = true
		}
	}
	server := &http.Server{
		Addr:              address,
		Handler:           core.WithCORS(handler, origins),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	errs := make(chan error, 1)
	go func() { errs <- server.ListenAndServeTLS(cert, key) }()
	go func() {
		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := db.Cleanup(ctx); err != nil {
					log.Printf("token cleanup failed: %v", err)
				}
			}
		}
	}()
	select {
	case err := <-errs:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdown); err != nil {
			return err
		}
	}
	return nil
}
