# ── Stage 1: build ────────────────────────────────────────────────────────────
# Requires go.sum to be committed. Run `go mod tidy` locally first to generate it.
FROM golang:1.22-alpine AS build

RUN apk add --no-cache git

WORKDIR /app

# Cache dependency downloads separately from source changes.
# go.sum must exist in the repo before building this image.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /loans-service ./cmd/api

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM alpine:3.20

RUN apk add --no-cache wget ca-certificates \
 && addgroup -S appgroup \
 && adduser  -S appuser -G appgroup

WORKDIR /app

# Binary
COPY --from=build /loans-service .

# Migration files must be present at runtime — golang-migrate reads them via file://migrations
COPY --from=build /app/migrations ./migrations

USER appuser

EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=5 \
  CMD wget -qO- http://localhost:8081/health || exit 1

ENTRYPOINT ["/app/loans-service"]
