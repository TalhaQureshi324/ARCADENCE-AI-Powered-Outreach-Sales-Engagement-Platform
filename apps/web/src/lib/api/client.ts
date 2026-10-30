import { createClient } from "@/lib/supabase/client";
import { API_BASE_URL } from "@/lib/config";
export { API_BASE_URL };

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

export async function accessToken(): Promise<string | null> {
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

// -- Segments (Phase 3.4) ------------------------------------------------------

export interface SegmentDto {
  id: string;
  name: string;
  filters: SegmentFilterGroup;
  createdAt: string;
  updatedAt: string;
}

export interface SegmentCondition {
  field: "state" | "verification_status" | "company" | "tag" | "region" | "job_title" | "email";
  operator: "eq" | "neq" | "contains" | "not_contains" | "in" | "not_in";
  value?: string;
  values?: string[];
}

export interface SegmentFilterGroup {
  op: "and" | "or";
  conditions: (SegmentCondition | SegmentFilterGroup)[];
}

export interface SegmentPreviewDto {
  count: number;
  sample: ContactDto[];
}

export interface DataQualityDto {
  total: number;
  valid: number;
  risky: number;
  invalid: number;
  unknown: number;
  active: number;
  unsubscribed: number;
  bounced: number;
  complained: number;
  missingName: number;
  missingCompany: number;
}

export interface MergeContactResultDto {
  mergedId: string;
  keptId: string;
  filledFields: number;
  movedTags: number;
  movedListMemberships: number;
}

export const segmentsApi = {
  list: (workspaceId: string) =>
    apiFetch<SegmentDto[]>(`/api/v1/workspaces/${workspaceId}/segments`),
  create: (workspaceId: string, input: { name: string; filters: SegmentFilterGroup }) =>
    apiFetch<SegmentDto>(`/api/v1/workspaces/${workspaceId}/segments`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  update: (
    workspaceId: string,
    segmentId: string,
    patch: { name?: string; filters?: SegmentFilterGroup },
  ) =>
    apiFetch<SegmentDto>(`/api/v1/workspaces/${workspaceId}/segments/${segmentId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  remove: (workspaceId: string, segmentId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/segments/${segmentId}`, {
      method: "DELETE",
    }),
  preview: (workspaceId: string, segmentId: string) =>
    apiFetch<SegmentPreviewDto>(`/api/v1/workspaces/${workspaceId}/segments/${segmentId}/preview`, {
      method: "POST",
    }),
  bulkTag: (workspaceId: string, segmentId: string, tagId: string) =>
    apiFetch<{ tagged: number }>(
      `/api/v1/workspaces/${workspaceId}/segments/${segmentId}/bulk-tag`,
      { method: "POST", body: JSON.stringify({ segmentId, tagId }) },
    ),
  dataQuality: (workspaceId: string) =>
    apiFetch<DataQualityDto>(`/api/v1/workspaces/${workspaceId}/contacts/data-quality`),
  merge: (workspaceId: string, keptContactId: string, sourceContactId: string) =>
    apiFetch<MergeContactResultDto>(
      `/api/v1/workspaces/${workspaceId}/contacts/${keptContactId}/merge`,
      { method: "POST", body: JSON.stringify({ sourceContactId }) },
    ),
  /** Segment export is CSV text, not JSON — stream it through a blob download. */
  exportCsv: async (workspaceId: string, segmentId: string, fileName: string) => {
    const token = await accessToken();
    const res = await fetch(
      `${API_BASE_URL}/api/v1/workspaces/${workspaceId}/segments/${segmentId}/export`,
      { method: "POST", headers: token ? { Authorization: `Bearer ${token}` } : {} },
    );
    if (!res.ok) {
      let code = "request_failed";
      let message = `Export failed with HTTP ${res.status}.`;
      try {
        const body = await res.json();
        if (body?.code) code = body.code;
        if (body?.message) message = body.message;
      } catch {
        // non-JSON error body — keep defaults
      }
      throw new ApiError(code, message, res.status);
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  },
};

// -- Campaigns (Phase 4.1) -----------------------------------------------------

export interface CampaignVariantDto {
  variantKey: string;
  subject: string;
  preheader: string;
  bodyHtml: string;
  bodyText: string;
  signature: string;
}

export interface CampaignStepDto {
  id: string;
  position: number;
  type: string;
  name: string;
  waitDays: number | null;
  key: string | null;
  condition: Record<string, unknown> | null;
  tagId: string | null;
  taskDueDays: number | null;
  businessDays: boolean;
  variants: CampaignVariantDto[];
}

export interface CampaignEdgeDto {
  from: string;
  to: string;
  branchKey: string | null;
}

export interface CampaignAudienceDto {
  id: string;
  leadListId: string | null;
  segmentId: string | null;
}

export interface CampaignDto {
  id: string;
  workspaceId: string;
  name: string;
  description: string;
  tags: string[];
  status: "draft" | "active" | "paused" | "completed" | "cancelled" | "archived";
  timezone: string;
  dailyCap: number;
  openTracking: boolean;
  clickTracking: boolean;
  stepCount: number;
  activatedAt: string | null;
  archivedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CampaignDetailDto {
  campaign: CampaignDto;
  steps: CampaignStepDto[];
  edges: CampaignEdgeDto[];
  mailboxIds: string[];
  audiences: CampaignAudienceDto[];
}

export interface EdgeInput {
  from: string;
  to: string;
  branchKey?: string;
}

export interface CampaignStepInput {
  type: string;
  name?: string;
  waitDays?: number;
  key?: string;
  condition?: Record<string, unknown>;
  tagId?: string;
  taskDueDays?: number;
  businessDays?: boolean;
  variants?: {
    variantKey: string;
    subject?: string;
    preheader?: string;
    bodyHtml?: string;
    bodyText?: string;
  }[];
}

export const campaignsApi = {
  list: (workspaceId: string, status?: string) =>
    apiFetch<CampaignDto[]>(
      `/api/v1/workspaces/${workspaceId}/campaigns${status ? `?status=${encodeURIComponent(status)}` : ""}`,
    ),
  create: (workspaceId: string, input: { name: string; description?: string }) =>
    apiFetch<CampaignDto>(`/api/v1/workspaces/${workspaceId}/campaigns`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  get: (workspaceId: string, campaignId: string) =>
    apiFetch<CampaignDetailDto>(`/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}`),
  update: (workspaceId: string, campaignId: string, patch: Record<string, unknown>) =>
    apiFetch<CampaignDetailDto>(`/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}`, {
      method: "PATCH",
      body: JSON.stringify(patch),
    }),
  remove: (workspaceId: string, campaignId: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}`, {
      method: "DELETE",
    }),
  duplicate: (workspaceId: string, campaignId: string) =>
    apiFetch<CampaignDetailDto>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/duplicate`,
      { method: "POST" },
    ),
  transition: (workspaceId: string, campaignId: string, action: string) =>
    apiFetch<{ status: string }>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/transition`,
      { method: "POST", body: JSON.stringify({ action }) },
    ),
  enroll: (workspaceId: string, campaignId: string, contactIds: string[]) =>
    apiFetch<{ enrolled: number }>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/enroll`,
      { method: "POST", body: JSON.stringify({ contactIds }) },
    ),
  versions: (workspaceId: string, campaignId: string) =>
    apiFetch<{ id: string; version: number; definition: unknown; createdAt: string }[]>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/versions`,
    ),
};

// -- Composer (Phase 4.2) ------------------------------------------------------

export interface VariantValidationDto {
  tokens: { field: string; fallback: string | null }[];
  unknown: string[];
}

export interface PreviewReportDto {
  resolved: Record<string, string>;
  fellBack: string[];
  missing: string[];
  sanitized: boolean;
}

export interface PreviewResponseDto {
  subject: string;
  html: string;
  text: string;
  report: PreviewReportDto;
}

export interface EmailTemplateDto {
  id: string;
  name: string;
  subject: string;
  preheader: string;
  bodyHtml: string;
  bodyText: string;
}

export interface ReusableBlockDto {
  id: string;
  name: string;
  bodyHtml: string;
  bodyText: string;
}

export const composerApi = {
  saveVariant: (
    workspaceId: string,
    campaignId: string,
    stepId: string,
    variantKey: string,
    content: {
      subject: string;
      preheader: string;
      bodyHtml: string;
      bodyText: string;
      signature: string;
    },
  ) =>
    apiFetch<{ variant: CampaignVariantDto; validation: VariantValidationDto }>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/steps/${stepId}/variants/${variantKey}`,
      { method: "PUT", body: JSON.stringify(content) },
    ),
  preview: (
    workspaceId: string,
    campaignId: string,
    input: { stepId: string; variantKey: string; contactId: string },
  ) =>
    apiFetch<PreviewResponseDto>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/preview`,
      {
        method: "POST",
        body: JSON.stringify(input),
      },
    ),
  testSend: (
    workspaceId: string,
    campaignId: string,
    input: { stepId: string; variantKey: string; contactId: string; emailAccountId?: string },
  ) =>
    apiFetch<{ from: string; to: string; subject: string; providerMessageId: string }>(
      `/api/v1/workspaces/${workspaceId}/campaigns/${campaignId}/test-send`,
      { method: "POST", body: JSON.stringify(input) },
    ),
  templates: {
    list: (workspaceId: string) =>
      apiFetch<EmailTemplateDto[]>(`/api/v1/workspaces/${workspaceId}/email-templates`),
    create: (workspaceId: string, input: Omit<EmailTemplateDto, "id">) =>
      apiFetch<EmailTemplateDto>(`/api/v1/workspaces/${workspaceId}/email-templates`, {
        method: "POST",
        body: JSON.stringify(input),
      }),
    remove: (workspaceId: string, templateId: string) =>
      apiFetch<void>(`/api/v1/workspaces/${workspaceId}/email-templates/${templateId}`, {
        method: "DELETE",
      }),
  },
  blocks: {
    list: (workspaceId: string) =>
      apiFetch<ReusableBlockDto[]>(`/api/v1/workspaces/${workspaceId}/reusable-blocks`),
    create: (workspaceId: string, input: Omit<ReusableBlockDto, "id">) =>
      apiFetch<ReusableBlockDto>(`/api/v1/workspaces/${workspaceId}/reusable-blocks`, {
        method: "POST",
        body: JSON.stringify(input),
      }),
    remove: (workspaceId: string, blockId: string) =>
      apiFetch<void>(`/api/v1/workspaces/${workspaceId}/reusable-blocks/${blockId}`, {
        method: "DELETE",
      }),
  },
};

// -- Unibox (Phase 6) -----------------------------------------------------------

export interface InboxThreadDto {
  id: string;
  emailAccountId: string;
  contactEmail: string | null;
  contactName: string | null;
  campaignName: string | null;
  subject: string;
  status: string;
  classification: string;
  unread: boolean;
  assignedTo: string | null;
  assignedToName: string | null;
  lastMessageAt: string;
}

export interface InboxMessageDto {
  id: string;
  direction: string;
  fromAddress: string;
  subject: string;
  bodyText: string;
  bodyHtml: string;
  occurredAt: string;
}

export interface InboxNoteDto {
  id: string;
  authorName: string | null;
  body: string;
  createdAt: string;
}

export interface InboxThreadDetailDto {
  thread: InboxThreadDto;
  messages: InboxMessageDto[];
  notes: InboxNoteDto[];
}

export const inboxApi = {
  list: (
    workspaceId: string,
    filters: { classification?: string; status?: string; unread?: boolean; q?: string } = {},
  ) => {
    const p = new URLSearchParams();
    Object.entries(filters).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== "") p.set(k, String(v));
    });
    const qs = p.toString();
    return apiFetch<{ items: InboxThreadDto[]; nextCursor: string | null }>(
      `/api/v1/workspaces/${workspaceId}/inbox${qs ? `?${qs}` : ""}`,
    );
  },
  sync: (workspaceId: string) =>
    apiFetch<{ items: InboxThreadDto[]; nextCursor: string | null }>(
      `/api/v1/workspaces/${workspaceId}/inbox/sync`,
      { method: "POST" },
    ),
  get: (workspaceId: string, threadId: string) =>
    apiFetch<InboxThreadDetailDto>(`/api/v1/workspaces/${workspaceId}/inbox/${threadId}`),
  reply: (workspaceId: string, threadId: string, bodyText: string) =>
    apiFetch<InboxMessageDto>(`/api/v1/workspaces/${workspaceId}/inbox/${threadId}/reply`, {
      method: "POST",
      body: JSON.stringify({ bodyText }),
    }),
  assign: (workspaceId: string, threadId: string, assignedTo: string | null) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/inbox/${threadId}`, {
      method: "PATCH",
      body: JSON.stringify({ assignedTo }),
    }),
  addNote: (workspaceId: string, threadId: string, body: string) =>
    apiFetch<void>(`/api/v1/workspaces/${workspaceId}/inbox/${threadId}/notes`, {
      method: "POST",
      body: JSON.stringify({ body }),
    }),
};

// -- Analytics (Phase 7) ---------------------------------------------------------

export interface AnalyticsWarning {
  type: string;
  change?: number;
  detail: string;
}

export const analyticsApi = {
  summary: (workspaceId: string, from?: string, to?: string) => {
    const p = new URLSearchParams();
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    return apiFetch<Record<string, number | string>>(
      `/api/v1/workspaces/${workspaceId}/analytics/summary${p.toString() ? `?${p}` : ""}`,
    );
  },
  timeseries: async (workspaceId: string, from?: string, to?: string) => {
    const p = new URLSearchParams();
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    const res = await apiFetch<unknown[]>(
      `/api/v1/workspaces/${workspaceId}/analytics/timeseries${p.toString() ? `?${p}` : ""}`,
    );
    return res;
  },
  campaigns: async (workspaceId: string, from?: string, to?: string) => {
    const p = new URLSearchParams();
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    const res = await apiFetch<unknown[]>(
      `/api/v1/workspaces/${workspaceId}/analytics/campaigns${p.toString() ? `?${p}` : ""}`,
    );
    return res;
  },
  mailboxes: async (workspaceId: string, from?: string, to?: string) => {
    const p = new URLSearchParams();
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    const res = await apiFetch<unknown[]>(
      `/api/v1/workspaces/${workspaceId}/analytics/mailboxes${p.toString() ? `?${p}` : ""}`,
    );
    return res;
  },
  anomalies: async (workspaceId: string) => {
    const res = await apiFetch<{ warnings: AnalyticsWarning[] }>(
      `/api/v1/workspaces/${workspaceId}/analytics/anomalies`,
    );
    return res;
  },
};

// -- Deliverability (Phase 8) ----------------------------------------------------

export const deliverabilityApi = {
  checkDomain: (workspaceId: string, domain: string, provider: string, dkimSelectors: string[]) =>
    apiFetch<{
      domain: string;
      overall: string;
      findings: { record: string; ok: boolean; message: string; remediation: string }[];
    }>(`/api/v1/workspaces/${workspaceId}/deliverability/domains/check`, {
      method: "POST",
      body: JSON.stringify({ domain, provider, dkimSelectors }),
    }),
  domainHistory: (workspaceId: string, domain: string) =>
    apiFetch<{ history: { checkedAt: string; status: string; reasons: unknown }[] }>(
      `/api/v1/workspaces/${workspaceId}/deliverability/domains/${encodeURIComponent(domain)}/history`,
    ),
  accountHealth: (workspaceId: string, accountId: string) =>
    apiFetch<{ status: string; reasons: string[]; bounceHardRate: number; authFailures: number }>(
      `/api/v1/workspaces/${workspaceId}/deliverability/accounts/${accountId}/health`,
      { method: "POST" },
    ),
  createPlacementTest: (
    workspaceId: string,
    emailAccountId: string,
    sampleSize: number,
    seedAddresses: string[],
  ) =>
    apiFetch<{ id: string; status: string; statusReason: string | null; sampleSize: number }>(
      `/api/v1/workspaces/${workspaceId}/deliverability/placement-tests`,
      { method: "POST", body: JSON.stringify({ emailAccountId, sampleSize, seedAddresses }) },
    ),
  placementTests: (workspaceId: string) =>
    apiFetch<{ id: string; status: string; statusReason: string | null; sampleSize: number }[]>(
      `/api/v1/workspaces/${workspaceId}/deliverability/placement-tests`,
    ),
};

export interface LeadFinderSearchDto {
  id: string;
  provider: string;
  status: string;
  resultsCount: number;
  estimatedCredits: number;
  creditsCharged: number;
  error: string | null;
}

export interface LeadFinderDiscoveredDto {
  id: string;
  provider: string;
  email: string | null;
  emailVerification: string;
  firstName: string | null;
  lastName: string | null;
  jobTitle: string | null;
  companyName: string | null;
  industry: string | null;
  region: string | null;
  companySize: string | null;
  confidence: number;
  permittedUse: string;
}

export interface LeadFinderProviderDto {
  name: string;
  supportedFilters: string[];
}

export const leadFinderApi = {
  providers: (workspaceId: string) =>
    apiFetch<LeadFinderProviderDto[]>(`/api/v1/workspaces/${workspaceId}/leadfinder/providers`),
  estimate: (workspaceId: string, maxResults: number) =>
    apiFetch<{ estimatedCredits: number; creditsPerLead: number; note: string }>(
      `/api/v1/workspaces/${workspaceId}/leadfinder/estimate?maxResults=${maxResults}`,
      { method: "POST" },
    ),
  search: (workspaceId: string, filters: Record<string, string>) =>
    apiFetch<LeadFinderSearchDto>(`/api/v1/workspaces/${workspaceId}/leadfinder/search`, {
      method: "POST",
      body: JSON.stringify({ filters }),
    }),
  results: (workspaceId: string, searchId: string) =>
    apiFetch<LeadFinderDiscoveredDto[]>(
      `/api/v1/workspaces/${workspaceId}/leadfinder/searches/${searchId}/results`,
    ),
  searches: (workspaceId: string) =>
    apiFetch<LeadFinderSearchDto[]>(`/api/v1/workspaces/${workspaceId}/leadfinder/searches`),
  credits: (workspaceId: string) =>
    apiFetch<{
      balance: number;
      history: { kind: string; delta: number; reason: string; at: string }[];
    }>(`/api/v1/workspaces/${workspaceId}/leadfinder/credits`),
  addToList: (workspaceId: string, searchRequestId: string, listId: string) =>
    apiFetch<{
      added: number;
      skippedDuplicates: number;
      skippedSuppressed: number;
      sandboxOnlyBlocked: boolean;
      note: string;
    }>(`/api/v1/workspaces/${workspaceId}/leadfinder/add-to-list`, {
      method: "POST",
      body: JSON.stringify({ searchRequestId, listId }),
    }),
};

export interface AiAgentDto {
  id: string;
  name: string;
  kind: string;
  provider: string;
  model: string;
  enabled: boolean;
  requiresApproval: boolean;
  maxCredits: number;
  config: unknown;
}

export const aiApi = {
  agents: (workspaceId: string) =>
    apiFetch<AiAgentDto[]>(`/api/v1/workspaces/${workspaceId}/ai/agents`),
  createAgent: (workspaceId: string, input: { name: string; kind: string }) =>
    apiFetch<AiAgentDto>(`/api/v1/workspaces/${workspaceId}/ai/agents`, {
      method: "POST",
      body: JSON.stringify(input),
    }),
  runs: (workspaceId: string) =>
    apiFetch<
      {
        id: string;
        kind: string;
        status: string;
        inputTokens: number;
        outputTokens: number;
        costMicros: number;
        error: string | null;
      }[]
    >(`/api/v1/workspaces/${workspaceId}/ai/runs`),
  approvals: (workspaceId: string, status: string) =>
    apiFetch<{ id: string; action: string; status: string }[]>(
      `/api/v1/workspaces/${workspaceId}/ai/approvals?status=${status}`,
    ),
  usage: (workspaceId: string) =>
    apiFetch<{ month: { totalTokens: number; costMicros: number }; budgetTokens: number }>(
      `/api/v1/workspaces/${workspaceId}/ai/usage`,
    ),
  messages: (workspaceId: string, messageId: string, decision: "approve" | "reject") =>
    apiFetch(`/api/v1/workspaces/${workspaceId}/ai/messages/${messageId}/${decision}`, {
      method: "POST",
    }),
  decideApproval: (workspaceId: string, approvalId: string, decision: "approved" | "rejected") =>
    apiFetch(`/api/v1/workspaces/${workspaceId}/ai/approvals/${approvalId}/decide`, {
      method: "POST",
      body: JSON.stringify({ decision }),
    }),
};
