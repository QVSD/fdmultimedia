package com.fdmultimedia.api.auth;

import com.fdmultimedia.api.auth.dto.AuthSessionResponse;
import com.fdmultimedia.api.auth.dto.LoginRequest;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.users.EmailNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final AuthService authService;
    private final EmailNormalizer emailNormalizer;

    public AuthController(
            AuthenticationManager authenticationManager,
            AuthService authService,
            EmailNormalizer emailNormalizer) {
        this.authenticationManager = authenticationManager;
        this.authService = authService;
        this.emailNormalizer = emailNormalizer;
    }

    @PostMapping("/login")
    public AuthSessionResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        String email = emailNormalizer.normalize(request.email());
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            servletRequest.getSession(true).setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                    context);

            log.info("User {} logged in", email);
            return authService.sessionFor((AuthenticatedUser) authentication.getPrincipal());
        } catch (BadCredentialsException ex) {
            log.info("Failed login attempt for {}", email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        } catch (AuthenticationException ex) {
            log.info("Failed login attempt for {}", email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            log.info("User {} logged out", authentication.getName());
        }
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        response.setHeader("Clear-Site-Data", "\"cookies\"");
    }

    @GetMapping("/me")
    public AuthSessionResponse me(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return authService.sessionFor(principal);
    }
}
