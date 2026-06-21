package com.library.repository;

import com.library.model.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID>, JpaSpecificationExecutor<Book> {

    boolean existsByIsbn(String isbn);

    Optional<Book> findByIsbn(String isbn);

    /**
     * Atomic conditional decrement — returns 1 if successful, 0 if no copies available.
     * Used by the saga orchestrator before calling loans-service.
     */
    @Modifying
    @Query("UPDATE Book b SET b.availableCopies = b.availableCopies - 1, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.id = :id AND b.availableCopies > 0")
    int decrementAvailableCopies(@Param("id") UUID id);

    /**
     * Releases a reserved copy (compensation or return).
     * Returns 1 if successful, 0 if book not found.
     */
    @Modifying
    @Query("UPDATE Book b SET b.availableCopies = b.availableCopies + 1, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.id = :id AND b.availableCopies < b.totalCopies")
    int incrementAvailableCopies(@Param("id") UUID id);
}
