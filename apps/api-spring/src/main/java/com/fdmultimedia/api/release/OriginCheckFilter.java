package com.fdmultimedia.api.release;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defence in depth for the production origin: a state-changing browser request whose {@code Origin} header names anything other than the
 * canonical public origin is rejected before it reaches CSRF or the controller. Requests without an Origin header (server-to-server
 * tools) are left to CSRF and authentication, and Worker-agent calls are never browser traffic so they are exempt.
 */
@Component
@Profile("prod")
public class OriginCheckFilter extends OncePerRequestFilter {
    private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final String allowed;

    public OriginCheckFilter(@Value("${app.public-origin:}") String publicOrigin) {
        this.allowed = ProductionConfigurationValidator.canonicalOrigin(publicOrigin);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !UNSAFE.contains(request.getMethod()) || !path.startsWith("/api/") || path.startsWith("/api/worker-agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !ProductionConfigurationValidator.canonicalOrigin(origin).equals(allowed)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
