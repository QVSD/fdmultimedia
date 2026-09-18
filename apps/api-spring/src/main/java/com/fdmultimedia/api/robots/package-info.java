/**
 * Robots & automation foundation (Phase 11C).
 *
 * A {@code Robot} is a persistent logical automation owned by a workspace —
 * "what should happen" — never a Worker, a Job, or a background Java
 * thread. It orchestrates the existing highlight/ContentDraft/PublishSchedule/
 * Publication/Job pipeline through durable {@code RobotRun}s; it never
 * duplicates any of them, and it never touches FFmpeg or a Job payload
 * directly. See docs/ARCHITECTURE.md for the full design, including why
 * this package is deliberately separate from the Worker/Job scheduling code
 * in {@code com.fdmultimedia.api.jobs} and the content-publication
 * scheduling in {@code com.fdmultimedia.api.publishschedules} — three
 * distinct "scheduling" layers that are never merged.
 */
package com.fdmultimedia.api.robots;
