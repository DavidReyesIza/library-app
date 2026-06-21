package com.library.controller;

import com.library.dto.book.BookResponse;
import com.library.service.BookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal endpoints consumed only by loans-service.
 * Protected by InternalApiKeyFilter — no JWT required.
 */
@RestController
@RequestMapping("/internal/books")
@RequiredArgsConstructor
public class InternalBookController {

    private final BookService bookService;

    /**
     * Atomically decrements availableCopies if > 0.
     * Called by the saga orchestrator in library-service before delegating to loans-service.
     * Returns 409 if no copies are available.
     */
    @PostMapping("/{id}/reserve")
    public ResponseEntity<BookResponse> reserve(@PathVariable UUID id) {
        return ResponseEntity.ok(bookService.reserve(id));
    }

    /**
     * Increments availableCopies (compensation or loan return).
     * Called on saga compensation or when loans-service processes a return.
     */
    @PostMapping("/{id}/release")
    public ResponseEntity<BookResponse> release(@PathVariable UUID id) {
        return ResponseEntity.ok(bookService.release(id));
    }
}
