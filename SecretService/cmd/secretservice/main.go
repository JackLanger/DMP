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

	"github.com/JackLanger/DMP/SecretService/internal/api"
	"github.com/JackLanger/DMP/SecretService/internal/store"
	"github.com/gin-gonic/gin"
	"github.com/joho/godotenv"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	if err := loadEnvironment(); err != nil {
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
		Handler:           withCORS(handler, origins),
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

func withCORS(handler http.Handler, origins map[string]bool) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
		if r.TLS != nil {
			w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		}
		origin := r.Header.Get("Origin")
		if origin != "" {
			w.Header().Add("Vary", "Origin")
			if !origins[origin] {
				http.Error(w, "origin not allowed", http.StatusForbidden)
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Credentials", "true")
			w.Header().Set("Access-Control-Allow-Methods", "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-User, Content-Range, Range, If-Range")
			w.Header().Set("Access-Control-Expose-Headers", "Content-Disposition, Content-Range, Accept-Ranges, ETag, Upload-Offset, Upload-Length, Upload-Expires, Location")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		handler.ServeHTTP(w, r)
	})
}

func loadEnvironment() error {
	path, err := environmentFilePath(".")
	if err != nil {
		return err
	}
	return loadEnvironmentFile(path)
}

func environmentFilePath(workingDirectory string) (string, error) {
	path := filepath.Join(workingDirectory, ".env")
	serviceDirectory := filepath.Join(workingDirectory, "SecretService")
	if info, err := os.Stat(serviceDirectory); err == nil && info.IsDir() {
		path = filepath.Join(serviceDirectory, ".env")
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", errors.New("cannot locate SecretService configuration directory")
	}
	return path, nil
}

func loadEnvironmentFile(path string) error {
	values, err := godotenv.Read(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		var pathError *os.PathError
		if errors.As(err, &pathError) {
			return errors.New("cannot read SecretService .env file")
		}
		return errors.New("invalid SecretService .env file")
	}
	for name, value := range values {
		if _, exists := os.LookupEnv(name); !exists {
			if err := os.Setenv(name, value); err != nil {
				return errors.New("cannot load SecretService .env file")
			}
		}
	}
	return nil
}
