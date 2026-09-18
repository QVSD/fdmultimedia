package com.fdmultimedia.api.publishschedules;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountRepository;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.auth.AuthService;
import com.fdmultimedia.api.auth.security.AuthenticatedUser;
import com.fdmultimedia.api.contentdrafts.ContentDraft;
import com.fdmultimedia.api.contentdrafts.ContentDraftRepository;
import com.fdmultimedia.api.contentdrafts.ContentDraftStatus;
import com.fdmultimedia.api.publishing.PublishingEligibilityService;
import com.fdmultimedia.api.workspaces.Workspace;
import com.fdmultimedia.api.workspaces.WorkspaceMembership;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublishScheduleService {

    private final AuthService authService;
    private final PublishScheduleRepository schedules;
    private final ContentDraftRepository drafts;
    private final SocialAccountRepository socialAccounts;
    private final PublishingEligibilityService eligibilityService;
    private final PublishScheduleProperties properties;
    private final Clock clock;

    public PublishScheduleService(
            AuthService authService,
            PublishScheduleRepository schedules,
            ContentDraftRepository drafts,
            SocialAccountRepository socialAccounts,
            PublishingEligibilityService eligibilityService,
            PublishScheduleProperties properties,
            Clock clock) {
        this.authService = authService;
        this.schedules = schedules;
        this.drafts = drafts;
        this.socialAccounts = socialAccounts;
        this.eligibilityService = eligibilityService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PublishScheduleSummary create(AuthenticatedUser principal, UUID draftId, CreatePublishScheduleRequest request) {
        WorkspaceMembership membership = authService.currentMembershipFor(principal);
        Workspace workspace = membership.getWorkspace();
        ContentDraft draft = drafts.findByWorkspaceAndId(workspace, draftId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Content draft not found"));
        if (draft.getStatus() != ContentDraftStatus.READY && draft.getStatus() != ContentDraftStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Draft is not ready to schedule");
        }
        SocialAccount account = socialAccounts.findByWorkspaceAndId(workspace, request.socialAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        if (account.getStatus() != SocialAccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Social account is not active");
        }
        MediaAsset media = draft.getMediaAsset();
        eligibilityService.validateAssetEligibility(media, account.getPlatform());
        Instant scheduledFor = validateScheduledFor(request.scheduledFor());

        Instant now = Instant.now(clock);
        PublishSchedule schedule = new PublishSchedule(
                workspace, draft, media, account, draft.getCaption(), scheduledFor, membership.getUser(), now);
        return toSummary(schedules.save(schedule));
    }

    @Transactional(readOnly = true)
    public List<PublishScheduleSummary> list(
            AuthenticatedUser principal, PublishScheduleStatus status, Instant from, Instant to, UUID contentDraftId) {
        Workspace workspace = currentWorkspace(principal);
        return schedules.search(workspace.getId(), status == null ? null : status.name(), from, to, contentDraftId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public PublishScheduleSummary getFor(AuthenticatedUser principal, UUID scheduleId) {
        Workspace workspace = currentWorkspace(principal);
        return schedules.findByWorkspaceAndId(workspace, scheduleId)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
    }

    @Transactional(readOnly = true)
    public List<PublishScheduleSummary> calendar(AuthenticatedUser principal, Instant from, Instant to) {
        Workspace workspace = currentWorkspace(principal);
        if (from == null || to == null || !to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid from/to interval is required");
        }
        Instant maxTo = from.plus(properties.getCalendarMaxDays(), ChronoUnit.DAYS);
        Instant boundedTo = to.isAfter(maxTo) ? maxTo : to;
        return schedules.search(workspace.getId(), null, from, boundedTo, null).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public PublishScheduleSummary cancel(AuthenticatedUser principal, UUID scheduleId) {
        Workspace workspace = currentWorkspace(principal);
        PublishSchedule schedule = schedules.findByWorkspaceAndIdForUpdate(workspace, scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
        if (schedule.getStatus() != PublishScheduleStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a scheduled item can be cancelled");
        }
        schedule.cancel(Instant.now(clock));
        return toSummary(schedule);
    }

    @Transactional
    public PublishScheduleSummary reschedule(AuthenticatedUser principal, UUID scheduleId, RescheduleRequest request) {
        Workspace workspace = currentWorkspace(principal);
        PublishSchedule schedule = schedules.findByWorkspaceAndIdForUpdate(workspace, scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found"));
        if (schedule.getStatus() != PublishScheduleStatus.SCHEDULED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a scheduled item can be rescheduled");
        }
        Instant scheduledFor = validateScheduledFor(request.scheduledFor());
        schedule.reschedule(scheduledFor, Instant.now(clock));
        return toSummary(schedule);
    }

    private Instant validateScheduledFor(Instant scheduledFor) {
        if (scheduledFor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scheduledFor is required");
        }
        Instant now = Instant.now(clock);
        Instant minAllowed = now.plus(properties.getMinLead());
        Instant maxAllowed = now.plus(properties.getMaxHorizonDays(), ChronoUnit.DAYS);
        if (scheduledFor.isBefore(minAllowed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduledFor must be at least " + properties.getMinLead().getSeconds() + " seconds in the future");
        }
        if (scheduledFor.isAfter(maxAllowed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduledFor must be within " + properties.getMaxHorizonDays() + " days");
        }
        return scheduledFor;
    }

    private Workspace currentWorkspace(AuthenticatedUser principal) {
        return authService.currentMembershipFor(principal).getWorkspace();
    }

    private PublishScheduleSummary toSummary(PublishSchedule schedule) {
        Long dispatchDelayMs = schedule.getDispatchedAt() == null
                ? null
                : schedule.getDispatchedAt().toEpochMilli() - schedule.getScheduledFor().toEpochMilli();
        return new PublishScheduleSummary(
                schedule.getId(),
                schedule.getContentDraft().getId(),
                schedule.getContentDraft().getTitle(),
                schedule.getMediaAsset().getId(),
                schedule.getMediaAsset().getOriginalFilename(),
                schedule.getSocialAccount().getId(),
                schedule.getSocialAccount().getDisplayName(),
                schedule.getSocialAccount().getPlatform(),
                schedule.getCaptionSnapshot(),
                schedule.getScheduledFor(),
                schedule.getStatus(),
                schedule.getPublicationId(),
                schedule.getCreatedAt(),
                schedule.getUpdatedAt(),
                schedule.getDispatchedAt(),
                schedule.getCancelledAt(),
                schedule.getFailureCode(),
                schedule.getFailureMessage(),
                dispatchDelayMs);
    }
}
