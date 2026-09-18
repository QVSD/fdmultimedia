export interface SchedulingOverview {
  window: string;
  queue: QueueMetrics;
  execution: JobTypeMetrics[];
  scheduling: SchedulingMetrics;
}

export interface QueueMetrics {
  queued: number;
  assigned: number;
  running: number;
  succeeded: number;
  failed: number;
  oldestQueuedAgeMs: number | null;
}

export interface JobTypeMetrics {
  jobType: string;
  attempts: number;
  successes: number;
  failures: number;
  averageQueueWaitMs: number | null;
  averageExecutionMs: number | null;
  averageTotalLatencyMs: number | null;
}

export interface SchedulingMetrics {
  claims: number;
  fallbackClaims: number;
  starvationOverrideClaims: number;
}

export interface WorkerPerformanceSummary extends JobTypeMetrics {
  workerId: string;
  workerName: string;
  mostRecentExecutionAt: string;
}

export interface SchedulingDecisionSummary {
  timestamp: string;
  jobId: string;
  jobType: string;
  workerId: string;
  workerName: string;
  policy: string;
  suitabilityScore: number | null;
  telemetryFresh: boolean;
  fallbackUsed: boolean;
  starvationOverride: boolean;
  reasonCodes: string[];
  activeJobs: number | null;
  maxActiveJobs: number;
  attempt: number;
}
