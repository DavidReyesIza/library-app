package com.library.controller;

import com.library.dto.loan.CreateLoanRequest;
import com.library.dto.loan.LoanRequestResponse;
import com.library.model.User;
import com.library.service.LoanOrchestrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/loans")
@RequiredArgsConstructor
public class LoanController {

    private final LoanOrchestrationService loanOrchestrationService;

    @PostMapping
    public ResponseEntity<LoanRequestResponse> createLoan(
            @AuthenticationPrincipal User currentUser,
            @Valid @RequestBody CreateLoanRequest request) {
        LoanRequestResponse response = loanOrchestrationService.createLoan(
                currentUser.getId(), request.bookId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/return")
    public ResponseEntity<Void> returnLoan(@PathVariable UUID id) {
        loanOrchestrationService.returnLoan(id);
        return ResponseEntity.noContent().build();
    }

    /** CONFIRMED loans for the current user. Use the {@code id} field for POST /loans/{id}/return. */
    @GetMapping("/me/active")
    public ResponseEntity<List<LoanRequestResponse>> getActiveLoans(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(loanOrchestrationService.getMyActiveLoanRequests(currentUser.getId()));
    }

    /** Full loan history for the current user. Use the {@code id} field for POST /loans/{id}/return. */
    @GetMapping("/me/history")
    public ResponseEntity<List<LoanRequestResponse>> getLoanHistory(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(loanOrchestrationService.getMyLoanRequests(currentUser.getId()));
    }
}
