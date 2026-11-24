package gocore

import (
	"errors"
	"os"
	"path/filepath"

	"github.com/joho/godotenv"
)

func LoadEnvironment() error {
	path, err := EnvironmentFilePath(".")
	if err != nil {
		return err
	}
	return LoadEnvironmentFile(path)
}

func EnvironmentFilePath(workingDirectory string) (string, error) {
	path := filepath.Join(workingDirectory, ".env")
	serviceDirectory := filepath.Join(workingDirectory, "SecretService")
	if info, err := os.Stat(serviceDirectory); err == nil && info.IsDir() {
		path = filepath.Join(serviceDirectory, ".env")
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return "", errors.New("cannot locate SecretService configuration directory")
	}
	return path, nil
}

func LoadEnvironmentFile(path string) error {
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
