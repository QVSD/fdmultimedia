package com.fdmultimedia.api.publishschedules;

import com.fdmultimedia.api.accounts.SocialAccount;
import com.fdmultimedia.api.accounts.SocialAccountStatus;
import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.assets.MediaAssetStatus;
import com.fdmultimedia.api.assets.MediaInspectionStatus;
import com.fdmultimedia.api.publishing.PublicationSummary;
import com.fdmultimedia.api.publishing.PublishingService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The atomic, per-schedule half of dispatch — deliberately a separate bean
 * from {@link PublishScheduleDispatcher}. Spring's {@code @Transactional}
 * works by intercepting calls through the bean's proxy; a method calling
 * {@code this.otherMethod()} within the same class bypasses that proxy
 * entirely (the classic self-invocation pitfall), which would silently run
 * {@link #dispatchOne()} with no transaction — and therefore no Hibernate
 * session to lazily resolve the claimed row's associations. Routing the call
 * through a distinct bean means it always goes through the proxy.
 */
@Service
public class PublishScheduleDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PublishScheduleDispatchService.class);

    private final PublishScheduleRepository schedules;
    private final PublishingService publishingService;
    private final Clock clock;

    public PublishScheduleDispatchService(
            PublishScheduleRepository schedules, PublishingService publishingService, Clock clock) {
        this.schedules = schedules;
        this.publishingService = publishingService;
        this.clock = clock;
    }

    /**
     * Claims and fully dispatches exactly one due schedule, if one exists,
     * in one transaction: claim (row-locked) -> revalidate -> create
     * Publication + PUBLISH_MEDIA -> link -> mark DISPATCHED, or mark FAILED
     * before any Publication is created. Returns {@code false} when nothing
     * was due.
     */
    @Transactional
    public boolean dispatchOne() {
        Instant now = Instant.now(clock);
        Optional<PublishSchedule> due = schedules.findNextDueForUpdate(now);
        if (due.isEmpty()) {
            return false;
        }
        dispatch(due.get(), now);
        return true;
    }

    private void dispatch(PublishSchedule schedule, Instant now) {
        SocialAccount account = schedule.getSocialAccount();
        if (account.getStatus() != SocialAccountStatus.ACTIVE) {
            fail(schedule, "SOCIAL_ACCOUNT_UNAVAILABLE", "Social account is not active", now);
            return;
        }
        MediaAsset media = schedule.getMediaAsset();
        if (media.getStatus() != MediaAssetStatus.READY
                || media.getInspectionStatus() != MediaInspectionStatus.INSPECTED
                || !Boolean.TRUE.equals(media.getHasVideo())) {
            fail(schedule, "MEDIA_UNAVAILABLE", "Scheduled media is no longer ready", now);
            return;
        }
        PublicationSummary publication;
        try {
            publication = schedule.getTikTokSettings() == null
                    ? publishingService.createPublicationForSchedule(
                            schedule.getWorkspace(), media, account, schedule.getCaptionSnapshot(),
                            schedule.getContentDraft().getId(), schedule.getCreatedByUser(),
                            schedule.getId(), schedule.getAppliedContentSuggestionIdSnapshot())
                    : publishingService.createPublicationForSchedule(
                            schedule.getWorkspace(), media, account, schedule.getCaptionSnapshot(),
                            schedule.getContentDraft().getId(), schedule.getCreatedByUser(),
                            schedule.getId(), schedule.getAppliedContentSuggestionIdSnapshot(), schedule.getTikTokSettings());
        } catch (ResponseStatusException ex) {
            fail(schedule, "PROVIDER_UNAVAILABLE", ex.getReason(), now);
            return;
        } catch (RuntimeException ex) {
            log.error("Unexpected error dispatching schedule {}", schedule.getId(), ex);
            fail(schedule, "DISPATCH_ERROR", "Unexpected error while dispatching", now);
            return;
        }
        schedule.markDispatched(publication.id(), now);
        log.info("Dispatched publish schedule {} -> publication {} (scheduledFor={}, dispatchedAt={})",
                schedule.getId(), publication.id(), schedule.getScheduledFor(), now);
    }

    private void fail(PublishSchedule schedule, String code, String message, Instant now) {
        schedule.markFailed(code, message, now);
        log.warn("Publish schedule {} failed dispatch: {}", schedule.getId(), code);
    }
}
