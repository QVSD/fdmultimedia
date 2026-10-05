package com.fdmultimedia.api.shared.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiInstanceHeaderFilter extends OncePerRequestFilter {
    private final ApiInstanceIdentity identity;

    public ApiInstanceHeaderFilter(ObjectProvider<ApiInstanceIdentity> identity) {
        this.identity = identity.getIfAvailable(ApiInstanceIdentity::new);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-FDM-API-Instance", identity.value());
        chain.doFilter(request, response);
    }
}
