"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ChevronDown,
  ChevronRight,
  History,
  Mail,
  Pencil,
  Plus,
  ShieldCheck,
  Trash2,
  Users,
  X,
} from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import { Dialog } from "@/components/ui/dialog";
import {
  ApiError,
  accountGroupsApi,
  emailAccountsApi,
  emailProvidersApi,
  oauthConnectApi,
  type AccountGroupDto,
  type EmailAccountDto,
  type EmailProviderDto,
  type HealthEventDto,
} from "@/lib/api/client";

type LoadState<T> =
  { kind: "loading" } | { kind: "loaded"; data: T } | { kind: "error"; message: string };

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function isUnauthorized(e: unknown): boolean {
  return e instanceof ApiError && e.isUnauthorized;
}

/** campaign managers and above manage email accounts (Permissions.scala). */
function canManage(role: string): boolean {
  return role === "owner" || role === "admin" || role === "campaign_manager";
}

const CALLBACK_MESSAGES: Record<string, { variant: "success" | "error" | "info"; text: string }> = {
  connected: { variant: "success", text: "Gmail account connected." },
  reconnected: {
    variant: "success",
    text: "Gmail account reconnected — credentials refreshed.",
  },
  denied: {
    variant: "error",
    text: "Google consent was denied — the account was not connected.",
  },
  invalid_state: {
    variant: "error",
    text: "The connect request could not be verified (invalid state). Start again from the Connect button.",
  },
  expired: {
    variant: "error",
    text: "The connect request expired. Start again from the Connect button.",
  },
  forbidden: {
    variant: "error",
    text: "Your access to that workspace changed before the connection completed.",
  },
  not_configured: {
    variant: "error",
    text: "Google OAuth is not configured on this server (see docs/MANUAL_SETUP.md).",
  },
  error: { variant: "error", text: "The provider reported a problem completing the connection." },
};

const OAUTH_PROVIDERS = ["gmail", "microsoft"] as const;

function unconfiguredNotice(providerId: string): { badge: string; hint: string } | null {
  if (providerId === "gmail")
    return {
      badge: "Google OAuth not configured on this server",
      hint: "Set GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET — steps in docs/MANUAL_SETUP.md.",
    };
  if (providerId === "microsoft")
    return {
      badge: "Microsoft OAuth not configured on this server",
      hint: "Set MSFT_OAUTH_CLIENT_ID / MSFT_OAUTH_CLIENT_SECRET — steps in docs/MANUAL_SETUP.md.",
    };
  return null;
}

/** Asks the API for a state-signed authorize URL and navigates to Google's
 * consent screen (full-page navigation — the callback returns here).
 */
async function startProviderConnect(workspaceId: string, providerId: string): Promise<void> {
  const { authorizeUrl } = await oauthConnectApi.start(providerId, workspaceId);
  window.location.href = authorizeUrl;
}

const STATUS_META: Record<string, { label: string; tone: BadgeTone }> = {
  connected: { label: "Connected", tone: "success" },
  reconnect_required: { label: "Reconnect required", tone: "warning" },
  rate_limited: { label: "Rate limited", tone: "warning" },
  suspended: { label: "Suspended", tone: "neutral" },
  error: { label: "Error", tone: "danger" },
  disconnected: { label: "Disconnected", tone: "neutral" },
};

const PROVIDER_LABEL: Record<string, string> = {
  gmail: "Gmail",
  microsoft: "Microsoft 365",
  smtp_imap: "SMTP/IMAP",
};

const HEALTH_LABEL: Record<string, string> = {
  connected: "Connected",
  disconnected: "Disconnected",
  reconnect_required: "Reconnect required",
  token_refreshed: "Token refreshed",
  rate_limited: "Rate limited",
  sync_error: "Sync error",
  send_error: "Send error",
  paused: "Paused",
  resumed: "Resumed",
  test_succeeded: "Test send succeeded",
  test_failed: "Test send failed",
};

