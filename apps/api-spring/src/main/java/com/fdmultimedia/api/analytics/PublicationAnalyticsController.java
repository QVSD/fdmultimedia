package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PublicationAnalyticsController {
    private final PublicationAnalyticsService service;
    private final PublicationAttributionService attribution;

    public PublicationAnalyticsController(PublicationAnalyticsService service,
            PublicationAttributionService attribution) {
        this.service = service;
        this.attribution = attribution;
    }

    @GetMapping("/publications/{id}/attribution")
    public PublicationAttribution attribution(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.attribution(user, id, attribution);
    }

    @GetMapping("/publications/{id}/analytics")
    public List<PublicationAnalyticsSnapshot> history(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestParam(defaultValue = "30") int limit) {
        return service.history(user, id, limit);
    }

    @GetMapping("/publications/{id}/analytics/latest")
    public PublicationAnalyticsSnapshot latest(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.latest(user, id);
    }

    @GetMapping("/publications/{id}/analytics/state")
    public PublicationAnalyticsState state(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.state(user, id);
    }

    @PostMapping("/publications/{id}/analytics/refresh")
    public PublicationAnalyticsSnapshot refresh(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return service.refresh(user, id);
    }

    @GetMapping("/analytics/publications")
    public List<PublicationAnalyticsSnapshot> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam Instant from, @RequestParam Instant to, @RequestParam(defaultValue = "50") int limit) {
        return service.list(user, from, to, limit);
    }
}
