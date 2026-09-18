import { PublicationSummary } from '../publishing/publishing.models';

export type ContentDraftStatus = 'DRAFT' | 'READY' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED';
export type ContentDraftWorkflowStage = 'CLIP_PENDING' | 'VERTICAL_PENDING' | 'READY';

export interface ContentDraftSummary {
  id: string;
  sourceAssetId: string;
  mediaAssetId: string;
  mediaAssetFilename: string | null;
  sourceHighlightCandidateId: string | null;
  title: string | null;
  caption: string | null;
  status: ContentDraftStatus;
  workflowStage: ContentDraftWorkflowStage;
  pendingJobId: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
  updatedAt: string;
  publishedAt: string | null;
  publications: PublicationSummary[];
  robotRunId: string | null;
}
