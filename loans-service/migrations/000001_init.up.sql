-- =============================================================================
-- 000001_init.up.sql — Schema inicial de loans-service
-- =============================================================================

CREATE TYPE loan_status AS ENUM ('ACTIVE', 'RETURNED');

CREATE TABLE loans (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- request_id es el ID idempotente que genera library-service antes de llamar
    -- a este servicio. UNIQUE garantiza que un mismo intento de préstamo (saga)
    -- no cree dos registros, aunque library-service reintente la llamada.
    request_id   UUID        NOT NULL UNIQUE,
    user_id      UUID        NOT NULL,
    book_id      UUID        NOT NULL,
    status       loan_status NOT NULL DEFAULT 'ACTIVE',
    loaned_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    returned_at  TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_loans_user_id    ON loans (user_id);
CREATE INDEX idx_loans_request_id ON loans (request_id);
CREATE INDEX idx_loans_status     ON loans (status);
CREATE INDEX idx_loans_book_id    ON loans (book_id);
