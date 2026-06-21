package config

import (
	"fmt"
	"os"
)

type Config struct {
	Port              string
	DBHost            string
	DBPort            string
	DBName            string
	DBUser            string
	DBPassword        string
	LibraryServiceURL string
	InternalAPIKey    string
}

// DSN returns the PostgreSQL connection string for pgx.
func (c *Config) DSN() string {
	return fmt.Sprintf(
		"host=%s port=%s dbname=%s user=%s password=%s sslmode=disable",
		c.DBHost, c.DBPort, c.DBName, c.DBUser, c.DBPassword,
	)
}

// MigrationDSN returns the connection URL used by golang-migrate.
func (c *Config) MigrationDSN() string {
	return fmt.Sprintf(
		"postgres://%s:%s@%s:%s/%s?sslmode=disable",
		c.DBUser, c.DBPassword, c.DBHost, c.DBPort, c.DBName,
	)
}

// Load reads all required configuration from environment variables.
// Returns an error if any required variable is missing.
func Load() (*Config, error) {
	dbPassword, err := require("LOANS_DB_PASSWORD")
	if err != nil {
		return nil, err
	}
	apiKey, err := require("INTERNAL_API_KEY")
	if err != nil {
		return nil, err
	}

	return &Config{
		Port:              optional("PORT", "8081"),
		DBHost:            optional("LOANS_DB_HOST", "localhost"),
		DBPort:            optional("LOANS_DB_PORT", "5432"),
		DBName:            optional("LOANS_DB_NAME", "loans_db"),
		DBUser:            optional("LOANS_DB_USER", "loans_user"),
		DBPassword:        dbPassword,
		LibraryServiceURL: optional("LIBRARY_SERVICE_URL", "http://localhost:8080"),
		InternalAPIKey:    apiKey,
	}, nil
}

func require(key string) (string, error) {
	v := os.Getenv(key)
	if v == "" {
		return "", fmt.Errorf("required environment variable not set: %s", key)
	}
	return v, nil
}

func optional(key, defaultValue string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultValue
}
