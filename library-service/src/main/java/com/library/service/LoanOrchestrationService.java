package com.library.service;

import com.library.client.LoanClient;
import com.library.dto.loan.LoanRequestResponse;
import com.library.dto.loan.LoanResponse;
import com.library.dto.loan.LoanServiceRequest;
import com.library.exception.ConflictException;
import com.library.exception.ResourceNotFoundException;
import com.library.exception.ServiceUnavailableException;
import com.library.model.LoanRequest;
import com.library.model.LoanRequestStatus;
import com.library.repository.LoanRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanOrchestrationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {200, 500, 1000};

    private final LoanRequestRepository loanRequestRepository;
    private final BookService bookService;
    private final LoanClient loanClient;

    // ── Create loan (saga) ───────────────────────────────────────────────────

    @Transactional
    public LoanRequestResponse createLoan(UUID userId, UUID bookId) {
        // 1. Validate book exists (throws 404 if not)
        bookService.findById(bookId);

        // 2. Create LoanRequest in PENDING
        UUID requestId = UUID.randomUUID();
        LoanRequest loanReq = LoanRequest.builder()
                .requestId(requestId)
                .userId(userId)
                .bookId(bookId)
                .build();
        loanReq = loanRequestRepository.save(loanReq);
        log.info("LoanRequest created: id={}, requestId={}", loanReq.getId(), requestId);

        // 3. Atomic reserve — abort immediately if no copies available (no call to loans-service)
        try {
            bookService.reserve(bookId);
        } catch (ConflictException e) {
            loanReq.setStatus(LoanRequestStatus.FAILED);
            loanRequestRepository.save(loanReq);
            throw e;
        }
        log.info("Book reserved: bookId={}, requestId={}", bookId, requestId);

        // 4. Call loans-service with retries and exponential backoff
        LoanResponse loanResponse = callLoansServiceWithRetry(loanReq, userId, bookId, requestId);

        if (loanResponse != null) {
            // 5. Success path
            loanReq.setStatus(LoanRequestStatus.CONFIRMED);
            loanReq.setLoanId(loanResponse.id());
            loanRequestRepository.save(loanReq);
            log.info("Loan CONFIRMED: requestId={}, loanId={}", requestId, loanResponse.id());
            return LoanRequestResponse.from(loanReq);
        }

        // 6. Retries exhausted — verify with loans-service before deciding
        log.warn("Retries exhausted for requestId={}, verifying with loans-service...", requestId);
        return resolveAfterRetryExhaustion(loanReq, bookId, requestId);
    }

    // ── Return loan ──────────────────────────────────────────────────────────

    @Transactional
    public void returnLoan(UUID loanRequestId) {
        LoanRequest loanReq = loanRequestRepository.findById(loanRequestId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan request not found: " + loanRequestId));

        if (loanReq.getStatus() != LoanRequestStatus.CONFIRMED) {
            throw new ConflictException(
                    "Cannot return loan in status: " + loanReq.getStatus());
        }

        UUID loanId = loanReq.getLoanId();
        if (loanId == null) {
            throw new ConflictException("Loan ID not available for request: " + loanRequestId);
        }

        // Call loans-service with retries — loans-service will call /release on library-service
        ServiceUnavailableException lastEx = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            try {
                loanClient.returnLoan(loanId);
                log.info("Return processed by loans-service: loanId={}", loanId);
                return;
            } catch (ServiceUnavailableException e) {
                lastEx = e;
                log.warn("loans-service return failed, attempt {}/{}: {}", i + 1, MAX_ATTEMPTS, e.getMessage());
                sleep(BACKOFF_MS[i]);
            }
        }
        throw new ServiceUnavailableException("loans-service unavailable, return could not be processed", lastEx);
    }

    // ── Proxy queries to loans-service ───────────────────────────────────────

    public List<LoanResponse> getActiveLoans(UUID userId) {
        return loanClient.getActiveLoans(userId);
    }

    public List<LoanResponse> getLoanHistory(UUID userId) {
        return loanClient.getLoanHistory(userId);
    }

    // ── Private saga helpers ─────────────────────────────────────────────────

    private LoanResponse callLoansServiceWithRetry(
            LoanRequest loanReq, UUID userId, UUID bookId, UUID requestId) {

        LoanServiceRequest serviceReq = new LoanServiceRequest(requestId, userId, bookId);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return loanClient.createLoan(serviceReq);
            } catch (ServiceUnavailableException e) {
                loanReq.setAttempts((short) (loanReq.getAttempts() + 1));
                loanRequestRepository.save(loanReq);
                log.warn("loans-service call failed, attempt {}/{}: {}", attempt + 1, MAX_ATTEMPTS, e.getMessage());

                if (attempt < MAX_ATTEMPTS - 1) {
                    sleep(BACKOFF_MS[attempt]);
                }
            }
        }
        return null;
    }

    private LoanRequestResponse resolveAfterRetryExhaustion(
            LoanRequest loanReq, UUID bookId, UUID requestId) {

        try {
            Optional<LoanResponse> existing = loanClient.findByRequestId(requestId);

            if (existing.isPresent()) {
                // Loan was actually created despite the network error — confirm, don't compensate
                loanReq.setStatus(LoanRequestStatus.CONFIRMED);
                loanReq.setLoanId(existing.get().id());
                loanRequestRepository.save(loanReq);
                log.info("Loan confirmed after verification: requestId={}, loanId={}", requestId, existing.get().id());
                return LoanRequestResponse.from(loanReq);
            }

            // Loan was NOT created — compensate (release the reserved copy)
            log.info("Loan not found in loans-service, compensating: requestId={}", requestId);
            compensate(loanReq, bookId, requestId);
            throw new ServiceUnavailableException(
                    "loans-service unavailable after retries. Loan request cancelled and copy released.");

        } catch (ServiceUnavailableException verifyEx) {
            if (loanReq.getStatus() == LoanRequestStatus.COMPENSATED) {
                // Already compensated above — re-throw the original error
                throw verifyEx;
            }
            // Verification also failed — leave in PENDING, never compensate blindly
            log.error("Verification also failed for requestId={}. Leaving in PENDING for manual recovery.", requestId);
            loanRequestRepository.save(loanReq);
            throw new ServiceUnavailableException(
                    "loans-service unavailable and could not verify. Request left in PENDING for recovery.");
        }
    }

    private void compensate(LoanRequest loanReq, UUID bookId, UUID requestId) {
        loanReq.setStatus(LoanRequestStatus.COMPENSATING);
        loanRequestRepository.save(loanReq);
        log.info("Releasing reserved copy: bookId={}, requestId={}", bookId, requestId);

        bookService.release(bookId);

        loanReq.setStatus(LoanRequestStatus.COMPENSATED);
        loanRequestRepository.save(loanReq);
        log.info("Compensation complete: requestId={}", requestId);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
