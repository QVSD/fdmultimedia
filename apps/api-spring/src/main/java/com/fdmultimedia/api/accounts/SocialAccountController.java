package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.publishing.instagram.InstagramAccountConnectionService;
import com.fdmultimedia.api.publishing.instagram.InstagramProperties;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/social-accounts")
public class SocialAccountController {

    private final SocialAccountService socialAccountService;
    private final InstagramAccountConnectionService instagramAccountConnectionService;
    private final InstagramProperties instagramProperties;

    public SocialAccountController(
            SocialAccountService socialAccountService,
            InstagramAccountConnectionService instagramAccountConnectionService,
            InstagramProperties instagramProperties) {
        this.socialAccountService = socialAccountService;
        this.instagramAccountConnectionService = instagramAccountConnectionService;
        this.instagramProperties = instagramProperties;
    }

    @PostMapping
    public SocialAccountSummary create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody CreateSocialAccountRequest request) {
        return socialAccountService.create(principal, request);
    }

    @GetMapping
    public List<SocialAccountSummary> list(@AuthenticationPrincipal AuthenticatedUser principal) {
        return socialAccountService.listFor(principal);
    }

    @GetMapping("/{accountId}")
    public SocialAccountSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return socialAccountService.getFor(principal, accountId);
    }

    @PostMapping("/{accountId}/disconnect")
    public SocialAccountSummary disconnect(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID accountId) {
        return instagramAccountConnectionService.disconnect(principal, accountId);
    }

    /** Lets the frontend show/hide "Connect Instagram" without duplicating server config logic. */
    @GetMapping("/platforms")
    public Map<String, Boolean> platforms() {
        Map<String, Boolean> availability = new LinkedHashMap<>();
        availability.put("TEST", true);
        availability.put("INSTAGRAM", instagramProperties.isConfigured());
        return availability;
    }
}
