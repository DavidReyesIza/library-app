package com.library.client;

import com.library.dto.loan.LoanResponse;
import com.library.dto.loan.LoanServiceRequest;
import com.library.exception.ServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoanClientImpl implements LoanClient {

    private final RestClient loansRestClient;

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Override
    public LoanResponse createLoan(LoanServiceRequest request) {
        log.info("Calling loans-service: POST /loans, requestId={}, userId={}, bookId={}",
                request.requestId(), request.userId(), request.bookId());
        try {
            return loansRestClient.post()
                    .uri("/loans")
                    .header("X-Internal-Api-Key", internalApiKey)
                    .body(request)
                    .retrieve()
                    .body(LoanResponse.class);
        } catch (RestClientResponseException e) {
            log.error("loans-service returned error: requestId={}, status={}, body={}",
                    request.requestId(), e.getStatusCode(), e.getResponseBodyAsString());
            throw new ServiceUnavailableException("loans-service error: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            log.error("loans-service unreachable: requestId={}, error={}", request.requestId(), e.getMessage());
            throw new ServiceUnavailableException("loans-service unreachable", e);
        }
    }

    @Override
    public Optional<LoanResponse> findByRequestId(UUID requestId) {
        log.info("Verifying loan in loans-service: requestId={}", requestId);
        try {
            LoanResponse response = loansRestClient.get()
                    .uri("/loans/by-request-id/{requestId}", requestId)
                    .header("X-Internal-Api-Key", internalApiKey)
                    .retrieve()
                    .onStatus(status -> status == HttpStatus.NOT_FOUND, (req, res) -> {})
                    .body(LoanResponse.class);
            return Optional.ofNullable(response);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            log.error("loans-service verification error: status={}", e.getStatusCode());
            throw new ServiceUnavailableException("loans-service verification error", e);
        } catch (ResourceAccessException e) {
            log.error("loans-service unreachable during verification: {}", e.getMessage());
            throw new ServiceUnavailableException("loans-service unreachable during verification", e);
        }
    }

    @Override
    public void returnLoan(UUID loanId) {
        log.info("Calling loans-service: POST /loans/{}/return", loanId);
        try {
            loansRestClient.post()
                    .uri("/loans/{id}/return", loanId)
                    .header("X-Internal-Api-Key", internalApiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("loans-service return error: status={}", e.getStatusCode());
            throw new ServiceUnavailableException("loans-service return error: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            log.error("loans-service unreachable during return: {}", e.getMessage());
            throw new ServiceUnavailableException("loans-service unreachable during return", e);
        }
    }

    @Override
    public List<LoanResponse> getActiveLoans(UUID userId) {
        try {
            return loansRestClient.get()
                    .uri("/loans/active?userId={userId}", userId)
                    .header("X-Internal-Api-Key", internalApiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (ResourceAccessException e) {
            throw new ServiceUnavailableException("loans-service unreachable", e);
        }
    }

    @Override
    public List<LoanResponse> getLoanHistory(UUID userId) {
        try {
            return loansRestClient.get()
                    .uri("/loans/history?userId={userId}", userId)
                    .header("X-Internal-Api-Key", internalApiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (ResourceAccessException e) {
            throw new ServiceUnavailableException("loans-service unreachable", e);
        }
    }
}
