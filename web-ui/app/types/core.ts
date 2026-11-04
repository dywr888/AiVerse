export interface SessionDto {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  workspacePath?: string;
  mode?: string;
  modeBeforePlan?: string | null;
  goal?: string | null;
  goalStatus?: string | null;
  reasoningEffort?: string;
  providerId?: string | null;
  model?: string | null;
  totalInputTokens?: number;
  totalOutputTokens?: number;
  isPinned?: boolean;
  parentId?: string | null;
  subagentType?: string | null;
}

export interface MessageDto {
  id: string;
  sessionId: string;
  role: string;
  content: string;
  timestamp: number;
  toolCallsJson?: string | null;
  toolCallId?: string | null;
  toolName?: string | null;
  toolArgs?: string | null;
  isError?: boolean;
  reasoning?: string | null;
  attachmentsJson?: string | null;
  isCompacted?: boolean;
  isContextSummary?: boolean;
  isCompactionMarker?: boolean;
  inputTokens?: number;
  outputTokens?: number;
  cachedInputTokens?: number;
  isContextExcluded?: boolean;
}

export interface FavoriteDto {
  id: string;
  content: string;
  sessionTitle?: string;
  createdAt: number;
}

export type MessageRole = "USER" | "ASSISTANT" | "TOOL" | "SYSTEM";
