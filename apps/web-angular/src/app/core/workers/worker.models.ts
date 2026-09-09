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
  lastSeenAt: string;
  registeredAt: string;
}
