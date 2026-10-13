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
import {
  ApiError,
  accountGroupsApi,
  emailAccountsApi,
  emailProvidersApi,
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
      <ProvidersSection />
      <GroupsSection workspaceId={selected.id} myRole={selected.role} />
      <AccountsSection workspaceId={selected.id} myRole={selected.role} />
    </div>
  );
}

// -- Provider connection cards ------------------------------------------------

function ProvidersSection() {
  const [providers, setProviders] = useState<LoadState<EmailProviderDto[]>>({
    kind: "loading",
  });

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
              <Badge tone="warning">Setup arrives in {p.connectFlow}</Badge>
              <p className="text-muted-foreground mt-2 text-xs">
                The connect flow is built in {p.connectFlow}; provider-console prerequisites are
                listed in docs/MANUAL_SETUP.md.
              </p>
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
          description="Connect flows for Gmail, Microsoft 365 and SMTP/IMAP arrive in subphases 2.2–2.4. Provider prerequisites are documented in docs/MANUAL_SETUP.md."
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
