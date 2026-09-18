package com.fdmultimedia.api.publishing;

/**
 * Response for the Instagram "drive one step" worker-agent endpoint. Kept
 * minimal on purpose: the Worker's only job is to decide whether to keep
 * polling ({@code IN_PROGRESS}) or stop ({@code PUBLISHED}/{@code FAILED}) —
 * the backend has already finalized the Job and Publication internally by
 * the time either terminal status is returned, so the Worker never needs to
 * report anything further back for an Instagram publication.
 */
public record PublicationDriveResponse(String status) {
}
