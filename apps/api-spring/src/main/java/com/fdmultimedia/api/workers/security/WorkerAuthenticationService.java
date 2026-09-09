package com.fdmultimedia.api.workers.security;

import com.fdmultimedia.api.workers.WorkerCredentialRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class WorkerAuthenticationService {

    private static final String SCHEME = "WorkerToken ";

    private final WorkerCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;

    public WorkerAuthenticationService(WorkerCredentialRepository credentials, PasswordEncoder passwordEncoder) {
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<WorkerPrincipal> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(SCHEME)) {
            return Optional.empty();
        }

        String token = authorizationHeader.substring(SCHEME.length()).trim();
        int separator = token.indexOf('.');
        if (separator < 1 || separator == token.length() - 1) {
            return Optional.empty();
        }

        try {
            UUID credentialId = UUID.fromString(token.substring(0, separator));
            String secret = token.substring(separator + 1);
            return credentials.findById(credentialId)
                    .filter(credential -> credential.isEnabled()
                            && passwordEncoder.matches(secret, credential.getSecretHash()))
                    .map(WorkerPrincipal::new);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
