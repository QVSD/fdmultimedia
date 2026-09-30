package com.fdmultimedia.worker;

import java.util.List;
import java.util.UUID;

record CoordinatedCopyAuthorization(
        UUID copySetId,
        UUID robotRunId,
        String provider,
        String model,
        String promptVersion,
        String prompt,
        List<UUID> expectedOutputIds,
        String language,
        String tone,
        int maxSeriesTitleLength,
        int maxHookLength,
        int maxCaptionLength,
        int maxHashtags,
        int maxHashtagLength,
        int maxShortTitleLength,
        int maxContinuityNoteLength) {
}
