import { createClient } from "@/lib/supabase/client";
import { API_BASE_URL } from "@/lib/config";

/** Browser-side API error mirroring the server's error envelope. */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }
}

async function accessToken(): Promise<string | null> {
  const supabase = createClient();
  const {
    data: { session },
  } = await supabase.auth.getSession();
  return session?.access_token ?? null;
}

/** Authenticated fetch against the Scala API: attaches the Supabase access
 * token, parses JSON, and throws typed ApiError from the error envelope.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await accessToken();
  const res = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init.body ? { "Content-Type": "application/json" } : {}),
      ...init.headers,
    },
  });

  const text = await res.text();
  const json = text ? JSON.parse(text) : null;

  if (!res.ok) {
    const envelope = json?.error as { code?: string; message?: string } | undefined;
    throw new ApiError(
      envelope?.code ?? "request_failed",
      envelope?.message ?? `Request failed with HTTP ${res.status}.`,
      res.status,
    );
  }
  return json as T;
}

export interface WorkspaceDto {
  id: string;
  name: string;
  slug: string;
  timezone: string;
  dailySendCap: number;
  createdAt: string;
  role: string;
}

export const workspacesApi = {
  list: () => apiFetch<WorkspaceDto[]>("/api/v1/workspaces"),
  create: (name: string) =>
    apiFetch<WorkspaceDto>("/api/v1/workspaces", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
};

export interface MemberDto {
  userId: string;
  role: string;
  displayName: string | null;
  email: string | null;
  joinedAt: string;
}

export interface InvitationDto {
  id: string;
  email: string;
  role: string;
  expiresAt: string;
  accepted: boolean;
  inviteUrl: string;
}

export const membersApi = {
  list: (workspaceId: string) => apiFetch<MemberDto[]>(`/api/v1/workspaces/${workspaceId}/members`),
  updateRole: (workspaceId: string, userId: string, role: string) =>
    apiFetch<MemberDto>(`/api/v1/workspaces/${workspaceId}/members/${userId}`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
    }),
  remove: (workspaceId: string, userId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/members/${userId}`, {
      method: "DELETE",
    }),
};

export const invitationsApi = {
  list: (workspaceId: string) =>
    apiFetch<InvitationDto[]>(`/api/v1/workspaces/${workspaceId}/invitations`),
  create: (workspaceId: string, email: string, role: string) =>
    apiFetch<InvitationDto>(`/api/v1/workspaces/${workspaceId}/invitations`, {
      method: "POST",
      body: JSON.stringify({ email, role }),
    }),
  revoke: (workspaceId: string, invitationId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/invitations/${invitationId}`, {
      method: "DELETE",
    }),
  detailsByToken: (token: string) =>
    apiFetch<{ email: string; workspaceName: string; role: string }>(
      `/api/v1/invitations/${token}`,
    ),
  accept: (token: string) =>
    apiFetch<WorkspaceDto>(`/api/v1/invitations/${token}/accept`, {
      method: "POST",
    }),
};

export const workspaceSettingsApi = {
  update: (
    workspaceId: string,
    patch: { name?: string; timezone?: string; dailySendCap?: number },
  ) =>
    apiFetch<WorkspaceDto>(`/api/v1/workspaces/${workspaceId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
};
