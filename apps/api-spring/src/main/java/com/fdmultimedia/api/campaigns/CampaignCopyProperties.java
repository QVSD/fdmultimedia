package com.fdmultimedia.api.campaigns;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Coordinated-copy-specific bounds only. hook/caption/hashtag/shortTitle
 * bounds are reused verbatim from {@code ContentAiProperties} (item 18) —
 * this is the same downstream artifact ({@code ContentSuggestion}), not a
 * parallel bound. Provider/model/the AI kill switch are also reused verbatim
 * from {@code ContentAiProperties} (item 22), same as campaign planning.
 */
@ConfigurationProperties(prefix = "app.campaign-copy")
public class CampaignCopyProperties {

    public static final String PROMPT_VERSION = "COORDINATED_COPY_V1";

    private int maxSeriesTitleLength = 120;
    private int maxSharedFramingLength = 500;
    private int maxContinuityNoteLength = 200;
    private int maxEvidenceExcerptCharacters = 600;
    private int maxSiblingSummaryCharacters = 150;
    private int maxPromptCharacters = 20000;
    private BigDecimal nearDuplicateHookThreshold = new BigDecimal("0.75");
    private BigDecimal duplicateCaptionOpeningThreshold = new BigDecimal("0.75");
    private int captionOpeningWindowCharacters = 60;

    public int getMaxSeriesTitleLength() { return maxSeriesTitleLength; }
    public void setMaxSeriesTitleLength(int v) { this.maxSeriesTitleLength = v; }
    public int getMaxSharedFramingLength() { return maxSharedFramingLength; }
    public void setMaxSharedFramingLength(int v) { this.maxSharedFramingLength = v; }
    public int getMaxContinuityNoteLength() { return maxContinuityNoteLength; }
    public void setMaxContinuityNoteLength(int v) { this.maxContinuityNoteLength = v; }
    public int getMaxEvidenceExcerptCharacters() { return maxEvidenceExcerptCharacters; }
    public void setMaxEvidenceExcerptCharacters(int v) { this.maxEvidenceExcerptCharacters = v; }
    public int getMaxSiblingSummaryCharacters() { return maxSiblingSummaryCharacters; }
    public void setMaxSiblingSummaryCharacters(int v) { this.maxSiblingSummaryCharacters = v; }
    public int getMaxPromptCharacters() { return maxPromptCharacters; }
    public void setMaxPromptCharacters(int v) { this.maxPromptCharacters = v; }
    public BigDecimal getNearDuplicateHookThreshold() { return nearDuplicateHookThreshold; }
    public void setNearDuplicateHookThreshold(BigDecimal v) { this.nearDuplicateHookThreshold = v; }
    public BigDecimal getDuplicateCaptionOpeningThreshold() { return duplicateCaptionOpeningThreshold; }
    public void setDuplicateCaptionOpeningThreshold(BigDecimal v) { this.duplicateCaptionOpeningThreshold = v; }
    public int getCaptionOpeningWindowCharacters() { return captionOpeningWindowCharacters; }
    public void setCaptionOpeningWindowCharacters(int v) { this.captionOpeningWindowCharacters = v; }
}
