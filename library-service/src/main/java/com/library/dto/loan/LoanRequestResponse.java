package com.library.dto.loan;

import com.library.model.LoanRequest;
import com.library.model.LoanRequestStatus;

import java.time.Instant;
import java.util.UUID;

/** Response returned to the end client after a loan request. */
public record LoanRequestResponse(
        UUID id,
        UUID requestId,
        UUID bookId,
        UUID loanId,
        LoanRequestStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static LoanRequestResponse from(LoanRequest req) {
        return new LoanRequestResponse(
                req.getId(),
                req.getRequestId(),
                req.getBookId(),
                req.getLoanId(),
                req.getStatus(),
                req.getCreatedAt(),
                req.getUpdatedAt()
        );
    }
}
