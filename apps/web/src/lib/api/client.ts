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
    // The Scala API returns the envelope at top level ({code, message[, requestId]});
    // json.error is kept as a fallback for non-API responses.
    const envelope =
      (json?.code ? { code: json.code as string, message: json.message as string } : undefined) ??
      (json?.error as { code?: string; message?: string } | undefined);
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

export interface ImportJobDto {
  id: string;
  fileName: string;
  status: string;
  duplicateStrategy: string;
  totalRows: number;
  processedRows: number;
  insertedRows: number;
  updatedRows: number;
  skippedRows: number;
  invalidRows: number;
  duplicateRows: number;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export const importsApi = {
  /** Uploads raw CSV bytes for parsing; returns the job + headers + preview. */
  upload: (workspaceId: string, fileName: string, bytes: Uint8Array) =>
    apiFetch<{ job: ImportJobDto; headers: string[]; preview: string[][] }>(
      `/api/v1/workspaces/${workspaceId}/imports/upload?fileName=${encodeURIComponent(fileName)}`,
      {
        method: "POST",
        body: bytes as unknown as BodyInit,
        headers: { "Content-Type": "application/octet-stream" },
      },
    ),
  start: (
    workspaceId: string,
    jobId: string,
    input: {
      mapping: Record<string, string>;
      duplicateStrategy: string;
      defaults?: Record<string, string>;
      targetListId?: string;
    },
  ) =>
    apiFetch<ImportJobDto>(`/api/v1/workspaces/${workspaceId}/imports/${jobId}/start`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  list: (workspaceId: string) =>
    apiFetch<ImportJobDto[]>(`/api/v1/workspaces/${workspaceId}/imports`),
  get: (workspaceId: string, jobId: string) =>
    apiFetch<ImportJobDto>(`/api/v1/workspaces/${workspaceId}/imports/${jobId}`),
  retry: (workspaceId: string, jobId: string) =>
    apiFetch<ImportJobDto>(`/api/v1/workspaces/${workspaceId}/imports/${jobId}/retry`, {
      method: "POST",
    }),
};

export interface SuppressionDto {
  id: string;
  email: string;
  contactId: string | null;
  reason: string;
  source: string;
  createdAt: string;
}

export const verificationApi = {
  verify: (workspaceId: string, contactId: string) =>
    apiFetch<{
      contactId: string;
      email: string;
      status: string;
      provider: string;
      details: string;
      suppressed: boolean;
    }>(`/api/v1/workspaces/${workspaceId}/contacts/${contactId}/verify`, { method: "POST" }),
  suppressions: {
    list: (workspaceId: string, q?: string) =>
      apiFetch<SuppressionDto[]>(
        `/api/v1/workspaces/${workspaceId}/suppressions${q ? `?q=${encodeURIComponent(q)}` : ""}`,
      ),
    create: (workspaceId: string, input: { email?: string; contactId?: string; reason: string }) =>
      apiFetch<SuppressionDto>(`/api/v1/workspaces/${workspaceId}/suppressions`, {
        method: "POST",
        body: JSON.stringify(input),
      }),
    remove: (workspaceId: string, suppressionId: string) =>
      apiFetch<void>(`/api/v1/workspaces/${workspaceId}/suppressions/${suppressionId}`, {
        method: "DELETE",
      }),
  },
};

// -- Leads (Phase 3.1) ---------------------------------------------------------

export interface ContactDto {
  id: string;
  companyId: string | null;
  companyName: string | null;
  email: string;
  emailDisplay: string | null;
  firstName: string | null;
  lastName: string | null;
  jobTitle: string | null;
  website: string | null;
  phone: string | null;
  region: string | null;
  timezone: string | null;
  notes: string | null;
  customFields: Record<string, unknown>;
  verificationStatus: string;
  verificationCheckedAt: string | null;
  verificationProvider: string | null;
  state: string;
  createdAt: string;
  updatedAt: string;
}

export interface ContactPage {
  items: ContactDto[];
  nextCursor: string | null;
  total: number;
}

export interface ContactInput {
  email: string;
  emailDisplay?: string;
  firstName?: string;
  lastName?: string;
  jobTitle?: string;
  companyId?: string | null;
  website?: string;
  phone?: string;
  region?: string;
  timezone?: string;
  notes?: string;
  customFields?: Record<string, unknown>;
}

export const contactsApi = {
  list: (
    workspaceId: string,
    params: {
      q?: string;
      state?: string;
      verification?: string;
      companyId?: string;
      listId?: string;
      tagId?: string;
      sort?: string;
      limit?: number;
      cursor?: string;
    } = {},
  ) => {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") search.set(k, String(v));
    });
    const qs = search.toString();
    return apiFetch<ContactPage>(`/api/v1/workspaces/${workspaceId}/contacts${qs ? `?${qs}` : ""}`);
  },
  get: (workspaceId: string, contactId: string) =>
    apiFetch<ContactDto>(`/api/v1/workspaces/${workspaceId}/contacts/${contactId}`),
  create: (workspaceId: string, input: ContactInput) =>
    apiFetch<ContactDto>(`/api/v1/workspaces/${workspaceId}/contacts`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  update: (
    workspaceId: string,
    contactId: string,
    patch: Partial<Record<keyof ContactInput, unknown>>,
  ) =>
    apiFetch<ContactDto>(`/api/v1/workspaces/${workspaceId}/contacts/${contactId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  remove: (workspaceId: string, contactId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/contacts/${contactId}`, {
      method: "DELETE",
    }),
  batch: (workspaceId: string, contacts: ContactInput[]) =>
    apiFetch<{ created: number; updated: number; skipped: number }>(
      `/api/v1/workspaces/${workspaceId}/contacts/batch`,
      { method: "POST", body: JSON.stringify({ contacts }) },
    ),
};

export interface CompanyDto {
  id: string;
  name: string;
  domain: string | null;
  website: string | null;
  contactCount: number;
  createdAt: string;
}

export const companiesApi = {
  list: (workspaceId: string, q?: string) =>
    apiFetch<CompanyDto[]>(
      `/api/v1/workspaces/${workspaceId}/companies${q ? `?q=${encodeURIComponent(q)}` : ""}`,
    ),
  create: (workspaceId: string, input: { name: string; domain?: string; website?: string }) =>
    apiFetch<CompanyDto>(`/api/v1/workspaces/${workspaceId}/companies`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  update: (
    workspaceId: string,
    companyId: string,
    patch: { name?: string; domain?: string | null; website?: string | null },
  ) =>
    apiFetch<CompanyDto>(`/api/v1/workspaces/${workspaceId}/companies/${companyId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  remove: (workspaceId: string, companyId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/companies/${companyId}`, {
      method: "DELETE",
    }),
};

export interface LeadListDto {
  id: string;
  name: string;
  description: string | null;
  memberCount: number;
  createdAt: string;
}

export const leadListsApi = {
  list: (workspaceId: string) =>
    apiFetch<LeadListDto[]>(`/api/v1/workspaces/${workspaceId}/lead-lists`),
  create: (workspaceId: string, input: { name: string; description?: string }) =>
    apiFetch<LeadListDto>(`/api/v1/workspaces/${workspaceId}/lead-lists`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  update: (
    workspaceId: string,
    listId: string,
    patch: { name?: string; description?: string | null },
  ) =>
    apiFetch<LeadListDto>(`/api/v1/workspaces/${workspaceId}/lead-lists/${listId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  remove: (workspaceId: string, listId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/lead-lists/${listId}`, {
      method: "DELETE",
    }),
  addMembers: (workspaceId: string, listId: string, contactIds: string[]) =>
    apiFetch<{ added: number }>(`/api/v1/workspaces/${workspaceId}/lead-lists/${listId}/members`, {
      method: "POST",
      body: JSON.stringify({ contactIds }),
    }),
  removeMember: (workspaceId: string, listId: string, contactId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/lead-lists/${listId}/members/${contactId}`, {
      method: "DELETE",
    }),
};

export interface TagDto {
  id: string;
  name: string;
  color: string | null;
  contactCount: number;
  createdAt: string;
}

export const tagsApi = {
  list: (workspaceId: string) => apiFetch<TagDto[]>(`/api/v1/workspaces/${workspaceId}/tags`),
  create: (workspaceId: string, input: { name: string; color?: string }) =>
    apiFetch<TagDto>(`/api/v1/workspaces/${workspaceId}/tags`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  remove: (workspaceId: string, tagId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/tags/${tagId}`, {
      method: "DELETE",
    }),
};

export interface CustomFieldDto {
  id: string;
  name: string;
  fieldType: string;
  description: string | null;
  createdAt: string;
}

export const customFieldsApi = {
  list: (workspaceId: string) =>
    apiFetch<CustomFieldDto[]>(`/api/v1/workspaces/${workspaceId}/custom-fields`),
  create: (workspaceId: string, input: { name: string; fieldType: string; description?: string }) =>
    apiFetch<CustomFieldDto>(`/api/v1/workspaces/${workspaceId}/custom-fields`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  update: (
    workspaceId: string,
    fieldId: string,
    patch: { name?: string; description?: string | null },
  ) =>
    apiFetch<CustomFieldDto>(`/api/v1/workspaces/${workspaceId}/custom-fields/${fieldId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  remove: (workspaceId: string, fieldId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/custom-fields/${fieldId}`, {
      method: "DELETE",
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

export interface EmailProviderDto {
  provider: string;
  label: string;
  authType: string;
  canSend: boolean;
  canSyncInbox: boolean;
  canSendReply: boolean;
  canRefreshToken: boolean;
  hasHealthCheck: boolean;
  permissionsSummary: string;
  connectFlow: string;
  /** True when this server's connect flow is ready (credentials present). */
  configured: boolean;
}

export const emailProvidersApi = {
  list: () => apiFetch<EmailProviderDto[]>("/api/v1/email-providers"),
};

/** Gmail OAuth connect flow: ask the API for a state-signed authorize URL,
 * then navigate the browser to Google's consent screen.
 */
export const oauthConnectApi = {
  start: (provider: string, workspaceId: string) =>
    apiFetch<{ authorizeUrl: string }>(`/api/v1/email-connections/oauth/${provider}/start`, {
      method: "POST",
      body: JSON.stringify({ workspaceId }),
    }),
};

export interface AccountGroupDto {
  id: string;
  name: string;
  accountCount: number;
  createdAt: string;
}

export const accountGroupsApi = {
  list: (workspaceId: string) =>
    apiFetch<AccountGroupDto[]>(`/api/v1/workspaces/${workspaceId}/account-groups`),
  create: (workspaceId: string, name: string) =>
    apiFetch<AccountGroupDto>(`/api/v1/workspaces/${workspaceId}/account-groups`, {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  rename: (workspaceId: string, groupId: string, name: string) =>
    apiFetch<AccountGroupDto>(`/api/v1/workspaces/${workspaceId}/account-groups/${groupId}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    }),
  remove: (workspaceId: string, groupId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/account-groups/${groupId}`, {
      method: "DELETE",
    }),
};

export interface EmailAccountDto {
  id: string;
  accountGroupId: string | null;
  accountGroupName: string | null;
  provider: string;
  emailAddress: string;
  displayName: string | null;
  status: string;
  statusMessage: string | null;
  dailySendCap: number | null;
  signature: string | null;
  isDefault: boolean;
  lastSyncedAt: string | null;
  syncStatus: string | null;
  syncError: string | null;
  createdAt: string;
}

export interface SmtpImapConnectInput {
  emailAddress: string;
  displayName?: string;
  username: string;
  password: string;
  smtpHost: string;
  smtpPort: number;
  smtpSecurity: string;
  imapHost: string;
  imapPort: number;
  imapSecurity: string;
}

export interface HealthEventDto {
  id: string;
  eventType: string;
  message: string | null;
  occurredAt: string;
}

export const emailAccountsApi = {
  list: (workspaceId: string) =>
    apiFetch<EmailAccountDto[]>(`/api/v1/workspaces/${workspaceId}/email-accounts`),
  /** Connect an SMTP/IMAP account: the server validates the credentials
   * against the real servers, stores them encrypted, and returns the account.
   */
  create: (workspaceId: string, input: SmtpImapConnectInput) =>
    apiFetch<EmailAccountDto>(`/api/v1/workspaces/${workspaceId}/email-accounts`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  get: (workspaceId: string, accountId: string) =>
    apiFetch<EmailAccountDto>(`/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}`),
  update: (
    workspaceId: string,
    accountId: string,
    patch: {
      displayName?: string | null;
      accountGroupId?: string | null;
      dailySendCap?: number | null;
      signature?: string | null;
    },
  ) =>
    apiFetch<EmailAccountDto>(`/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  pause: (workspaceId: string, accountId: string) =>
    apiFetch<EmailAccountDto>(
      `/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}/pause`,
      { method: "POST" },
    ),
  resume: (workspaceId: string, accountId: string) =>
    apiFetch<EmailAccountDto>(
      `/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}/resume`,
      { method: "POST" },
    ),
  disconnect: (workspaceId: string, accountId: string) =>
    apiFetch<EmailAccountDto>(
      `/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}/disconnect`,
      { method: "POST" },
    ),
  remove: (workspaceId: string, accountId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}`, {
      method: "DELETE",
    }),
  health: (workspaceId: string, accountId: string) =>
    apiFetch<HealthEventDto[]>(
      `/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}/health`,
    ),
  /** Authorized test send from a connected account. */
  test: (workspaceId: string, accountId: string, to: string) =>
    apiFetch<{ messageId: string }>(
      `/api/v1/workspaces/${workspaceId}/email-accounts/${accountId}/test`,
      { method: "POST", body: JSON.stringify({ to }) },
    ),
};
