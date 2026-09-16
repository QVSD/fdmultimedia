export type WorkerStatus = 'ONLINE' | 'OFFLINE';

export interface WorkerSummary {
  id: string;
  name: string;
  status: WorkerStatus;
  machineIdentifier: string;
  operatingSystem: string;
  architecture: string;
  cpuModel: string;
  cpuLogicalCores: number;
  totalMemoryBytes: number;
  gpuModel: string | null;
  gpuMemoryBytes: number | null;
  agentVersion: string;
  maxActiveJobs: number;
  schedulingPolicy: string;
  schedulingState: string;
  supportedJobTypes: string[] | null;
  supportedHighlightAnalyzers: string[] | null;
  telemetry: WorkerTelemetry | null;
  lastSeenAt: string;
  registeredAt: string;
}

export interface WorkerTelemetry {
  systemCpuLoad: number | null;
  processCpuLoad: number | null;
  availableMemoryBytes: number | null;
  jvmHeapUsedBytes: number | null;
  jvmHeapMaxBytes: number | null;
  activeJobs: number | null;
  lastTelemetryAt: string | null;
  fresh: boolean;
}
