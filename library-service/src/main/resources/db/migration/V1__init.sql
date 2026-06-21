-- =============================================================================
-- V1__init.sql — Schema inicial de library-service
-- =============================================================================

-- Tipos enumerados
CREATE TYPE user_role AS ENUM ('ADMIN', 'USER');
CREATE TYPE loan_request_status AS ENUM ('PENDING', 'CONFIRMED', 'COMPENSATING', 'COMPENSATED', 'FAILED');

-- -----------------------------------------------------------------------------
-- Tabla: users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name     VARCHAR(150)        NOT NULL,
    email         VARCHAR(255)        NOT NULL UNIQUE,
    password_hash VARCHAR(255)        NOT NULL,
    role          user_role           NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);

-- -----------------------------------------------------------------------------
-- Tabla: books
-- -----------------------------------------------------------------------------
CREATE TABLE books (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title             VARCHAR(300)    NOT NULL,
    author            VARCHAR(200)    NOT NULL,
    isbn              VARCHAR(20)     NOT NULL UNIQUE,
    publication_year  INT             NOT NULL,
    genre             VARCHAR(100)    NOT NULL,
    total_copies      INT             NOT NULL CHECK (total_copies >= 0),
    available_copies  INT             NOT NULL CHECK (available_copies >= 0),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_copies CHECK (available_copies <= total_copies)
);

CREATE INDEX idx_books_author ON books (author);
CREATE INDEX idx_books_genre  ON books (genre);
CREATE INDEX idx_books_isbn   ON books (isbn);

-- -----------------------------------------------------------------------------
-- Tabla: loan_requests
-- Registra el estado de la saga de préstamo orquestada por library-service.
-- Cada fila es un intento de préstamo y su ciclo de vida hasta CONFIRMED o COMPENSATED.
-- -----------------------------------------------------------------------------
CREATE TABLE loan_requests (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id  UUID                     NOT NULL UNIQUE,  -- ID idempotente enviado a loans-service
    user_id     UUID                     NOT NULL REFERENCES users(id),
    book_id     UUID                     NOT NULL REFERENCES books(id),
    status      loan_request_status      NOT NULL DEFAULT 'PENDING',
    attempts    SMALLINT                 NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_loan_requests_user_id    ON loan_requests (user_id);
CREATE INDEX idx_loan_requests_request_id ON loan_requests (request_id);
CREATE INDEX idx_loan_requests_status     ON loan_requests (status);
