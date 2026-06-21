package loan

import (
	"context"
	"errors"
	"fmt"
	"time"
)

// ─── Consumer-defined interfaces ─────────────────────────────────────────────
// Interfaces are declared here, on the consumer side (service), NOT in the
// packages that implement them. This is the idiomatic Go rule:
// "accept interfaces, return structs" — the producer is unaware of the contract.

// LoanRepository is the storage contract required by the service.
type LoanRepository interface {
	Create(ctx context.Context, l *Loan) error
	FindByID(ctx context.Context, id string) (*Loan, error)
	FindByRequestID(ctx context.Context, requestID string) (*Loan, error)
	FindActiveByUserID(ctx context.Context, userID string) ([]Loan, error)
	FindHistoryByUserID(ctx context.Context, userID string) ([]Loan, error)
	MarkReturned(ctx context.Context, id string, returnedAt time.Time) error
}

// LibraryClient is the external dependency contract for library-service.
// loans-service only needs to call /release — library-service already executed
// the atomic reservation before calling loans-service, so no /reserve is needed.
type LibraryClient interface {
	ReleaseBook(ctx context.Context, bookID string) error
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

// CreateLoanRequest is the input for registering a new loan.
type CreateLoanRequest struct {
	RequestID string `json:"request_id" validate:"required,uuid4"`
	UserID    string `json:"user_id"    validate:"required,uuid4"`
	BookID    string `json:"book_id"    validate:"required,uuid4"`
}

// ─── Service ─────────────────────────────────────────────────────────────────

type Service struct {
	repo   LoanRepository
	client LibraryClient
}

func NewService(repo LoanRepository, client LibraryClient) *Service {
	return &Service{repo: repo, client: client}
}

// CreateLoan persists a new loan record.
// B does NOT validate availability with A here — library-service already
// executed the atomic decrement on available_copies before calling this endpoint.
// Idempotent: a duplicate request_id returns ErrDuplicateRequestID (not 500).
func (s *Service) CreateLoan(ctx context.Context, req CreateLoanRequest) (*Loan, error) {
	l := &Loan{
		RequestID: req.RequestID,
		UserID:    req.UserID,
		BookID:    req.BookID,
		Status:    StatusActive,
	}

	if err := s.repo.Create(ctx, l); err != nil {
		if errors.Is(err, ErrDuplicateRequestID) {
			// Idempotent: return the existing record instead of an error.
			existing, lookupErr := s.repo.FindByRequestID(ctx, req.RequestID)
			if lookupErr != nil {
				return nil, fmt.Errorf("handling duplicate request_id: %w", lookupErr)
			}
			return existing, nil
		}
		return nil, fmt.Errorf("creating loan: %w", err)
	}
	return l, nil
}

// ReturnLoan marks the loan as RETURNED locally first, then calls library-service
// to release the book copy.
//
// If the DB update succeeds but the library-service call fails:
//   - Local state remains RETURNED (not rolled back).
//   - ErrBookReleaseFailed (wrapping the original error) is returned as a value.
//   - No panic, no log.Fatal — the caller decides how to handle the retry.
func (s *Service) ReturnLoan(ctx context.Context, id string) (*Loan, error) {
	loan, err := s.repo.FindByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if loan.Status == StatusReturned {
		return nil, ErrAlreadyReturned
	}

	returnedAt := time.Now()
	if err := s.repo.MarkReturned(ctx, id, returnedAt); err != nil {
		return nil, fmt.Errorf("marking loan as returned: %w", err)
	}

	loan.Status = StatusReturned
	loan.ReturnedAt = &returnedAt

	if err := s.client.ReleaseBook(ctx, loan.BookID); err != nil {
		return nil, fmt.Errorf("%w: %w", ErrBookReleaseFailed, err)
	}

	return loan, nil
}

// GetByRequestID returns a loan by its idempotency key.
// This is the verification endpoint used by library-service to resolve
// timeout ambiguity: "did the loan actually get created before the timeout?"
func (s *Service) GetByRequestID(ctx context.Context, requestID string) (*Loan, error) {
	return s.repo.FindByRequestID(ctx, requestID)
}

// GetActiveLoansByUser returns all active loans for a given user.
func (s *Service) GetActiveLoansByUser(ctx context.Context, userID string) ([]Loan, error) {
	return s.repo.FindActiveByUserID(ctx, userID)
}

// GetLoanHistoryByUser returns the full loan history for a given user.
func (s *Service) GetLoanHistoryByUser(ctx context.Context, userID string) ([]Loan, error) {
	return s.repo.FindHistoryByUserID(ctx, userID)
}
