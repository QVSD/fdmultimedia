package com.fdmultimedia.api.publishschedules;

/**
 * SCHEDULED: waiting for its due time; no Publication/Job exists yet.
 * DISPATCHED: the dispatcher atomically created a Publication (and its
 * PUBLISH_MEDIA Job) for this schedule. Terminal — the schedule's own job is
 * done; whatever happens to the Publication afterward is tracked on the
 * Publication, not here.
 * CANCELLED: the user cancelled before due time. Terminal.
 * FAILED: dispatch-time validation rejected the schedule (account/media no
 * longer eligible) before any Publication was created. Terminal — no
 * automatic retry; the user creates a new schedule after fixing the cause.
 *
 * There is no DISPATCHING state: claiming the due row, creating the
 * Publication, and marking DISPATCHED all happen in one transaction (see
 * PublishScheduleDispatcher), so no intermediate state is ever externally
 * observable.
 */
public enum PublishScheduleStatus {
    SCHEDULED,
    DISPATCHED,
    CANCELLED,
    FAILED
}
