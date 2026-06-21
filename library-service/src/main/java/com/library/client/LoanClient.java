package com.library.client;

import com.library.dto.loan.LoanResponse;
import com.library.dto.loan.LoanServiceRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter pattern — encapsulates all HTTP communication with loans-service.
 * The interface is defined here (consumer side), not alongside the implementation,
 * following idiomatic dependency inversion: the caller defines the contract.
 */
public interface LoanClient {

    /** Registers a new loan in loans-service. Throws ServiceUnavailableException on any network/HTTP error. */
    LoanResponse createLoan(LoanServiceRequest request);

    /** Verifies whether a loan was registered for the given requestId. Used to resolve timeout ambiguity. */
    Optional<LoanResponse> findByRequestId(UUID requestId);

    /** Triggers the return flow in loans-service. */
    void returnLoan(UUID loanId);

    List<LoanResponse> getActiveLoans(UUID userId);

    List<LoanResponse> getLoanHistory(UUID userId);
}
