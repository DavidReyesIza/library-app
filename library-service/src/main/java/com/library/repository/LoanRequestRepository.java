package com.library.repository;

import com.library.model.LoanRequest;
import com.library.model.LoanRequestStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanRequestRepository extends JpaRepository<LoanRequest, UUID> {

    Optional<LoanRequest> findByRequestId(UUID requestId);

    List<LoanRequest> findByUserId(UUID userId);

    List<LoanRequest> findByUserIdAndStatus(UUID userId, LoanRequestStatus status);

    List<LoanRequest> findByStatus(LoanRequestStatus status);

    /**
     * Selects stale PENDING records with a pessimistic write lock and SKIP LOCKED,
     * so concurrent recovery job instances (multiple replicas) never process the same row.
     * timeout=-2 maps to SKIP LOCKED in PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT lr FROM LoanRequest lr WHERE lr.status = :status AND lr.createdAt < :before")
    List<LoanRequest> findStaleByStatusWithLock(
            @Param("status") LoanRequestStatus status,
            @Param("before") Instant before);
}
