package com.fdmultimedia.api.accounts;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
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

    public SocialAccountController(SocialAccountService socialAccountService) {
        this.socialAccountService = socialAccountService;
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
}
