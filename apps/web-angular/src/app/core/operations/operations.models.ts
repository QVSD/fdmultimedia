export type OverallStatus = 'HEALTHY' | 'DEGRADED' | 'ACTION_REQUIRED';
export type ComponentStatus = 'HEALTHY' | 'DEGRADED' | 'UNAVAILABLE' | 'UNKNOWN';
export type OpsSeverity = 'INFO' | 'WARNING' | 'CRITICAL';
export type OpsIncidentStatus = 'ACTIVE' | 'RESOLVED';
export type WorkerState = 'ONLINE' | 'STALE' | 'OFFLINE';
export type SchedulerState = 'IDLE' | 'RUNNING' | 'DEGRADED' | 'FAILED' | 'UNKNOWN';

export interface OpsDependency {
  name: string;
  status: ComponentStatus;
  detail: string;
  configured: boolean;
  observedAt: string | null;
  ageSeconds: number;
  cached: boolean;
  probeMillis: number | null;
}

export interface OpsApiSection {
  status: ComponentStatus;
  instance: string;
  uptimeSeconds: number;
  liveness: string;
  readiness: string;
  version: string;
  commit: string;
  builtAt: string | null;
}

export interface OpsWorkersSection {
  status: ComponentStatus;
  total: number;
  online: number;
  stale: number;
  offline: number;
  recentlySeenOffline: number;
  staleAfterSeconds: number;
  offlineAfterSeconds: number;
}

export interface OpsJobsSection {
  status: ComponentStatus;
  queued: number;
  assigned: number;
  running: number;
  leaseExpired: number;
  failedRecent: number;
  succeededRecent: number;
  oldestQueuedAgeSeconds: number | null;
  backlogSeverity: OpsSeverity | null;
  backlogWarningSeconds: number;
  backlogCriticalSeconds: number;
  recentWindowHours: number;
}

export interface OpsSchedulersSection {
  status: ComponentStatus;
  total: number;
  idle: number;
  running: number;
  degraded: number;
  failed: number;
  unknown: number;
}

export interface OpsPublishingSection {
  status: ComponentStatus;
  scheduledDue: number;
  scheduledOverdue: number;
  publishing: number;
  publishedRecent: number;
  failedRecent: number;
  outcomeUnknown: number;
  oldestOverdueAgeSeconds: number | null;
  overdueWarningSeconds: number;
  overdueCriticalSeconds: number;
  recentWindowHours: number;
}

export interface OpsAutomationSection {
  status: ComponentStatus;
  autoProposeRobots: number;
  pendingProposals: number;
  activeAuthorizations: number;
  guardrailBlocked: number;
  activeSafetyObservations: number;
  openRollbackRecommendations: number;
  memorySuppressedTransitions: number;
}

export interface OpsIncidentsSection {
  status: ComponentStatus;
  active: number;
  critical: number;
  warning: number;
  info: number;
  unacknowledged: number;
}

export interface OpsIncident {
  id: string;
  key: string;
  severity: OpsSeverity;
  status: OpsIncidentStatus;
  category: string;
  title: string;
  conditionCode: string;
  detail: string | null;
  suggestedAction: string;
  subjectType: string | null;
  subjectId: string | null;
  firstObservedAt: string;
  lastObservedAt: string;
  resolvedAt: string | null;
  acknowledgedAt: string | null;
  acknowledged: boolean;
  persisted: boolean;
}

export interface OpsOverview {
  engineVersion: string;
  observedAt: string;
  overallStatus: OverallStatus;
  api: OpsApiSection;
  dependencies: OpsDependency[];
  workers: OpsWorkersSection;
  jobs: OpsJobsSection;
  schedulers: OpsSchedulersSection;
  publishing: OpsPublishingSection;
  automation: OpsAutomationSection;
  incidents: OpsIncidentsSection;
  topIncidents: OpsIncident[];
  refreshHintSeconds: number;
}

export interface OpsPage<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface OpsWorkerRow {
  id: string;
  name: string;
  state: WorkerState;
  lastHeartbeatAt: string | null;
  activeJobs: number;
  maxActiveJobs: number;
  capabilities: string[];
  agentVersion: string | null;
  currentJobId: string | null;
  lastCompletedAt: string | null;
  lastFailedAt: string | null;
}

export interface OpsJobRow {
  id: string;
  type: string;
  status: string;
  queuedAt: string;
  finishedAt: string | null;
  attemptCount: number;
  maxAttempts: number;
  retryState: 'NONE' | 'RETRY_PENDING' | 'EXHAUSTED';
  failureCategory: string | null;
  failureMessage: string | null;
  leaseExpiresAt: string | null;
}

export interface OpsAttemptRow {
  jobAttempt: number;
  outcome: string;
  errorCategory: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface OpsJobDetail {
  job: OpsJobRow;
  workerName: string | null;
  assignedAt: string | null;
  startedAt: string | null;
  publishingAttempts: OpsAttemptRow[];
}

export interface OpsSchedulerRow {
  name: string;
  enabled: boolean;
  state: SchedulerState;
  stale: boolean;
  cadenceSeconds: number;
  staleAfterSeconds: number;
  lastStartedAt: string | null;
  lastCompletedAt: string | null;
  lastSucceededAt: string | null;
  lastFailedAt: string | null;
  lastDurationMs: number | null;
  processedCount: number;
  resultCount: number;
  lastFailureCode: string | null;
  reportingInstances: number;
  batchBound: number;
  classification: string;
}

export interface OpsProviderRow {
  provider: string;
  status: ComponentStatus;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  failuresSinceLastSuccess: number;
}

export interface OpsPublicationRow {
  id: string;
  provider: string;
  status: string;
  createdAt: string;
  finishedAt: string | null;
  attempts: number;
  failureCategory: string | null;
  outcomeUnknown: boolean;
  retryGuidance: string;
}

export interface OpsWorkersView {
  summary: OpsWorkersSection;
  workers: OpsPage<OpsWorkerRow>;
}

export interface OpsJobsView {
  summary: OpsJobsSection;
  jobs: OpsPage<OpsJobRow>;
}

export interface OpsSchedulersView {
  summary: OpsSchedulersSection;
  schedulers: OpsSchedulerRow[];
}

export interface OpsPublishingView {
  summary: OpsPublishingSection;
  providers: OpsProviderRow[];
  attention: OpsPage<OpsPublicationRow>;
}

export interface OpsIncidentsView {
  summary: OpsIncidentsSection;
  incidents: OpsPage<OpsIncident>;
}
