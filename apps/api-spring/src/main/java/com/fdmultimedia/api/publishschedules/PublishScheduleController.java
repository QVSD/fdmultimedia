package com.fdmultimedia.api.publishschedules;

import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PublishScheduleController {

    private final PublishScheduleService publishScheduleService;

    public PublishScheduleController(PublishScheduleService publishScheduleService) {
        this.publishScheduleService = publishScheduleService;
    }

    @PostMapping("/content-drafts/{draftId}/schedules")
    public PublishScheduleSummary create(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID draftId,
            @Valid @RequestBody CreatePublishScheduleRequest request) {
        return publishScheduleService.create(principal, draftId, request);
    }

    @GetMapping("/publish-schedules")
    public List<PublishScheduleSummary> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(required = false) PublishScheduleStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID contentDraftId) {
        return publishScheduleService.list(principal, status, from, to, contentDraftId);
    }

    @GetMapping("/publish-schedules/calendar")
    public List<PublishScheduleSummary> calendar(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        return publishScheduleService.calendar(principal, from, to);
    }

    @GetMapping("/publish-schedules/{scheduleId}")
    public PublishScheduleSummary get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID scheduleId) {
        return publishScheduleService.getFor(principal, scheduleId);
    }

    @PostMapping("/publish-schedules/{scheduleId}/cancel")
    public PublishScheduleSummary cancel(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID scheduleId) {
        return publishScheduleService.cancel(principal, scheduleId);
    }

    @PatchMapping("/publish-schedules/{scheduleId}")
    public PublishScheduleSummary reschedule(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RescheduleRequest request) {
        return publishScheduleService.reschedule(principal, scheduleId, request);
    }
}
