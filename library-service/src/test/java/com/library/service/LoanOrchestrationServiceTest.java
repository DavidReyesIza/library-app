package com.library.service;

import com.library.client.LoanClient;
import com.library.dto.loan.LoanRequestResponse;
import com.library.dto.loan.LoanResponse;
import com.library.dto.loan.LoanServiceRequest;
import com.library.exception.ServiceUnavailableException;
import com.library.model.Book;
import com.library.model.LoanRequest;
import com.library.model.LoanRequestStatus;
import com.library.repository.BookRepository;
import com.library.repository.LoanRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoanOrchestrationServiceTest {

    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOOK_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID LOAN_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock
    private LoanRequestRepository loanRequestRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private LoanClient loanClient;

    private BookService bookService;
    private LoanOrchestrationService loanOrchestrationService;

    @BeforeEach
    void setUp() {
        bookService = new BookService(bookRepository);
        loanOrchestrationService = new LoanOrchestrationService(
                loanRequestRepository, bookService, loanClient);

        when(loanRequestRepository.save(any(LoanRequest.class))).thenAnswer(invocation -> {
            LoanRequest loanRequest = invocation.getArgument(0);
            if (loanRequest.getId() == null) {
                loanRequest.setId(UUID.fromString("66666666-6666-6666-6666-666666666666"));
            }
            return loanRequest;
        });

        stubBookWithAvailableCopies(3, 2);
    }

    @Test
    void createLoan_confirmsWhenLoansServiceSucceeds() {
        LoanResponse loanResponse = new LoanResponse(
                LOAN_ID, UUID.randomUUID(), USER_ID, BOOK_ID, "ACTIVE", Instant.now(), null);

        when(loanClient.createLoan(any(LoanServiceRequest.class))).thenReturn(loanResponse);

        LoanRequestResponse result = loanOrchestrationService.createLoan(USER_ID, BOOK_ID);

        assertThat(result.status()).isEqualTo(LoanRequestStatus.CONFIRMED);
        assertThat(result.loanId()).isEqualTo(LOAN_ID);
        assertThat(result.bookId()).isEqualTo(BOOK_ID);

        verify(bookRepository).decrementAvailableCopies(BOOK_ID);
        verify(loanClient).createLoan(any(LoanServiceRequest.class));
        verify(loanClient, never()).findByRequestId(any());
    }

    @Test
    void createLoan_compensatesWhenRetriesExhaustedAndLoanNotFoundInLoansService() {
        when(loanClient.createLoan(any(LoanServiceRequest.class)))
                .thenThrow(new ServiceUnavailableException("loans-service unreachable"));
        when(loanClient.findByRequestId(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> loanOrchestrationService.createLoan(USER_ID, BOOK_ID))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("copy released");

        verify(loanClient, times(3)).createLoan(any(LoanServiceRequest.class));
        verify(bookRepository).decrementAvailableCopies(BOOK_ID);
        verify(bookRepository).incrementAvailableCopies(BOOK_ID);

        ArgumentCaptor<LoanRequest> captor = ArgumentCaptor.forClass(LoanRequest.class);
        verify(loanRequestRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(request -> request.getStatus() == LoanRequestStatus.COMPENSATED);
    }

    @Test
    void createLoan_leavesPendingWhenVerificationAlsoFails() {
        when(loanClient.createLoan(any(LoanServiceRequest.class)))
                .thenThrow(new ServiceUnavailableException("loans-service unreachable"));
        when(loanClient.findByRequestId(any(UUID.class)))
                .thenThrow(new ServiceUnavailableException("verification failed"));

        assertThatThrownBy(() -> loanOrchestrationService.createLoan(USER_ID, BOOK_ID))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("left in PENDING");

        verify(loanClient, times(3)).createLoan(any(LoanServiceRequest.class));
        verify(bookRepository).decrementAvailableCopies(BOOK_ID);
        verify(bookRepository, never()).incrementAvailableCopies(BOOK_ID);

        ArgumentCaptor<LoanRequest> captor = ArgumentCaptor.forClass(LoanRequest.class);
        verify(loanRequestRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .noneMatch(request -> request.getStatus() == LoanRequestStatus.COMPENSATED);
        assertThat(captor.getAllValues())
                .anyMatch(request -> request.getStatus() == LoanRequestStatus.PENDING);
    }

    private void stubBookWithAvailableCopies(int totalCopies, int availableCopies) {
        Book book = Book.builder()
                .id(BOOK_ID)
                .title("Clean Code")
                .author("Robert Martin")
                .isbn("978-0132350884")
                .publicationYear(2008)
                .genre("Tech")
                .totalCopies(totalCopies)
                .availableCopies(availableCopies)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(bookRepository.existsById(BOOK_ID)).thenReturn(true);
        when(bookRepository.findById(BOOK_ID)).thenReturn(Optional.of(book));
        when(bookRepository.decrementAvailableCopies(BOOK_ID)).thenAnswer(invocation -> {
            book.setAvailableCopies(book.getAvailableCopies() - 1);
            return 1;
        });
        lenient().when(bookRepository.incrementAvailableCopies(BOOK_ID)).thenAnswer(invocation -> {
            book.setAvailableCopies(book.getAvailableCopies() + 1);
            return 1;
        });
    }
}
