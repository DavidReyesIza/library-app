package com.library.controller;

import com.library.dto.loan.CreateLoanRequest;
import com.library.dto.loan.LoanRequestResponse;
import com.library.dto.loan.LoanResponse;
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

    @GetMapping("/me/active")
    public ResponseEntity<List<LoanResponse>> getActiveLoans(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(loanOrchestrationService.getActiveLoans(currentUser.getId()));
    }

    @GetMapping("/me/history")
    public ResponseEntity<List<LoanResponse>> getLoanHistory(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(loanOrchestrationService.getLoanHistory(currentUser.getId()));
    }
}
