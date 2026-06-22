package com.library.service;

import com.library.client.LoanClient;
import com.library.dto.loan.LoanResponse;
import com.library.model.LoanRequest;
import com.library.model.LoanRequestStatus;
import com.library.repository.LoanRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Recovery job for LoanRequest records stuck in PENDING status.
 *
 * A LoanRequest reaches PENDING when:
 *   - loans-service was unreachable during all retries, AND
 *   - the verification call (GET /loans/by-request-id) also failed.
 *
 * In that state the book copy is decremented in library_db but no loan exists in loans_db.
 * This job periodically resolves these records when loans-service is back online.
 *
 * Multi-instance safety: uses FOR UPDATE SKIP LOCKED so concurrent replicas never process
 * the same row. Each replica independently picks a non-overlapping set of rows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingLoanRecoveryJob {

    private final LoanRequestRepository loanRequestRepository;
    private final LoanClient loanClient;
    private final BookService bookService;

    @Value("${recovery.pending.stale-minutes:10}")
    private int staleMinutes;

    /**
     * Runs every 5 minutes. Only processes records older than {@code staleMinutes} minutes
     * to avoid interfering with requests that are still in-flight.
     */
    @Scheduled(fixedDelayString = "${recovery.pending.interval-ms:300000}")
    @Transactional
    public void recoverStalePending() {
        Instant cutoff = Instant.now().minus(staleMinutes, ChronoUnit.MINUTES);
        List<LoanRequest> stale = loanRequestRepository
                .findStaleByStatusWithLock(LoanRequestStatus.PENDING, cutoff);

        if (stale.isEmpty()) {
            return;
        }

        log.info("Recovery job found {} stale PENDING record(s) older than {} minutes",
                stale.size(), staleMinutes);

        for (LoanRequest lr : stale) {
            recoverOne(lr);
        }
    }

    private void recoverOne(LoanRequest lr) {
        log.info("Recovering PENDING requestId={}, bookId={}, createdAt={}",
                lr.getRequestId(), lr.getBookId(), lr.getCreatedAt());
        try {
            Optional<LoanResponse> existing = loanClient.findByRequestId(lr.getRequestId());

            if (existing.isPresent()) {
                // loans-service did register the loan — confirm it
                lr.setStatus(LoanRequestStatus.CONFIRMED);
                lr.setLoanId(existing.get().id());
                loanRequestRepository.save(lr);
                log.info("Recovery: CONFIRMED requestId={}, loanId={}",
                        lr.getRequestId(), existing.get().id());
            } else {
                // loans-service never created the loan — release the reserved copy
                lr.setStatus(LoanRequestStatus.COMPENSATING);
                loanRequestRepository.save(lr);

                bookService.release(lr.getBookId());

                lr.setStatus(LoanRequestStatus.COMPENSATED);
                loanRequestRepository.save(lr);
                log.info("Recovery: COMPENSATED requestId={}, bookId={}",
                        lr.getRequestId(), lr.getBookId());
            }
        } catch (Exception e) {
            // loans-service still down — leave in PENDING, retry on next cycle
            log.warn("Recovery skipped requestId={}: loans-service still unavailable ({})",
                    lr.getRequestId(), e.getMessage());
        }
    }
}
