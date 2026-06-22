package loan_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"loans-service/internal/loan"
)

// ─── Manual mocks ─────────────────────────────────────────────────────────────
// Interfaces are defined in service.go (consumer side), so we implement them
// here directly — no code-generation tools needed for these 4 focused tests.

type mockRepo struct {
	createFn             func(ctx context.Context, l *loan.Loan) error
	findByIDFn           func(ctx context.Context, id string) (*loan.Loan, error)
	findByRequestIDFn    func(ctx context.Context, requestID string) (*loan.Loan, error)
	findActiveByUserIDFn func(ctx context.Context, userID string) ([]loan.Loan, error)
	findHistoryByUserIDFn func(ctx context.Context, userID string) ([]loan.Loan, error)
	markReturnedFn       func(ctx context.Context, id string, returnedAt time.Time) error
	markReturnedCalled   bool // tracked explicitly for the partial-failure test
}

func (m *mockRepo) Create(ctx context.Context, l *loan.Loan) error {
	return m.createFn(ctx, l)
}
func (m *mockRepo) FindByID(ctx context.Context, id string) (*loan.Loan, error) {
	return m.findByIDFn(ctx, id)
}
func (m *mockRepo) FindByRequestID(ctx context.Context, requestID string) (*loan.Loan, error) {
	return m.findByRequestIDFn(ctx, requestID)
}
func (m *mockRepo) FindActiveByUserID(ctx context.Context, userID string) ([]loan.Loan, error) {
	return m.findActiveByUserIDFn(ctx, userID)
}
func (m *mockRepo) FindHistoryByUserID(ctx context.Context, userID string) ([]loan.Loan, error) {
	return m.findHistoryByUserIDFn(ctx, userID)
}
func (m *mockRepo) MarkReturned(ctx context.Context, id string, returnedAt time.Time) error {
	m.markReturnedCalled = true
	return m.markReturnedFn(ctx, id, returnedAt)
}

type mockLibraryClient struct {
	releaseBookFn func(ctx context.Context, bookID string) error
}

func (m *mockLibraryClient) ReleaseBook(ctx context.Context, bookID string) error {
	return m.releaseBookFn(ctx, bookID)
}

// ─── Tests ────────────────────────────────────────────────────────────────────

// Test 1: Registro exitoso de préstamo.
// library-service ya ejecutó la reserva atómica; loans-service solo persiste
// el registro y retorna confirmación — sin validar disponibilidad con A.
func TestCreateLoan_Success(t *testing.T) {
	const (
		requestID = "11111111-1111-1111-1111-111111111111"
		userID    = "22222222-2222-2222-2222-222222222222"
		bookID    = "33333333-3333-3333-3333-333333333333"
	)

	repo := &mockRepo{
		createFn: func(_ context.Context, l *loan.Loan) error {
			// Simulate DB assigning the primary key on INSERT RETURNING
			l.ID = "44444444-4444-4444-4444-444444444444"
			l.LoanedAt = time.Now()
			return nil
		},
	}
	// LibraryClient must NOT be called during loan creation —
	// loans-service does not validate or reserve via library-service.
	client := &mockLibraryClient{
		releaseBookFn: func(_ context.Context, _ string) error {
			t.Fatal("ReleaseBook must not be called during CreateLoan")
			return nil
		},
	}

	svc := loan.NewService(repo, client)
	result, err := svc.CreateLoan(context.Background(), loan.CreateLoanRequest{
		RequestID: requestID,
		UserID:    userID,
		BookID:    bookID,
	})

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, "44444444-4444-4444-4444-444444444444", result.ID)
	assert.Equal(t, requestID, result.RequestID)
	assert.Equal(t, loan.StatusActive, result.Status)
	assert.Nil(t, result.ReturnedAt)
}

