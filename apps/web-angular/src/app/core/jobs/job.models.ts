export type JobType = 'SYSTEM_TEST' | 'IMPORT_MEDIA' | 'INSPECT_MEDIA' | 'CREATE_CLIP' | 'CREATE_SOCIAL_VERTICAL';
export type JobStatus = 'QUEUED' | 'ASSIGNED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';

export interface JobSummary {
  id: string;
  type: JobType;
  status: JobStatus;
  payload: Record<string, unknown>;
  result: Record<string, unknown> | null;
  errorCode: string | null;
  errorMessage: string | null;
  assignedWorkerId: string | null;
  assignedWorkerName: string | null;
  attemptCount: number;
  maxAttempts: number;
  queuedAt: string;
  assignedAt: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  leaseExpiresAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateSystemTestJobRequest {
  type: 'SYSTEM_TEST';
  payload: {
    message: string;
    durationMs: number;
  };
}
