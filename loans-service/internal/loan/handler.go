package loan

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/go-playground/validator/v10"
)

var validate = validator.New()

// ─── Response DTOs ────────────────────────────────────────────────────────────

// LoanResponse is the JSON shape returned to callers for a single loan.
type LoanResponse struct {
	ID         string     `json:"id"`
	RequestID  string     `json:"requestId"`
	UserID     string     `json:"userId"`
	BookID     string     `json:"bookId"`
	Status     string     `json:"status"`
	LoanedAt   time.Time  `json:"loanedAt"`
	ReturnedAt *time.Time `json:"returnedAt,omitempty"`
}

// apiError mirrors the error shape of library-service's GlobalExceptionHandler
// so both services return the same JSON structure to their callers.
type apiError struct {
	Timestamp string `json:"timestamp"`
	Status    int    `json:"status"`
	Error     string `json:"error"`
	Message   string `json:"message"`
}

// ─── Handler ─────────────────────────────────────────────────────────────────

type Handler struct {
	svc *Service
}

func NewHandler(svc *Service) *Handler {
	return &Handler{svc: svc}
}

// Routes returns a chi router with all loan endpoints.
// Callers are responsible for mounting the API-key middleware before these routes.
func (h *Handler) Routes() http.Handler {
	r := chi.NewRouter()
	r.Post("/", h.createLoan)
	r.Post("/{id}/return", h.returnLoan)
	r.Get("/active", h.activeLoansByUser)
	r.Get("/history", h.historyByUser)
	r.Get("/by-request-id/{requestId}", h.byRequestID)
	return r
}

// POST /loans
// Idempotent: duplicate request_id returns 200 with the existing record.
func (h *Handler) createLoan(w http.ResponseWriter, r *http.Request) {
	var req CreateLoanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	if err := validate.Struct(req); err != nil {
		writeError(w, http.StatusBadRequest, formatValidationError(err))
		return
	}

	loan, err := h.svc.CreateLoan(r.Context(), req)
	if err != nil {
		handleServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusCreated, toResponse(loan))
}

// POST /loans/{id}/return
func (h *Handler) returnLoan(w http.ResponseWriter, r *http.Request) {
	id := chi.URLParam(r, "id")

	loan, err := h.svc.ReturnLoan(r.Context(), id)
	if err != nil {
		handleServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, toResponse(loan))
}

// GET /loans/active?userId={uuid}
func (h *Handler) activeLoansByUser(w http.ResponseWriter, r *http.Request) {
	userID := r.URL.Query().Get("userId")
	if userID == "" {
		writeError(w, http.StatusBadRequest, "query parameter 'userId' is required")
		return
	}

	loans, err := h.svc.GetActiveLoansByUser(r.Context(), userID)
	if err != nil {
		handleServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, toResponseList(loans))
}

// GET /loans/history?userId={uuid}
func (h *Handler) historyByUser(w http.ResponseWriter, r *http.Request) {
	userID := r.URL.Query().Get("userId")
	if userID == "" {
		writeError(w, http.StatusBadRequest, "query parameter 'userId' is required")
		return
	}

	loans, err := h.svc.GetLoanHistoryByUser(r.Context(), userID)
	if err != nil {
		handleServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, toResponseList(loans))
}

// GET /loans/by-request-id/{requestId}
// Verification endpoint: library-service calls this to resolve timeout ambiguity.
// If 404 → loan was never created, safe to compensate.
// If 200 → loan exists, do NOT compensate.
func (h *Handler) byRequestID(w http.ResponseWriter, r *http.Request) {
	requestID := chi.URLParam(r, "requestId")

	loan, err := h.svc.GetByRequestID(r.Context(), requestID)
	if err != nil {
		handleServiceError(w, err)
		return
	}

	writeJSON(w, http.StatusOK, toResponse(loan))
}

// ─── Error handling ───────────────────────────────────────────────────────────

// handleServiceError maps domain errors to HTTP status codes.
// All errors are returned as values — no panics reach here.
func handleServiceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, ErrAlreadyReturned):
		writeError(w, http.StatusConflict, err.Error())
	case errors.Is(err, ErrBookReleaseFailed):
		// Loan is RETURNED locally; release on library-service failed.
		// Return 502 so library-service can schedule a retry of the release.
		slog.Error("book release failed after loan return", "error", err)
		writeError(w, http.StatusBadGateway, "loan returned locally but book release failed; retry required")
	default:
		slog.Error("unexpected service error", "error", err)
		writeError(w, http.StatusInternalServerError, "internal server error")
	}
}

func writeError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(apiError{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Status:    status,
		Error:     http.StatusText(status),
		Message:   message,
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(body)
}

// formatValidationError extracts the first validation failure into a readable string.
func formatValidationError(err error) string {
	var ve validator.ValidationErrors
	if errors.As(err, &ve) && len(ve) > 0 {
		f := ve[0]
		return f.Field() + ": failed validation '" + f.Tag() + "'"
	}
	return err.Error()
}

// ─── Mapping helpers ──────────────────────────────────────────────────────────

func toResponse(l *Loan) LoanResponse {
	return LoanResponse{
		ID:         l.ID,
		RequestID:  l.RequestID,
		UserID:     l.UserID,
		BookID:     l.BookID,
		Status:     string(l.Status),
		LoanedAt:   l.LoanedAt,
		ReturnedAt: l.ReturnedAt,
	}
}

func toResponseList(loans []Loan) []LoanResponse {
	result := make([]LoanResponse, len(loans))
	for i, l := range loans {
		result[i] = toResponse(&l)
	}
	return result
}
