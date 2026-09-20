package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.DashboardModels.*;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/analytics/dashboard")
public class PublicationDashboardController {
    private final PublicationDashboardService service;

    public PublicationDashboardController(PublicationDashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public Summary summary(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute PublicationDashboardService.Request request) {
        return service.summary(user, request);
    }

    @GetMapping("/trend")
    public Trend trend(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute PublicationDashboardService.Request request,
            @RequestParam(defaultValue = "VIEWS") String metric) {
        return service.trend(user, request, metric);
    }

    @GetMapping("/breakdown")
    public Breakdown breakdown(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute PublicationDashboardService.Request request,
            @RequestParam(defaultValue = "ROBOT") String dimension) {
        return service.breakdown(user, request, dimension);
    }

    @GetMapping("/filters")
    public FilterOptions filters(@AuthenticationPrincipal AuthenticatedUser user,
            @ModelAttribute PublicationDashboardService.Request request) {
        return service.filters(user, request);
    }
}
