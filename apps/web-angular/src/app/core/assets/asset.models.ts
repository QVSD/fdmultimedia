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
  candidates: HighlightCandidateSummary[];
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
