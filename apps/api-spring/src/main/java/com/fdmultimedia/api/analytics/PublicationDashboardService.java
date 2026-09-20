package com.fdmultimedia.api.analytics;

import com.fdmultimedia.api.analytics.DashboardModels.*;
import com.fdmultimedia.api.analytics.DashboardQuery.*;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicationDashboardService {
    private final AuthService auth;
    private final PublicationDashboardStore store;
    private final Clock clock;

    public PublicationDashboardService(AuthService auth, PublicationDashboardStore store, Clock clock) {
        this.auth = auth;
        this.store = store;
        this.clock = clock;
    }

    public Summary summary(AuthenticatedUser user, Request request) {
        return store.summary(query(user, request), Instant.now(clock));
    }

    public Trend trend(AuthenticatedUser user, Request request, String metric) {
        return store.trend(query(user, request), parse(Metric.class, metric, Metric.VIEWS), Instant.now(clock));
    }

    public Breakdown breakdown(AuthenticatedUser user, Request request, String dimension) {
        return store.breakdown(query(user, request), parse(Dimension.class, dimension, Dimension.ROBOT), Instant.now(clock));
    }

    public FilterOptions filters(AuthenticatedUser user, Request request) {
        return store.options(query(user, request));
    }

    DashboardQuery query(AuthenticatedUser user, Request request) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = date(request.dateFrom(), today.minusDays(29));
        LocalDate to = date(request.dateTo(), today);
        if (from.isAfter(to) || to.isAfter(today) || ChronoUnit.DAYS.between(from, to) > 364) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dashboard date range must be within 365 days and not in the future");
        }
        String provider = request.provider();
        if (provider != null && !provider.isBlank() && !provider.equals("TEST") && !provider.equals("INSTAGRAM")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported analytics provider");
        }
        UUID workspaceId = auth.currentMembershipFor(user).getWorkspace().getId();
        return new DashboardQuery(workspaceId, from, to, parse(Window.class, request.window(), Window.LATEST),
                blankToNull(provider), uuid(request.robotId()), uuid(request.personaId()), uuid(request.contentSourceId()),
                parse(Origin.class, request.origin(), null), parse(AiUsage.class, request.aiUsage(), null));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return LocalDate.parse(value); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dashboard date"); }
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dashboard filter ID"); }
    }

    private static <T extends Enum<T>> T parse(Class<T> type, String value, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Enum.valueOf(type, value); }
        catch (RuntimeException ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dashboard option"); }
    }

    public record Request(String dateFrom, String dateTo, String window, String provider,
            String robotId, String personaId, String contentSourceId, String origin, String aiUsage) {}
}
