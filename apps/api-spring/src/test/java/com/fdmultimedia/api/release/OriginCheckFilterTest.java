package com.fdmultimedia.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginCheckFilterTest {
    private final OriginCheckFilter filter = new OriginCheckFilter("https://app.pilot.example.org");

    private MockHttpServletResponse run(String method, String path, String origin) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        if (origin != null) request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() == null && response.getStatus() == 200) response.setStatus(-1);
        return response;
    }

    @Test
    void sameOriginMutationPasses() throws Exception {
        assertThat(run("POST", "/api/robots", "https://app.pilot.example.org").getStatus()).isEqualTo(200);
        assertThat(run("DELETE", "/api/personas/1", "https://APP.pilot.example.org:443").getStatus()).isEqualTo(200);
    }

    @Test
    void foreignOrNullOriginMutationIsForbidden() throws Exception {
        assertThat(run("POST", "/api/robots", "https://evil.example.com").getStatus()).isEqualTo(403);
        assertThat(run("PUT", "/api/robots/1", "http://app.pilot.example.org").getStatus()).isEqualTo(403);
        assertThat(run("PATCH", "/api/robots/1", "null").getStatus()).isEqualTo(403);
        assertThat(run("POST", "/api/operations/incidents/x/acknowledge", "https://app.pilot.example.org.evil.com").getStatus()).isEqualTo(403);
    }

    @Test
    void requestsWithoutOriginAreLeftToCsrfAndAuthentication() throws Exception {
        assertThat(run("POST", "/api/robots", null).getStatus()).isEqualTo(200);
    }

    @Test
    void safeMethodsWorkerAgentAndNonApiPathsAreNotChecked() throws Exception {
        assertThat(run("GET", "/api/robots", "https://evil.example.com").getStatus()).isEqualTo(200);
        assertThat(run("POST", "/api/worker-agent/jobs/claim", "https://evil.example.com").getStatus()).isEqualTo(200);
        assertThat(run("POST", "/something", "https://evil.example.com").getStatus()).isEqualTo(200);
    }
}
