package com.library.dto.book;

import com.library.model.Book;

import java.time.Instant;
import java.util.UUID;

public record BookResponse(
        UUID id,
        String title,
        String author,
        String isbn,
        int publicationYear,
        String genre,
        int totalCopies,
        int availableCopies,
        Instant createdAt,
        Instant updatedAt
) {
    public static BookResponse from(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getIsbn(),
                book.getPublicationYear(),
                book.getGenre(),
                book.getTotalCopies(),
                book.getAvailableCopies(),
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }
}
