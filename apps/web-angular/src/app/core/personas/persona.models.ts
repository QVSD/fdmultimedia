export type PersonaStatus = 'ACTIVE' | 'ARCHIVED';

export interface PersonaSummary {
  id: string;
  name: string;
  description: string | null;
  status: PersonaStatus;
  defaultLanguage: 'AUTO' | 'ENGLISH' | 'ROMANIAN';
  defaultTone: 'NEUTRAL' | 'INFORMATIVE' | 'CASUAL' | 'ENERGETIC';
  audience: string | null;
  voiceDescription: string;
  styleGuidelines: string | null;
  avoidGuidelines: string | null;
  hashtagGuidelines: string | null;
  exampleCopy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePersonaRequest {
  name: string;
  description: string | null;
  defaultLanguage: PersonaSummary['defaultLanguage'] | null;
  defaultTone: PersonaSummary['defaultTone'] | null;
  audience: string | null;
  voiceDescription: string;
  styleGuidelines: string | null;
  avoidGuidelines: string | null;
  hashtagGuidelines: string | null;
  exampleCopy: string | null;
}

export type UpdatePersonaRequest = CreatePersonaRequest;
