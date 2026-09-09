export interface UserSummary {
  id: string;
  email: string;
  displayName: string;
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  slug: string;
  role: 'OWNER' | 'ADMIN' | 'MEMBER';
}

export interface AuthSession {
  user: UserSummary;
  currentWorkspace: WorkspaceSummary;
}

export interface LoginRequest {
  email: string;
  password: string;
}
