package com.fdmultimedia.api.contentsuggestions;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Backend-side configuration only — non-secret labels and validation
 * bounds. The Worker owns the actual provider endpoint/model/timeout/API key
 * in its own environment (mirroring the Phase 7B2 semantic-highlight split);
 * the backend never needs, stores, or forwards a provider credential.
 */
@ConfigurationProperties(prefix = "app.content-ai")
public class ContentAiProperties {

    /** Master switch. False: generation requests fail fast with AI_DISABLED; the app still starts normally. */
    private boolean enabled = true;
    private String provider = "DETERMINISTIC_TEST";
    private String model = "deterministic-v1";
    private int maxHookLength = 200;
    private int maxCaptionLength = 2200;
    private int maxHashtags = 30;
    private int maxHashtagLength = 50;
    private int maxShortTitleLength = 100;
    private int maxTranscriptContextCharacters = 4000;
    private int maxTranscriptContextSegments = 80;
    private long transcriptContextPaddingMs = 5000;
    private int maxPromptCharacters = 20000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getMaxHookLength() { return maxHookLength; }
    public void setMaxHookLength(int maxHookLength) { this.maxHookLength = maxHookLength; }
    public int getMaxCaptionLength() { return maxCaptionLength; }
    public void setMaxCaptionLength(int maxCaptionLength) { this.maxCaptionLength = maxCaptionLength; }
    public int getMaxHashtags() { return maxHashtags; }
    public void setMaxHashtags(int maxHashtags) { this.maxHashtags = maxHashtags; }
    public int getMaxHashtagLength() { return maxHashtagLength; }
    public void setMaxHashtagLength(int maxHashtagLength) { this.maxHashtagLength = maxHashtagLength; }
    public int getMaxShortTitleLength() { return maxShortTitleLength; }
    public void setMaxShortTitleLength(int maxShortTitleLength) { this.maxShortTitleLength = maxShortTitleLength; }
    public int getMaxTranscriptContextCharacters() { return maxTranscriptContextCharacters; }
    public void setMaxTranscriptContextCharacters(int maxTranscriptContextCharacters) { this.maxTranscriptContextCharacters = maxTranscriptContextCharacters; }
    public int getMaxTranscriptContextSegments() { return maxTranscriptContextSegments; }
    public void setMaxTranscriptContextSegments(int maxTranscriptContextSegments) { this.maxTranscriptContextSegments = maxTranscriptContextSegments; }
    public long getTranscriptContextPaddingMs() { return transcriptContextPaddingMs; }
    public void setTranscriptContextPaddingMs(long transcriptContextPaddingMs) { this.transcriptContextPaddingMs = transcriptContextPaddingMs; }
    public int getMaxPromptCharacters() { return maxPromptCharacters; }
    public void setMaxPromptCharacters(int maxPromptCharacters) { this.maxPromptCharacters = maxPromptCharacters; }
}
