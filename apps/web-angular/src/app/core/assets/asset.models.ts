export type MediaAssetStatus = 'PENDING' | 'IMPORTING' | 'PROCESSING' | 'READY' | 'FAILED';
export type MediaInspectionStatus = 'NOT_REQUESTED' | 'PENDING' | 'INSPECTING' | 'INSPECTED' | 'FAILED';
export type MediaDerivationType = 'ORIGINAL' | 'CLIP' | 'SOCIAL_VERTICAL';
export type HighlightAnalysisStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type TranscriptStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface MediaAssetSummary {
  id: string;
  sourceType: 'DIRECT_URL' | 'DERIVED';
  sourceUrl: string;
  parentAssetId: string | null;
  derivationType: MediaDerivationType;
  status: MediaAssetStatus;
  originalFilename: string | null;
  contentType: string | null;
  fileSizeBytes: number | null;
  checksumSha256: string | null;
  durationMs: number | null;
  width: number | null;
  height: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  containerFormat: string | null;
  importJobId: string | null;
  processingJobId: string | null;
  inspectionStatus: MediaInspectionStatus;
  inspectionJobId: string | null;
  inspectionErrorCode: string | null;
  inspectionErrorMessage: string | null;
  frameRate: number | null;
  bitrate: number | null;
  hasVideo: boolean | null;
  hasAudio: boolean | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  readyAt: string | null;
}

export interface MediaImportResponse {
  asset: MediaAssetSummary;
}

export interface CreateClipResponse {
  asset: MediaAssetSummary;
}

export interface HighlightCandidateSummary {
  id: string;
  analysisId: string;
  assetId: string;
  startMs: number;
  endMs: number;
  durationMs: number;
  score: number;
  reason: string;
  rank: number;
  createdAt: string;
  /** SEMANTIC_HIGHLIGHTS_V2 decomposable evidence — null for pre-V2 candidates. */
  hookScore: number | null;
  completenessScore: number | null;
  informationDensityScore: number | null;
  speechDensityScore: number | null;
  boundaryScore: number | null;
  coverageScore: number | null;
  sceneScore: number | null;
  audioBoundaryScore: number | null;
  repetitionPenalty: number | null;
  explanationLabels: string[] | null;
  transcriptExcerpt: string | null;
  baseScore?: number | null;
  lexicalScore?: number | null;
  emphasisScore?: number | null;
  selfContainedScore?: number | null;
  semanticScore?: number | null;
  wordCount?: number | null;
  firstTranscriptSegmentId?: string | null;
  lastTranscriptSegmentId?: string | null;
  boundaryStartAdjustmentMs?: number | null;
  boundaryEndAdjustmentMs?: number | null;
}

export interface HighlightAnalysisSummary {
  id: string;
  assetId: string;
  status: HighlightAnalysisStatus;
  analysisJobId: string;
  analyzerType: string;
  analyzerVersion: string;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  configFingerprint: string | null;
  configSnapshot: Record<string, unknown> | null;
  transcriptCoverage: number | null;
  requestedAnalyzerType?: string | null;
  effectiveAnalyzerType?: string | null;
  fallbackReason?: string | null;
  transcriptId?: string | null;
  candidates: HighlightCandidateSummary[];
}

export type HighlightSelectionStatus = 'COMPLETE' | 'PARTIAL' | 'EMPTY';
export type HighlightSelectionExclusionReason = 'TEMPORAL_OVERLAP' | 'INSUFFICIENT_TEMPORAL_GAP' | 'LEXICAL_DUPLICATE' | 'BELOW_QUALITY_FLOOR' | 'SELECTION_LIMIT_REACHED';

export interface HighlightSelectionItemSummary {
  id: string;
  candidateId: string;
  selectionOrder: number;
  sourceRank: number;
  startMs: number;
  endMs: number;
  score: number;
  transcriptExcerpt?: string | null;
  clipAssetId?: string | null;
  clipAssetStatus?: MediaAssetStatus | null;
  clipJobId?: string | null;
  clipJobStatus?: string | null;
  clipFailureCode?: string | null;
  clipFailureMessage?: string | null;
}

export interface HighlightSelectionExclusionSummary {
  candidateId: string;
  sourceRank: number;
  reason: HighlightSelectionExclusionReason;
  conflictingCandidateId?: string | null;
  conflictingSourceRank?: number | null;
  temporalOverlapRatio?: number | null;
  lexicalSimilarity?: number | null;
}

export interface HighlightSelectionSummary {
  id: string;
  mediaAssetId: string;
  highlightAnalysisId: string;
  selectorVersion: string;
  requestedCount: number;
  selectedCount: number;
  status: HighlightSelectionStatus;
  createdAt: string;
  items: HighlightSelectionItemSummary[];
  exclusions: HighlightSelectionExclusionSummary[];
}

export interface TranscriptSegmentSummary {
  id: string;
  sequence: number;
  startMs: number;
  endMs: number;
  text: string;
  confidence: number | null;
}

export interface MediaTranscriptSummary {
  id: string;
  assetId: string;
  status: TranscriptStatus;
  transcriptionJobId: string;
  provider: string;
  model: string;
  detectedLanguage: string | null;
  durationMs: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
  segments: TranscriptSegmentSummary[];
}
