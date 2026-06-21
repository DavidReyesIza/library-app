package com.library.dto.loan;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Loan record as returned by loans-service. */
public record LoanResponse(

        UUID id,

        @JsonProperty("requestId")
        UUID requestId,

        UUID userId,
        UUID bookId,
        String status,
        Instant loanedAt,
        Instant returnedAt
) {}
