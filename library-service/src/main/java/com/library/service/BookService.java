package com.library.service;

import com.library.dto.book.BookRequest;
import com.library.dto.book.BookResponse;
import com.library.dto.book.BookUpdateRequest;
import com.library.exception.BadRequestException;
import com.library.exception.ConflictException;
import com.library.exception.ResourceNotFoundException;
import com.library.model.Book;
import com.library.repository.BookRepository;
import com.library.repository.spec.BookSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;

    @Transactional
    public BookResponse create(BookRequest request) {
        if (bookRepository.existsByIsbn(request.isbn())) {
            throw new ConflictException("ISBN already exists: " + request.isbn());
        }

        Book book = Book.builder()
                .title(request.title())
                .author(request.author())
                .isbn(request.isbn())
                .publicationYear(request.publicationYear())
                .genre(request.genre())
                .totalCopies(request.totalCopies())
                .availableCopies(request.totalCopies())
                .build();

        return BookResponse.from(bookRepository.save(book));
    }

    @Transactional(readOnly = true)
    public Page<BookResponse> findAll(String author, String genre, Boolean available, Pageable pageable) {
        Specification<Book> spec = Specification.where(null);

        if (author != null && !author.isBlank()) {
            spec = spec.and(BookSpecification.byAuthor(author));
        }
        if (genre != null && !genre.isBlank()) {
            spec = spec.and(BookSpecification.byGenre(genre));
        }
        if (Boolean.TRUE.equals(available)) {
            spec = spec.and(BookSpecification.isAvailable());
        }

        return bookRepository.findAll(spec, pageable).map(BookResponse::from);
    }

    @Transactional(readOnly = true)
    public BookResponse findById(UUID id) {
        return BookResponse.from(getOrThrow(id));
    }

    @Transactional
    public BookResponse update(UUID id, BookUpdateRequest request) {
        Book book = getOrThrow(id);

        if (request.isbn() != null && !request.isbn().equals(book.getIsbn())) {
            if (bookRepository.existsByIsbn(request.isbn())) {
                throw new ConflictException("ISBN already exists: " + request.isbn());
            }
            book.setIsbn(request.isbn());
        }
        if (request.title() != null)           book.setTitle(request.title());
        if (request.author() != null)          book.setAuthor(request.author());
        if (request.publicationYear() != null) book.setPublicationYear(request.publicationYear());
        if (request.genre() != null)           book.setGenre(request.genre());

        if (request.totalCopies() != null) {
            int delta = request.totalCopies() - book.getTotalCopies();
            int newAvailable = book.getAvailableCopies() + delta;
            if (newAvailable < 0) {
                throw new BadRequestException(
                        "Cannot reduce total copies below the number of active loans (%d loaned out)"
                                .formatted(book.getTotalCopies() - book.getAvailableCopies()));
            }
            book.setTotalCopies(request.totalCopies());
            book.setAvailableCopies(newAvailable);
        }

        log.info("Book updated: id={}, isbn={}", id, book.getIsbn());
        return BookResponse.from(bookRepository.save(book));
    }

    @Transactional
    public void delete(UUID id) {
        if (!bookRepository.existsById(id)) {
            throw new ResourceNotFoundException("Book not found: " + id);
        }
        bookRepository.deleteById(id);
        log.info("Book deleted: id={}", id);
    }

    // ── Internal operations (used by the saga orchestrator) ──────────────────

    /**
     * Atomically decrements availableCopies if copies are available.
     *
     * @return the updated book after successful reservation
     * @throws ConflictException if no copies are available
     * @throws ResourceNotFoundException if the book does not exist
     */
    @Transactional
    public BookResponse reserve(UUID id) {
        if (!bookRepository.existsById(id)) {
            throw new ResourceNotFoundException("Book not found: " + id);
        }
        int updated = bookRepository.decrementAvailableCopies(id);
        if (updated == 0) {
            throw new ConflictException("No available copies for book: " + id);
        }
        log.info("Book reserved: id={}", id);
        return BookResponse.from(getOrThrow(id));
    }

    /**
     * Releases a reserved copy (compensation or loan return).
     */
    @Transactional
    public BookResponse release(UUID id) {
        if (!bookRepository.existsById(id)) {
            throw new ResourceNotFoundException("Book not found: " + id);
        }
        bookRepository.incrementAvailableCopies(id);
        log.info("Book released: id={}", id);
        return BookResponse.from(getOrThrow(id));
    }

    private Book getOrThrow(UUID id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Book not found: " + id));
    }
}