// Test 2: Devolución exitosa.
// LibraryClient.ReleaseBook retorna nil → préstamo queda en RETURNED,
// returned_at se setea, no hay error.
func TestReturnLoan_Success(t *testing.T) {
	const loanID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
	const bookID = "33333333-3333-3333-3333-333333333333"

	activeLoan := &loan.Loan{
		ID:     loanID,
		BookID: bookID,
		Status: loan.StatusActive,
	}

	repo := &mockRepo{
		findByIDFn: func(_ context.Context, id string) (*loan.Loan, error) {
			assert.Equal(t, loanID, id)
			return activeLoan, nil
		},
		markReturnedFn: func(_ context.Context, id string, _ time.Time) error {
			assert.Equal(t, loanID, id)
			return nil
		},
	}
	client := &mockLibraryClient{
		releaseBookFn: func(_ context.Context, id string) error {
			assert.Equal(t, bookID, id)
			return nil
		},
	}

	svc := loan.NewService(repo, client)
	result, err := svc.ReturnLoan(context.Background(), loanID)

	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Equal(t, loan.StatusReturned, result.Status)
	assert.NotNil(t, result.ReturnedAt, "returned_at must be set after a successful return")
	assert.True(t, repo.markReturnedCalled)
}

// Test 3: Devolución con falla de LibraryClient (⚠️ caso crítico).
// ReleaseBook retorna error → el préstamo queda RETURNED localmente
// (MarkReturned SÍ fue llamado, el estado no se revierte), el error se
// propaga como valor — sin panic, sin log.Fatal.
func TestReturnLoan_LibraryClientFails(t *testing.T) {
	const loanID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
	const bookID = "cccccccc-cccc-cccc-cccc-cccccccccccc"

	simulatedTimeout := errors.New("context deadline exceeded")

	activeLoan := &loan.Loan{
		ID:     loanID,
		BookID: bookID,
		Status: loan.StatusActive,
	}

	repo := &mockRepo{
		findByIDFn: func(_ context.Context, _ string) (*loan.Loan, error) {
			return activeLoan, nil
		},
		markReturnedFn: func(_ context.Context, _ string, _ time.Time) error {
			return nil // DB update succeeds
		},
	}
	client := &mockLibraryClient{
		releaseBookFn: func(_ context.Context, _ string) error {
			return simulatedTimeout // library-service times out
		},
	}

	svc := loan.NewService(repo, client)

	// Must not panic — capture the return values normally.
	result, err := svc.ReturnLoan(context.Background(), loanID)

	// Error must be propagated as a value.
	require.Error(t, err)
	assert.True(t, errors.Is(err, loan.ErrBookReleaseFailed),
		"error must wrap ErrBookReleaseFailed so the caller can identify partial failure")

	// Underlying cause must also be reachable via errors.Is / errors.As.
	assert.True(t, errors.Is(err, simulatedTimeout),
		"original timeout error must be reachable through the error chain")

	// No result returned on partial failure.
	assert.Nil(t, result)

	// Local state WAS updated — MarkReturned was called and not rolled back.
	assert.True(t, repo.markReturnedCalled,
		"MarkReturned must have been called even though ReleaseBook failed")
}

// Test 4: Idempotencia — el mismo request_id dos veces no duplica el préstamo.
// La UNIQUE constraint en la BD devuelve ErrDuplicateRequestID (controlado),
// el servicio lo intercepta y retorna el registro existente con 200, no 500.
func TestCreateLoan_Idempotent(t *testing.T) {
	const requestID = "dddddddd-dddd-dddd-dddd-dddddddddddd"

	existingLoan := &loan.Loan{
		ID:        "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
		RequestID: requestID,
		Status:    loan.StatusActive,
		LoanedAt:  time.Now().Add(-5 * time.Minute),
	}

	repo := &mockRepo{
		createFn: func(_ context.Context, _ *loan.Loan) error {
			// Second attempt hits the UNIQUE constraint.
			return loan.ErrDuplicateRequestID
		},
		findByRequestIDFn: func(_ context.Context, id string) (*loan.Loan, error) {
			assert.Equal(t, requestID, id)
			return existingLoan, nil
		},
	}
	client := &mockLibraryClient{
		releaseBookFn: func(_ context.Context, _ string) error {
			t.Fatal("ReleaseBook must not be called during CreateLoan")
			return nil
		},
	}

	svc := loan.NewService(repo, client)
	result, err := svc.CreateLoan(context.Background(), loan.CreateLoanRequest{
		RequestID: requestID,
		UserID:    "ffffffff-ffff-ffff-ffff-ffffffffffff",
		BookID:    "00000000-0000-0000-0000-000000000001",
	})

	// Must return the existing loan — no error, no duplicate.
	require.NoError(t, err, "duplicate request_id must not surface as an error to the caller")
	require.NotNil(t, result)
	assert.Equal(t, existingLoan.ID, result.ID,
		"must return the original loan record, not a new one")
	assert.Equal(t, requestID, result.RequestID)
}
