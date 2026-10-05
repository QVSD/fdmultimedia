package com.fdmultimedia.api.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before the shared-session and security filters. When PostgreSQL is unreachable (pool exhausted, connection refused)
 * every request would otherwise surface as an uncontrolled 500 with a stack trace per request. This answers a clean,
 * retryable 503 and logs one bounded line per interval. Liveness stays process-oriented; readiness reports the database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DatabaseUnavailableFilter extends OncePerRequestFilter {
    static final long LOG_INTERVAL_MILLIS = 30_000;
    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);
    private final AtomicLong lastLogged = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | RuntimeException failure) {
            if (!isDatabaseUnavailable(failure) || response.isCommitted()) throw failure;
            logBounded();
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", "5");
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":503,\"error\":\"Service Unavailable\",\"message\":\"DATABASE_UNAVAILABLE\"}");
        }
    }

    static boolean isDatabaseUnavailable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CannotGetJdbcConnectionException || current instanceof CannotCreateTransactionException
                    || current instanceof DataAccessResourceFailureException || current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException || current instanceof SQLRecoverableException) {
                return true;
            }
            if (current.getCause() == current) return false;
        }
        return false;
    }

    private void logBounded() {
        long now = System.currentTimeMillis();
        long previous = lastLogged.get();
        if (now - previous >= LOG_INTERVAL_MILLIS && lastLogged.compareAndSet(previous, now)) {
            log.warn("Database unavailable; answering 503 (further occurrences suppressed for {} s)", LOG_INTERVAL_MILLIS / 1000);
        }
    }
}
