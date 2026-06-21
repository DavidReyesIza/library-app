package loan

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

// PostgresLoanRepository is the concrete pgx-backed implementation.
// No interface is declared here — the interface (LoanRepository) lives in
// service.go, on the consumer side, following Go's "accept interfaces,
// return structs" and consumer-defined-contract conventions.
type PostgresLoanRepository struct {
	pool *pgxpool.Pool
}

func NewPostgresLoanRepository(pool *pgxpool.Pool) *PostgresLoanRepository {
	return &PostgresLoanRepository{pool: pool}
}

// Create inserts a new loan record.
// Returns ErrDuplicateRequestID when the UNIQUE constraint on request_id fires.
func (r *PostgresLoanRepository) Create(ctx context.Context, l *Loan) error {
	const q = `
		INSERT INTO loans (request_id, user_id, book_id, status, loaned_at)
		VALUES ($1::uuid, $2::uuid, $3::uuid, $4, now())
		RETURNING id::text, loaned_at`

	row := r.pool.QueryRow(ctx, q, l.RequestID, l.UserID, l.BookID, l.Status)
	if err := row.Scan(&l.ID, &l.LoanedAt); err != nil {
		if isUniqueViolation(err) {
			return ErrDuplicateRequestID
		}
		return fmt.Errorf("inserting loan: %w", err)
	}
	return nil
}

// FindByID returns a single loan by its primary key.
// Returns ErrNotFound when no row matches.
func (r *PostgresLoanRepository) FindByID(ctx context.Context, id string) (*Loan, error) {
	const q = `
		SELECT id::text, request_id::text, user_id::text, book_id::text,
		       status, loaned_at, returned_at
		FROM loans
		WHERE id = $1::uuid`

	return r.scanOne(r.pool.QueryRow(ctx, q, id))
}

// FindByRequestID returns a loan by its idempotency key.
// Returns ErrNotFound when no row matches.
func (r *PostgresLoanRepository) FindByRequestID(ctx context.Context, requestID string) (*Loan, error) {
	const q = `
		SELECT id::text, request_id::text, user_id::text, book_id::text,
		       status, loaned_at, returned_at
		FROM loans
		WHERE request_id = $1::uuid`

	return r.scanOne(r.pool.QueryRow(ctx, q, requestID))
}

// FindActiveByUserID returns all ACTIVE loans for a given user.
func (r *PostgresLoanRepository) FindActiveByUserID(ctx context.Context, userID string) ([]Loan, error) {
	const q = `
		SELECT id::text, request_id::text, user_id::text, book_id::text,
		       status, loaned_at, returned_at
		FROM loans
		WHERE user_id = $1::uuid AND status = 'ACTIVE'
		ORDER BY loaned_at DESC`

	return r.scanMany(ctx, q, userID)
}

// FindHistoryByUserID returns all loans (active + returned) for a given user.
func (r *PostgresLoanRepository) FindHistoryByUserID(ctx context.Context, userID string) ([]Loan, error) {
	const q = `
		SELECT id::text, request_id::text, user_id::text, book_id::text,
		       status, loaned_at, returned_at
		FROM loans
		WHERE user_id = $1::uuid
		ORDER BY loaned_at DESC`

	return r.scanMany(ctx, q, userID)
}

// MarkReturned updates the loan status to RETURNED atomically.
// Returns ErrNotFound when no row matches.
func (r *PostgresLoanRepository) MarkReturned(ctx context.Context, id string, returnedAt time.Time) error {
	const q = `
		UPDATE loans
		SET status = 'RETURNED', returned_at = $2
		WHERE id = $1::uuid AND status = 'ACTIVE'`

	tag, err := r.pool.Exec(ctx, q, id, returnedAt)
	if err != nil {
		return fmt.Errorf("marking loan returned: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// ─── helpers ─────────────────────────────────────────────────────────────────

func (r *PostgresLoanRepository) scanOne(row pgx.Row) (*Loan, error) {
	l := &Loan{}
	err := row.Scan(
		&l.ID, &l.RequestID, &l.UserID, &l.BookID,
		&l.Status, &l.LoanedAt, &l.ReturnedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("scanning loan row: %w", err)
	}
	return l, nil
}

func (r *PostgresLoanRepository) scanMany(ctx context.Context, query string, args ...any) ([]Loan, error) {
	rows, err := r.pool.Query(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("querying loans: %w", err)
	}
	defer rows.Close()

	var result []Loan
	for rows.Next() {
		l := Loan{}
		if err := rows.Scan(
			&l.ID, &l.RequestID, &l.UserID, &l.BookID,
			&l.Status, &l.LoanedAt, &l.ReturnedAt,
		); err != nil {
			return nil, fmt.Errorf("scanning loan row: %w", err)
		}
		result = append(result, l)
	}
	return result, rows.Err()
}

// isUniqueViolation detects PostgreSQL unique-constraint violations (code 23505).
func isUniqueViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23505"
}
