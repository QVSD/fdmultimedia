package com.fdmultimedia.api.shared.web;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.ServletException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DatabaseUnavailableFilterTest {
    private final DatabaseUnavailableFilter filter = new DatabaseUnavailableFilter();

    private MockHttpServletResponse run(Throwable failure) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse res) throws java.io.IOException, ServletException {
                if (failure instanceof RuntimeException r) throw r;
                if (failure instanceof ServletException s) throw s;
            }
        };
        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"), response, chain);
        return response;
    }

    @Test
    void databaseOutageBecomesACleanRetryable503() throws Exception {
        MockHttpServletResponse response = run(new CannotGetJdbcConnectionException("pool exhausted", new SQLTransientConnectionException("refused")));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentAsString()).contains("DATABASE_UNAVAILABLE").doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void aWrappedConnectionFailureDeepInTheCauseChainIsRecognized() throws Exception {
        MockHttpServletResponse response = run(new ServletException("filter failed", new IllegalStateException(new SQLTransientConnectionException("timeout"))));
        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void unrelatedFailuresAreNeverMaskedAndSuccessPassesThrough() throws Exception {
        assertThatThrownBy(() -> run(new IllegalArgumentException("business bug"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(run(null).getStatus()).isEqualTo(200);
    }

    @Test
    void classificationHandlesSelfReferentialCausesWithoutLooping() {
        Exception loop = new Exception("x") { @Override public synchronized Throwable getCause() { return this; } };
        assertThat(DatabaseUnavailableFilter.isDatabaseUnavailable(loop)).isFalse();
    }
}
