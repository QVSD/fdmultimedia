package com.fdmultimedia.api.publishing.instagram;

/** One bounded step of the Instagram publish orchestration, reported back to the Worker's drive loop. */
public record InstagramDriveOutcome(
        Status status,
        String providerRequestId,
        String providerPublicationId,
        String errorCode,
        String errorMessage,
        boolean terminal) {

    public enum Status { IN_PROGRESS, PUBLISHED, FAILED }

    public static InstagramDriveOutcome inProgress() {
        return new InstagramDriveOutcome(Status.IN_PROGRESS, null, null, null, null, false);
    }

    public static InstagramDriveOutcome published(String containerId, String mediaId) {
        return new InstagramDriveOutcome(Status.PUBLISHED, containerId, mediaId, null, null, false);
    }

    public static InstagramDriveOutcome failure(String errorCode, String errorMessage, boolean terminal) {
        return new InstagramDriveOutcome(Status.FAILED, null, null, errorCode, errorMessage, terminal);
    }
}
