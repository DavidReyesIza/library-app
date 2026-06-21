package com.library.dto.loan;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Payload sent from library-service to loans-service when registering a loan. */
public record LoanServiceRequest(

        @JsonProperty("request_id")
        UUID requestId,

        @JsonProperty("userId")
        UUID userId,

        @JsonProperty("bookId")
        UUID bookId
) {}
