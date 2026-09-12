export type MediaAssetStatus = 'PENDING' | 'IMPORTING' | 'PROCESSING' | 'READY' | 'FAILED';
export type MediaInspectionStatus = 'NOT_REQUESTED' | 'PENDING' | 'INSPECTING' | 'INSPECTED' | 'FAILED';
export type MediaDerivationType = 'ORIGINAL' | 'CLIP' | 'SOCIAL_VERTICAL';

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