function formatDate(value: string | null): string {
  if (!value) return "—";
  return new Date(value).toLocaleString(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}

export function EmailAccountsContent() {
  const { workspaces, selected } = useWorkspaces();
  const [connectNotice, setConnectNotice] = useState<string | null>(null);
  const [noticeVariant, setNoticeVariant] = useState<"success" | "error" | "info">("info");

  // OAuth callback landing: read ?gmail=..., show the outcome, clean the URL.
  useEffect(() => {
    let cancelled = false;
    Promise.resolve().then(() => {
      if (cancelled) return;
      const params = new URLSearchParams(window.location.search);
      const outcome = params.get("gmail") ?? params.get("microsoft");
      if (!outcome) return;
      const message = CALLBACK_MESSAGES[outcome] ?? {
        variant: "info" as const,
        text: `Connection finished with status: ${outcome}`,
      };
      setNoticeVariant(message.variant);
      setConnectNotice(message.text);
      params.delete("gmail");
      params.delete("microsoft");
      params.delete("detail");
      const cleaned = params.toString();
      window.history.replaceState(
        null,
        "",
        `${window.location.pathname}${cleaned ? `?${cleaned}` : ""}`,
      );
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (workspaces === null) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-72" />
        <Skeleton className="h-64" />
      </div>
    );
  }

  if (workspaces.length === 0 || !selected) {
    return (
      <EmptyState
        icon={<Users size={22} />}
        title="No workspace selected"
        description="Create a workspace from the selector above to connect email accounts."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader
          eyebrow={`Workspace · /${selected.slug}`}
          title="Email accounts"
          subtitle={`Connect sending identities for ${selected.name}. Your role: ${selected.role}.`}
        />
      </Card>
      {connectNotice && <Alert variant={noticeVariant}>{connectNotice}</Alert>}
      <ProvidersSection workspaceId={selected.id} myRole={selected.role} />
      <GroupsSection workspaceId={selected.id} myRole={selected.role} />
      <AccountsSection workspaceId={selected.id} myRole={selected.role} />
    </div>
  );
}

// -- Provider connection cards ------------------------------------------------

function ProvidersSection({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const manageable = canManage(myRole);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [providers, setProviders] = useState<LoadState<EmailProviderDto[]>>({
    kind: "loading",
  });

  async function connect(providerId: string) {
    if (!(OAUTH_PROVIDERS as readonly string[]).includes(providerId)) return;
    setConnecting(true);
    setConnectError(null);
    try {
      await startProviderConnect(workspaceId, "gmail"); // navigates away on success
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setConnectError(errorMessage(e));
      setConnecting(false);
    }
  }

  useEffect(() => {
    let cancelled = false;
    emailProvidersApi
      .list()
      .then((data) => {
        if (!cancelled) setProviders({ kind: "loaded", data });
      })
      .catch((e) => {
        if (!cancelled) setProviders({ kind: "error", message: errorMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (providers.kind === "loading") return <Skeleton className="h-44" />;
  if (providers.kind === "error") return <Alert variant="error">{providers.message}</Alert>;

  return (
    <section aria-label="Available providers">
      <h2 className="text-foreground mb-3 text-sm font-semibold">Providers</h2>
      <div className="grid gap-4 md:grid-cols-3">
        {providers.data.map((p) => (
          <Card key={p.provider} className="flex h-full flex-col">
            <div className="flex items-center justify-between gap-2">
              <div className="text-foreground text-sm font-semibold">{p.label}</div>
              <Badge tone={p.authType === "oauth2" ? "primary" : "neutral"}>
                {p.authType === "oauth2" ? "OAuth 2.0" : "TLS + password"}
              </Badge>
            </div>
            <ul
              className="text-muted-foreground mt-3 space-y-1 text-xs"
              aria-label={`${p.label} capabilities`}
            >
              {p.canSend && <li>✓ Campaign + test sends</li>}
              {p.canSyncInbox && <li>✓ Inbox synchronization</li>}
              {p.canSendReply && <li>✓ Threaded replies</li>}
              {p.canRefreshToken ? (
                <li>✓ Automatic token refresh</li>
              ) : (
                <li>— No token refresh (password auth)</li>
              )}
              {p.hasHealthCheck && <li>✓ Health checks</li>}
            </ul>
            <p className="text-muted-foreground mt-3 flex-1 text-xs leading-relaxed">
              <ShieldCheck size={12} className="mr-1 inline" aria-hidden="true" />
              {p.permissionsSummary}
            </p>
            <div className="border-border mt-4 border-t pt-3">
              {p.configured ? (
                manageable ? (
                  <>
                    <Button onClick={() => connect(p.provider)} loading={connecting}>
                      Connect {p.label.split(" /")[0]}
                    </Button>
                    <p className="text-muted-foreground mt-2 text-xs">
                      Google asks for the permissions listed above; you can revoke access at any
                      time from this page or your Google account.
                    </p>
                  </>
                ) : (
                  <p className="text-muted-foreground text-xs">
                    Connect flow is ready — managing accounts requires the campaign manager role or
                    above.
                  </p>
                )
              ) : unconfiguredNotice(p.provider) ? (
                <>
                  <Badge tone="warning">{unconfiguredNotice(p.provider)!.badge}</Badge>
                  <p className="text-muted-foreground mt-2 text-xs">
                    {unconfiguredNotice(p.provider)!.hint}
                  </p>
                </>
              ) : (
                <>
                  <Badge tone="warning">Setup arrives in {p.connectFlow}</Badge>
                  <p className="text-muted-foreground mt-2 text-xs">
                    The connect flow is built in {p.connectFlow}; provider-console prerequisites are
                    listed in docs/MANUAL_SETUP.md.
                  </p>
                </>
              )}
              {connectError && (
                <div className="mt-2">
                  <Alert variant="error">{connectError}</Alert>
                </div>
              )}
            </div>
          </Card>
        ))}
      </div>
    </section>
  );
}

// -- Account groups -------------------------------------------------------------

function GroupsSection({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const manageable = canManage(myRole);
  const [groups, setGroups] = useState<LoadState<AccountGroupDto[]>>({ kind: "loading" });
  const [name, setName] = useState("");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [renamingId, setRenamingId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const data = await accountGroupsApi.list(workspaceId);
      setGroups({ kind: "loaded", data });
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setGroups({ kind: "error", message: errorMessage(e) });
    }
  }, [workspaceId, router]);

  useEffect(() => {
    let cancelled = false;
    accountGroupsApi
      .list(workspaceId)
      .then((data) => {
        if (!cancelled) setGroups({ kind: "loaded", data });
      })
      .catch((e) => {
        if (!cancelled) setGroups({ kind: "error", message: errorMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  async function createGroup() {
    const trimmed = name.trim();
    if (!trimmed) return;
    setCreating(true);
    setError(null);
    try {
      await accountGroupsApi.create(workspaceId, trimmed);
      setName("");
      toast("success", `Group “${trimmed}” created.`);
      await reload();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setError(errorMessage(e));
    } finally {
      setCreating(false);
    }
  }

  async function renameGroup(groupId: string) {
    const trimmed = renameValue.trim();
    if (!trimmed) return;
    try {
      await accountGroupsApi.rename(workspaceId, groupId, trimmed);
      setRenamingId(null);
      toast("success", "Group renamed.");
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function deleteGroup(groupId: string) {
    try {
      await accountGroupsApi.remove(workspaceId, groupId);
      setConfirmDeleteId(null);
      toast("success", "Group deleted; its accounts are now ungrouped.");
      await reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Organization"
        title="Account groups"
        subtitle={
          manageable
            ? "Group related sending accounts (e.g. per region or client)."
            : "View groups — managing requires the campaign manager role or above."
        }
      />
      <div className="space-y-4">
        {groups.kind === "loading" && <Skeleton className="h-20" />}
        {groups.kind === "error" && <Alert variant="error">{groups.message}</Alert>}
        {groups.kind === "loaded" && (
          <>
            {groups.data.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                No groups yet{manageable ? " — create the first one below." : "."}
              </p>
            ) : (
              <ul className="divide-border divide-y" aria-label="Account groups">
                {groups.data.map((g) => (
                  <li key={g.id} className="flex items-center gap-3 py-2">
                    {renamingId === g.id ? (
                      <>
                        <Input
                          aria-label="Group name"
                          value={renameValue}
                          onChange={(e) => setRenameValue(e.target.value)}
                          className="max-w-xs"
                        />
                        <Button onClick={() => renameGroup(g.id)}>Save</Button>
                        <Button
                          variant="ghost"
                          onClick={() => setRenamingId(null)}
                          aria-label="Cancel rename"
                        >
                          <X size={14} />
                        </Button>
                      </>
                    ) : (
                      <>
                        <span className="text-foreground text-sm font-medium">{g.name}</span>
                        <Badge tone="neutral">
                          {g.accountCount} {g.accountCount === 1 ? "account" : "accounts"}
                        </Badge>
                        <span className="flex-1" />
                        {manageable && (
                          <>
                            <Button
                              variant="ghost"
                              aria-label={`Rename group ${g.name}`}
                              onClick={() => {
                                setRenamingId(g.id);
                                setRenameValue(g.name);
                              }}
                            >
                              <Pencil size={14} />
                            </Button>
                            {confirmDeleteId === g.id ? (
                              <>
                                <Button variant="danger" onClick={() => deleteGroup(g.id)}>
                                  Confirm delete
                                </Button>
                                <Button
                                  variant="ghost"
                                  aria-label="Cancel delete"
                                  onClick={() => setConfirmDeleteId(null)}
                                >
                                  <X size={14} />
                                </Button>
                              </>
                            ) : (
                              <Button
                                variant="ghost"
                                aria-label={`Delete group ${g.name}`}
                                onClick={() => setConfirmDeleteId(g.id)}
                              >
                                <Trash2 size={14} />
                              </Button>
                            )}
                          </>
                        )}
                      </>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {manageable && (
              <form
                className="flex max-w-md items-center gap-2"
                onSubmit={(e) => {
                  e.preventDefault();
                  void createGroup();
                }}
              >
                <Input
                  aria-label="New group name"
                  placeholder="e.g. EU senders"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  maxLength={80}
                />
                <Button type="submit" loading={creating} disabled={!name.trim()}>
                  <Plus size={14} /> Create group
                </Button>
              </form>
            )}
            {error && <Alert variant="error">{error}</Alert>}
          </>
        )}
      </div>
    </Card>
  );
}

// -- Accounts table --------------------------------------------------------------

function AccountsSection({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const manageable = canManage(myRole);
  const [accounts, setAccounts] = useState<LoadState<EmailAccountDto[]>>({ kind: "loading" });
  const [groupsList, setGroupsList] = useState<AccountGroupDto[]>([]);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [health, setHealth] = useState<LoadState<HealthEventDto[]>>({ kind: "loading" });
  const [testAccountId, setTestAccountId] = useState<string | null>(null);
  const [testTo, setTestTo] = useState("");
  const [testing, setTesting] = useState(false);
  const [testError, setTestError] = useState<string | null>(null);
  const [reconnecting, setReconnecting] = useState(false);

  const reload = useCallback(async () => {
    try {
      const data = await emailAccountsApi.list(workspaceId);
      setAccounts({ kind: "loaded", data });
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setAccounts({ kind: "error", message: errorMessage(e) });
    }
  }, [workspaceId, router]);

  useEffect(() => {
    let cancelled = false;
    emailAccountsApi
      .list(workspaceId)
      .then((data) => {
        if (!cancelled) setAccounts({ kind: "loaded", data });
      })
      .catch((e) => {
        if (!cancelled) setAccounts({ kind: "error", message: errorMessage(e) });
      });
    accountGroupsApi
      .list(workspaceId)
      .then((data) => {
        if (!cancelled) setGroupsList(data);
      })
      .catch(() => {
        // Group options are a convenience; the accounts list still renders.
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  useEffect(() => {
    if (!expandedId) return;
    let cancelled = false;
    emailAccountsApi
      .health(workspaceId, expandedId)
      .then((data) => {
        if (!cancelled) setHealth({ kind: "loaded", data });
      })
      .catch((e) => {
        if (!cancelled) setHealth({ kind: "error", message: errorMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [expandedId, workspaceId]);

  async function act(accountId: string, action: "pause" | "resume" | "disconnect") {
    setBusyId(accountId);
    setActionError(null);
    try {
      await emailAccountsApi[action](workspaceId, accountId);
      toast("success", `Account ${action}d.`);
      await reload();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setActionError(errorMessage(e));
    } finally {
      setBusyId(null);
    }
  }

  async function runTestSend() {
    if (!testAccountId || !testTo.trim()) return;
    setTesting(true);
    setTestError(null);
    try {
      await emailAccountsApi.test(workspaceId, testAccountId, testTo.trim());
      toast("success", `Test message to ${testTo.trim()} accepted by the provider.`);
      setTestAccountId(null);
      setTestTo("");
      await reload();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setTestError(errorMessage(e));
    } finally {
      setTesting(false);
    }
  }

  async function reconnect(providerId: string) {
    setReconnecting(true);
    setActionError(null);
    try {
      await startProviderConnect(workspaceId, providerId); // navigates away on success
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setActionError(errorMessage(e));
      setReconnecting(false);
    }
  }

  async function remove(accountId: string) {
    setBusyId(accountId);
    setActionError(null);
    try {
      await emailAccountsApi.remove(workspaceId, accountId);
      setConfirmDeleteId(null);
      toast("success", "Account removed.");
      await reload();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusyId(null);
    }
  }

  async function assignGroup(accountId: string, groupId: string | null) {
    setBusyId(accountId);
    setActionError(null);
    try {
      await emailAccountsApi.update(workspaceId, accountId, { accountGroupId: groupId });
      await reload();
    } catch (e) {
      setActionError(errorMessage(e));
    } finally {
      setBusyId(null);
    }
  }

  if (accounts.kind === "loading") return <Skeleton className="h-40" />;
  if (accounts.kind === "error") return <Alert variant="error">{accounts.message}</Alert>;

  return (
    <Card>
      <CardHeader
        eyebrow="Connections"
        title="Connected accounts"
        subtitle={
          manageable
            ? "Pause, resume, disconnect or remove accounts; assign them to groups."
            : "Read-only view — managing accounts requires the campaign manager role or above."
        }
      />
      {accounts.data.length === 0 ? (
        <EmptyState
          icon={<Mail size={22} />}
          title="No email accounts connected yet"
          description="Connect Gmail from the Providers section above. Microsoft 365 and SMTP/IMAP arrive in subphases 2.3–2.4 (prerequisites in docs/MANUAL_SETUP.md)."
        />
      ) : (
        <>
          <Table>
            <THead>
              <TR>
                <TH>Account</TH>
                <TH>Provider</TH>
                <TH>Status</TH>
                <TH>Group</TH>
                <TH>Daily cap</TH>
                <TH>Last sync</TH>
                <TH>
                  <span className="sr-only">Actions</span>
                </TH>
              </TR>
            </THead>
            <TBody>
              {accounts.data.map((a) => {
                const meta = STATUS_META[a.status] ?? {
                  label: a.status,
                  tone: "neutral" as BadgeTone,
                };
                return (
                  <TR key={a.id}>
                    <TD>
                      <div className="text-foreground text-sm font-medium">
                        {a.displayName || a.emailAddress}
                      </div>
                      {a.displayName && (
                        <div className="text-muted-foreground text-xs">{a.emailAddress}</div>
                      )}
                    </TD>
                    <TD>
                      <Badge tone="neutral">{PROVIDER_LABEL[a.provider] ?? a.provider}</Badge>
                    </TD>
                    <TD>
                      <span className="flex items-center gap-2">
                        <span
                          aria-hidden="true"
                          className={`inline-block h-2 w-2 rounded-full ${
                            meta.tone === "success"
                              ? "bg-success"
                              : meta.tone === "warning"
                                ? "bg-warning"
                                : meta.tone === "danger"
                                  ? "bg-danger"
                                  : "bg-muted-foreground"
                          }`}
                        />
                        <Badge tone={meta.tone}>{meta.label}</Badge>
                      </span>
                      {a.statusMessage && (
                        <div className="text-muted-foreground mt-1 max-w-52 text-xs">
                          {a.statusMessage}
                        </div>
                      )}
                    </TD>
                    <TD>
                      {manageable ? (
                        <select
                          aria-label={`Group for ${a.emailAddress}`}
                          value={a.accountGroupId ?? ""}
                          disabled={busyId === a.id}
                          onChange={(e) =>
                            assignGroup(a.id, e.target.value === "" ? null : e.target.value)
                          }
                          className="border-border bg-surface text-foreground rounded-lg border px-2 py-1 text-xs"
                        >
                          <option value="">No group</option>
                          {groupsList.map((g) => (
                            <option key={g.id} value={g.id}>
                              {g.name}
                            </option>
                          ))}
                        </select>
                      ) : (
                        <span className="text-muted-foreground text-sm">
                          {a.accountGroupName ?? "—"}
                        </span>
                      )}
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">
                        {a.dailySendCap ?? "Workspace default"}
                      </span>
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">
                        {formatDate(a.lastSyncedAt)}
                      </span>
                    </TD>
                    <TD>
                      <div className="flex items-center justify-end gap-1">
                        {manageable && a.status === "connected" && (
                          <Button
                            variant="ghost"
                            loading={busyId === a.id}
                            aria-label={`Pause ${a.emailAddress}`}
                            onClick={() => act(a.id, "pause")}
                          >
                            Pause
                          </Button>
                        )}
                        {manageable && a.status === "suspended" && (
                          <Button
                            variant="ghost"
                            loading={busyId === a.id}
                            aria-label={`Resume ${a.emailAddress}`}
                            onClick={() => act(a.id, "resume")}
                          >
                            Resume
                          </Button>
                        )}
                        {manageable && a.status !== "disconnected" && (
                          <Button
                            variant="ghost"
                            loading={busyId === a.id}
                            aria-label={`Disconnect ${a.emailAddress}`}
                            onClick={() => act(a.id, "disconnect")}
                          >
                            Disconnect
                          </Button>
                        )}
                        {manageable && a.provider === "gmail" && a.status === "connected" && (
                          <Button
                            variant="ghost"
                            aria-label={`Send test message from ${a.emailAddress}`}
                            onClick={() => {
                              setTestAccountId(a.id);
                              setTestTo("");
                              setTestError(null);
                            }}
                          >
                            Test send
                          </Button>
                        )}
                        {manageable &&
                          (OAUTH_PROVIDERS as readonly string[]).includes(a.provider) &&
                          (a.status === "reconnect_required" || a.status === "error") && (
                            <Button
                              variant="ghost"
                              loading={reconnecting}
                              aria-label={`Reconnect ${a.emailAddress}`}
                              onClick={() => reconnect(a.provider)}
                            >
                              Reconnect
                            </Button>
                          )}
                        {manageable &&
                          (confirmDeleteId === a.id ? (
                            <>
                              <Button
                                variant="danger"
                                loading={busyId === a.id}
                                onClick={() => remove(a.id)}
                              >
                                Confirm
                              </Button>
                              <Button
                                variant="ghost"
                                aria-label="Cancel removal"
                                onClick={() => setConfirmDeleteId(null)}
                              >
                                <X size={14} />
                              </Button>
                            </>
                          ) : (
                            <Button
                              variant="ghost"
                              aria-label={`Remove ${a.emailAddress}`}
                              onClick={() => setConfirmDeleteId(a.id)}
                            >
                              <Trash2 size={14} />
                            </Button>
                          ))}
                        <Button
                          variant="ghost"
                          aria-label={
                            expandedId === a.id
                              ? `Hide history for ${a.emailAddress}`
                              : `Show history for ${a.emailAddress}`
                          }
                          aria-expanded={expandedId === a.id}
                          onClick={() => setExpandedId(expandedId === a.id ? null : a.id)}
                        >
                          {expandedId === a.id ? (
                            <ChevronDown size={14} />
                          ) : (
                            <ChevronRight size={14} />
                          )}
                          <History size={14} />
                        </Button>
                      </div>
                    </TD>
                  </TR>
                );
              })}
            </TBody>
          </Table>
          {actionError && <Alert variant="error">{actionError}</Alert>}
          <Dialog
            open={testAccountId !== null}
            onOpenChange={(open) => {
              if (!open) setTestAccountId(null);
            }}
            title="Send a test message"
            description="Arcadence sends a short verification email from this account so you can confirm the connection works end to end."
          >
            <form
              className="space-y-3"
              onSubmit={(e) => {
                e.preventDefault();
                void runTestSend();
              }}
            >
              <Input
                aria-label="Recipient email address"
                placeholder="you@example.com"
                type="email"
                required
                value={testTo}
                onChange={(e) => setTestTo(e.target.value)}
              />
              {testError && <Alert variant="error">{testError}</Alert>}
              <div className="flex justify-end gap-2">
                <Button type="button" variant="ghost" onClick={() => setTestAccountId(null)}>
                  Cancel
                </Button>
                <Button type="submit" loading={testing} disabled={!testTo.trim()}>
                  Send test message
                </Button>
              </div>
            </form>
          </Dialog>
          {expandedId && (
            <div className="border-border bg-surface-sunken mt-3 rounded-lg border p-4">
              <h3 className="text-foreground text-sm font-semibold">Health events</h3>
              {health.kind === "loading" && <Skeleton className="mt-2 h-16" />}
              {health.kind === "error" && <Alert variant="error">{health.message}</Alert>}
              {health.kind === "loaded" && health.data.length === 0 && (
                <p className="text-muted-foreground mt-2 text-sm">
                  No health events recorded for this account yet.
                </p>
              )}
              {health.kind === "loaded" && health.data.length > 0 && (
                <ul className="mt-2 space-y-1" aria-label="Health events">
                  {health.data.map((e) => (
                    <li key={e.id} className="text-muted-foreground text-xs">
                      <span className="text-foreground font-medium">
                        {HEALTH_LABEL[e.eventType] ?? e.eventType}
                      </span>{" "}
                      · {formatDate(e.occurredAt)}
                      {e.message ? ` · ${e.message}` : ""}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </>
      )}
    </Card>
  );
}
