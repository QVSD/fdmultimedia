package com.fdmultimedia.api.publishing.tiktok;
import jakarta.validation.constraints.NotBlank;
public record TikTokSettingsRequest(@NotBlank String privacyLevel,boolean disableComment,boolean disableDuet,boolean disableStitch){}
