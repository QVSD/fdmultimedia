package com.fdmultimedia.worker;

import java.util.UUID;

record SocialCopyAuthorization(
        UUID suggestionId,
        UUID contentDraftId,
        String provider,
        String model,
        String promptVersion,
        String prompt,
        String language,
        String tone,
        int maxHookLength,
        int maxCaptionLength,
        int maxHashtags,
        int maxHashtagLength,
        int maxShortTitleLength) {
}
