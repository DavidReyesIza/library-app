package middleware

import (
	"encoding/json"
	"net/http"
)

const apiKeyHeader = "X-Internal-Api-Key"

// InternalAPIKey returns a Chi-compatible middleware that rejects requests
// without the correct shared API key in the X-Internal-Api-Key header.
// This protects all loan endpoints from external callers — only library-service
// (which knows the shared key) can reach them.
func InternalAPIKey(expectedKey string) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get(apiKeyHeader) != expectedKey {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusUnauthorized)
				json.NewEncoder(w).Encode(map[string]string{
					"error": "missing or invalid internal API key",
				})
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
