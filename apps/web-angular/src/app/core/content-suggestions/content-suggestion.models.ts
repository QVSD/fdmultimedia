export type ContentSuggestionType = 'SOCIAL_COPY';
export type ContentSuggestionStatus = 'PENDING' | 'GENERATING' | 'READY' | 'FAILED' | 'APPLIED' | 'DISCARDED';
export type SuggestionLanguage = 'AUTO' | 'ENGLISH' | 'ROMANIAN';
export type SuggestionTone = 'NEUTRAL' | 'INFORMATIVE' | 'CASUAL' | 'ENERGETIC';

export interface ContentSuggestionSummary {
  id: string;
  contentDraftId: string;
  robotRunId: string | null;
  type: ContentSuggestionType;
  status: ContentSuggestionStatus;
  provider: string;
  model: string;
  promptVersion: string;
  language: SuggestionLanguage;
  tone: SuggestionTone;
  personaId: string | null;
  personaName: string | null;
  hook: string | null;
  caption: string | null;
  hashtags: string[];
  shortTitle: string | null;
  transcriptUsed: boolean;
  transcriptId: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  latencyMs: number | null;
  failureCode: string | null;
  failureMessage: string | null;
  stale: boolean;
  createdAt: string;
  completedAt: string | null;
  appliedAt: string | null;
  appliedByUserId: string | null;
}

export interface CreateContentSuggestionRequest {
  language: SuggestionLanguage | null;
  tone: SuggestionTone | null;
  personaId: string | null;
}
