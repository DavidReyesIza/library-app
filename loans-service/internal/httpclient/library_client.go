package httpclient

import (
	"context"
	"fmt"
	"net/http"
	"time"
)

const releaseTimeout = 5 * time.Second

// LibraryHTTPClient is the concrete HTTP implementation of the loan.LibraryClient
// interface defined in the service package (consumer-defined contract).
//
// This client only calls library-service for /release — it does NOT call /reserve
// because library-service already executed the atomic reservation before calling
// loans-service. Calling /reserve here would double-decrement available_copies.
type LibraryHTTPClient struct {
	baseURL    string
	apiKey     string
	httpClient *http.Client
}

// NewLibraryClient creates a LibraryHTTPClient ready to call library-service.
// baseURL example: "http://library-service:8080"
func NewLibraryClient(baseURL, apiKey string) *LibraryHTTPClient {
	return &LibraryHTTPClient{
		baseURL: baseURL,
		apiKey:  apiKey,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// ReleaseBook calls POST /internal/books/{bookID}/release on library-service
// to increment the available_copies counter after a loan is returned.
// A context.WithTimeout is applied independently of the parent context to
// avoid inheriting a context that is already near its deadline.
func (c *LibraryHTTPClient) ReleaseBook(ctx context.Context, bookID string) error {
	releaseCtx, cancel := context.WithTimeout(ctx, releaseTimeout)
	defer cancel()

	url := fmt.Sprintf("%s/internal/books/%s/release", c.baseURL, bookID)

	req, err := http.NewRequestWithContext(releaseCtx, http.MethodPost, url, nil)
	if err != nil {
		return fmt.Errorf("building release request: %w", err)
	}
	req.Header.Set("X-Internal-Api-Key", c.apiKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("calling library-service /release: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("library-service /release returned unexpected status %d", resp.StatusCode)
	}

	return nil
}
