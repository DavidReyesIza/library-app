package com.library.service;

import com.library.dto.book.BookRequest;
import com.library.exception.ConflictException;
import com.library.model.Book;
import com.library.repository.BookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookServiceTest {

    @Mock
    private BookRepository bookRepository;

    @InjectMocks
    private BookService bookService;

    private static final UUID BOOK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void create_throwsConflictWhenIsbnAlreadyExists() {
        BookRequest request = new BookRequest(
                "Clean Code", "Robert Martin", "978-0132350884", 2008, "Tech", 3);

        when(bookRepository.existsByIsbn("978-0132350884")).thenReturn(true);

        assertThatThrownBy(() -> bookService.create(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ISBN already exists");

        verify(bookRepository, never()).save(any());
    }

    @Test
    void reserve_throwsConflictWhenNoCopiesAvailable() {
        when(bookRepository.existsById(BOOK_ID)).thenReturn(true);
        when(bookRepository.decrementAvailableCopies(BOOK_ID)).thenReturn(0);

        assertThatThrownBy(() -> bookService.reserve(BOOK_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("No available copies");

        verify(bookRepository).decrementAvailableCopies(BOOK_ID);
    }

    @Test
    void reserve_decrementsAvailableCopiesWhenBookHasStock() {
        Book book = sampleBook(BOOK_ID, 2);

        when(bookRepository.existsById(BOOK_ID)).thenReturn(true);
        when(bookRepository.decrementAvailableCopies(BOOK_ID)).thenReturn(1);
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        var response = bookService.reserve(BOOK_ID);

        assertThat(response.availableCopies()).isEqualTo(2);
        verify(bookRepository).decrementAvailableCopies(BOOK_ID);
    }

    @Test
    void release_incrementsAvailableCopies() {
        Book book = sampleBook(BOOK_ID, 1);

        when(bookRepository.existsById(BOOK_ID)).thenReturn(true);
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));

        bookService.release(BOOK_ID);

        verify(bookRepository).incrementAvailableCopies(BOOK_ID);
    }

    private Book sampleBook(UUID id, int availableCopies) {
        Instant now = Instant.now();
        return Book.builder()
                .id(id)
                .title("Clean Code")
                .author("Robert Martin")
                .isbn("978-0132350884")
                .publicationYear(2008)
                .genre("Tech")
                .totalCopies(3)
                .availableCopies(availableCopies)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
