export type MediaAssetStatus = 'PENDING' | 'IMPORTING' | 'READY' | 'FAILED';

export interface MediaAssetSummary {
  id: string;
  sourceType: 'DIRECT_URL';
  sourceUrl: string;
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
  errorCode: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  readyAt: string | null;
}

export interface MediaImportResponse {
  asset: MediaAssetSummary;
}
