export type ContentSourceType = 'MEDIA_LIBRARY';
export type ContentSourceStatus = 'ACTIVE' | 'PAUSED';

export interface ContentSourceSummary {
  id: string;
  name: string;
  description: string | null;
  type: ContentSourceType;
  status: ContentSourceStatus;
  assetCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ContentSourceAssetSummary {
  mediaAssetId: string;
  originalFilename: string | null;
  status: 'PENDING' | 'IMPORTING' | 'PROCESSING' | 'READY' | 'FAILED';
  inspectionStatus: 'NOT_REQUESTED' | 'PENDING' | 'INSPECTING' | 'INSPECTED' | 'FAILED';
  derivationType: 'ORIGINAL' | 'CLIP' | 'SOCIAL_VERTICAL';
  durationMs: number | null;
  hasVideo: boolean | null;
  addedAt: string;
}

export interface CreateContentSourceRequest {
  name: string;
  description: string | null;
}

export type UpdateContentSourceRequest = CreateContentSourceRequest;
