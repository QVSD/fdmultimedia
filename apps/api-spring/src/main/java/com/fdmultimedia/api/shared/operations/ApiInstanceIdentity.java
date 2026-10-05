package com.fdmultimedia.api.shared.operations;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ApiInstanceIdentity {
    private final String value;

    public ApiInstanceIdentity() {
        value = Optional.ofNullable(System.getenv("HOSTNAME"))
                .filter(name -> !name.isBlank())
                .orElseGet(() -> "local-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public String value() {
        return value;
    }
}
