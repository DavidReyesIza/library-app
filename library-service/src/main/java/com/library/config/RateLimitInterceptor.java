package com.library.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter por IP: 20 requests por minuto.
 * Aplica a todos los endpoints de la API pública (/auth/**, /books/**, /loans/**).
 *
 * Implementado con Bucket4j (token bucket algorithm):
 * - Cada IP obtiene un bucket independiente con capacidad de 20 tokens.
 * - Se recarga 1 token cada 3 segundos (= 20/min).
 * - Si el bucket está vacío, se devuelve 429 Too Many Requests.
 *
 * Limitación: los buckets viven en memoria (no en Redis), por lo que en entornos
 * multi-instancia cada réplica tiene su propio límite independiente. Para rate
 * limiting distribuido real se necesitaría Bucket4j + Redis.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int REQUESTS_PER_MINUTE = 20;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String ip = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, this::newBucket);

        if (bucket.tryConsume(1)) {
            response.addHeader("X-RateLimit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\"," +
                "\"message\":\"Rate limit exceeded. Max " + REQUESTS_PER_MINUTE +
                " requests per minute per IP.\"}");
        return false;
    }

    private Bucket newBucket(String ip) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(REQUESTS_PER_MINUTE)
                        .refillIntervally(REQUESTS_PER_MINUTE, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
