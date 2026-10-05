package com.fdmultimedia.api.shared.operations;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsService operations;

    public OperationsController(OperationsService operations) {
        this.operations = operations;
    }

    @GetMapping("/status")
    public OperationsService.Status status(Authentication authentication) {
        return operations.status((AuthenticatedUser) authentication.getPrincipal());
    }
}
