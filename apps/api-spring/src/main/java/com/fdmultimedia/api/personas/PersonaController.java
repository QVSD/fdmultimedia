package com.fdmultimedia.api.personas;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/personas")
public class PersonaController {

    private final PersonaService service;

    public PersonaController(PersonaService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonaSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return service.list(principal);
    }

    @PostMapping
    public PersonaSummary create(@AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody CreatePersonaRequest request) {
        return service.create(principal, request);
    }

    @GetMapping("/{personaId}")
    public PersonaSummary get(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID personaId) {
        return service.getFor(principal, personaId);
    }

    @PatchMapping("/{personaId}")
    public PersonaSummary update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID personaId,
            @Valid @RequestBody UpdatePersonaRequest request) {
        return service.update(principal, personaId, request);
    }

    @PostMapping("/{personaId}/archive")
    public PersonaSummary archive(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID personaId) {
        return service.archive(principal, personaId);
    }

    @PostMapping("/{personaId}/restore")
    public PersonaSummary restore(@AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID personaId) {
        return service.restore(principal, personaId);
    }
}
