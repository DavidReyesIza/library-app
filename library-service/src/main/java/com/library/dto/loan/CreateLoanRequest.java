package com.library.dto.loan;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request body sent by the end client to POST /loans. */
public record CreateLoanRequest(

        @NotNull(message = "bookId is required")
        UUID bookId
) {}
