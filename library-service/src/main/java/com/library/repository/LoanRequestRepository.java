package com.library.repository;

import com.library.model.LoanRequest;
import com.library.model.LoanRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoanRequestRepository extends JpaRepository<LoanRequest, UUID> {

    Optional<LoanRequest> findByRequestId(UUID requestId);

    List<LoanRequest> findByUserId(UUID userId);

    List<LoanRequest> findByUserIdAndStatus(UUID userId, LoanRequestStatus status);

    List<LoanRequest> findByStatus(LoanRequestStatus status);
}
