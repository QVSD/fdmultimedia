/**
 * User-controlled future publication scheduling (Phase 11B).
 *
 * {@code PublishSchedule} is deliberately distinct from the Worker/Job
 * scheduling system in {@code com.fdmultimedia.api.jobs}
 * ({@code SchedulingDecision}, telemetry-aware placement, etc.) — that
 * subsystem decides which compatible Worker executes an already-queued Job;
 * this package decides *when a Publication is even created*. A future
 * schedule holds no Worker, no Job lease, and no {@code PUBLISH_MEDIA} Job
 * until its due time: {@link PublishScheduleDispatcher} is the one thing
 * that turns a due {@code PublishSchedule} into a normal Publication through
 * the existing {@code PublishingService}, which is the only thing a Worker
 * ever sees.
 */
package com.fdmultimedia.api.publishschedules;
