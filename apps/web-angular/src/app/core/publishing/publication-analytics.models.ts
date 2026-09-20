export interface PublicationAnalyticsSnapshot {
  id: string;
  publicationId: string;
  provider: string;
  bucketKey: string;
  collectedAt: string;
  providerObservedAt: string | null;
  publicationAgeSeconds: number;
  views: number | null;
  reach: number | null;
  likes: number | null;
  comments: number | null;
  shares: number | null;
  saves: number | null;
  totalInteractions: number | null;
  watchTimeMs: number | null;
  averageWatchTimeMs: number | null;
  providerMetricVersion: string;
}

export interface PublicationAnalyticsState {
  nextCollectionAt: string | null;
  lastAttemptAt: string | null;
  lastSuccessAt: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  completedAt: string | null;
}

export interface PublicationAttribution {
  publicationId: string;
  contentDraftId: string | null;
  publishScheduleId: string | null;
  robotRunId: string | null;
  robotId: string | null;
  robotNameSnapshot: string | null;
  contentSourceId: string | null;
  sourceMediaAssetId: string | null;
  finalMediaAssetId: string;
  appliedContentSuggestionId: string | null;
  suggestionOrigin: string | null;
  personaId: string | null;
  personaNameSnapshot: string | null;
  aiProvider: string | null;
  aiModel: string | null;
  promptVersion: string | null;
  aiPolicy: string | null;
  robotAutonomyMode: string | null;
  sourceSelectionPolicy: string | null;
  createdAt: string;
}
