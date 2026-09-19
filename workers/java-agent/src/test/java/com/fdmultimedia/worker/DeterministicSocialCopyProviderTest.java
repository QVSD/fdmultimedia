package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicSocialCopyProviderTest {

    private final DeterministicSocialCopyProvider provider = new DeterministicSocialCopyProvider();

    @Test
    void producesStableOutputForTheSameAuthorization() throws Exception {
        SocialCopyAuthorization authorization = authorization("Source file: clip.mp4\n");

        SocialCopyResult first = provider.generate(authorization);
        SocialCopyResult second = provider.generate(authorization);

        assertEquals(first.hook(), second.hook());
        assertEquals(first.caption(), second.caption());
        assertEquals(first.hashtags(), second.hashtags());
        assertEquals(first.shortTitle(), second.shortTitle());
    }

    @Test
    void neverExceedsBoundsFromAuthorization() throws Exception {
        SocialCopyAuthorization authorization = new SocialCopyAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), "DETERMINISTIC_TEST", "deterministic-v1", "SOCIAL_COPY_V1",
                "Source file: clip.mp4\n", "ENGLISH", "ENERGETIC", 10, 20, 2, 5, 8);

        SocialCopyResult result = provider.generate(authorization);

        assertTrue(result.hook().length() <= 10);
        assertTrue(result.caption().length() <= 20);
        assertTrue(result.hashtags().size() <= 2);
        result.hashtags().forEach(tag -> assertTrue(tag.length() <= 5));
        assertTrue(result.shortTitle().length() <= 8);
    }

    @Test
    void neverPretendsToBeRealAi() throws Exception {
        SocialCopyResult result = provider.generate(authorization("No source line here.\n"));

        assertFalse(result.hook().toLowerCase().contains("openai"));
        assertFalse(result.hook().toLowerCase().contains("gpt"));
    }

    @Test
    void weavesPersonaNameIntoHookAndCaptionWhenPromptCarriesAPersonaSection() throws Exception {
        SocialCopyResult result = provider.generate(authorization(
                "Source file: clip.mp4\n<<<EDITORIAL_PERSONA_START>>>\nPersona name: Tech Romania\nVoice: Direct.\n<<<EDITORIAL_PERSONA_END>>>\n"));

        assertTrue(result.hook().contains("Tech Romania"));
        assertTrue(result.caption().contains("Tech Romania"));
    }

    @Test
    void omitsPersonaFlavorWhenPromptCarriesNoPersonaSection() throws Exception {
        SocialCopyResult result = provider.generate(authorization("Source file: clip.mp4\n"));

        assertFalse(result.hook().contains("voice)"));
        assertFalse(result.caption().contains("styled as"));
    }

    @Test
    void personaFlavoredOutputRemainsStableForTheSameAuthorization() throws Exception {
        SocialCopyAuthorization authorization = authorization(
                "Source file: clip.mp4\n<<<EDITORIAL_PERSONA_START>>>\nPersona name: Tech Romania\nVoice: Direct.\n<<<EDITORIAL_PERSONA_END>>>\n");

        SocialCopyResult first = provider.generate(authorization);
        SocialCopyResult second = provider.generate(authorization);

        assertEquals(first.hook(), second.hook());
        assertEquals(first.caption(), second.caption());
    }

    private SocialCopyAuthorization authorization(String prompt) {
        return new SocialCopyAuthorization(
                UUID.randomUUID(), UUID.randomUUID(), "DETERMINISTIC_TEST", "deterministic-v1", "SOCIAL_COPY_V1",
                prompt, "ENGLISH", "CASUAL", 200, 2200, 30, 50, 100);
    }
}
