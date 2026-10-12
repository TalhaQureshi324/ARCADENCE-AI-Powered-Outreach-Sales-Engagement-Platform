"use client";

import { useCallback, useEffect, useState } from "react";
import { Mail, Settings as SettingsIcon, UserMinus, Users } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Avatar } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Field } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useToast } from "@/components/ui/toast";
import {
  ApiError,
  invitationsApi,
  membersApi,
  workspaceSettingsApi,
  type InvitationDto,
  type MemberDto,
} from "@/lib/api/client";
import { useWorkspaces } from "@/components/shell/workspace-context";

const ROLES = ["admin", "campaign_manager", "member", "viewer"];

function canManage(role: string): boolean {
  return role === "owner" || role === "admin";
}

type LoadState<T> =
  { kind: "loading" } | { kind: "loaded"; data: T } | { kind: "error"; message: string };

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

export function SettingsContent({ email }: { email: string }) {
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
        description="Create a workspace from the selector above to manage its members and settings."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader
          eyebrow={`Workspace · /${selected.slug}`}
          title={`${selected.name} settings`}
          subtitle={`Your role: ${selected.role}. Management actions require admin or owner.`}
        />
      </Card>
      <Tabs defaultValue="members">
        <TabsList>
          <TabsTrigger value="members">Members</TabsTrigger>
          <TabsTrigger value="invitations">Invitations</TabsTrigger>
          <TabsTrigger value="profile">Profile</TabsTrigger>
        </TabsList>
        <TabsContent value="members">
          <MembersTab workspaceId={selected.id} myRole={selected.role} />
        </TabsContent>
        <TabsContent value="invitations">
          <InvitationsTab workspaceId={selected.id} canManage={canManage(selected.role)} />
        </TabsContent>
        <TabsContent value="profile">
          <ProfileTab
            workspaceId={selected.id}
            canManage={canManage(selected.role)}
            initial={{
              name: selected.name,
              timezone: selected.timezone,
              dailySendCap: selected.dailySendCap,
            }}
          />
        </TabsContent>
      </Tabs>
    </div>
  );
}

// -- Members ---------------------------------------------------------------

function MembersTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const { toast } = useToast();
  const [state, setState] = useState<LoadState<MemberDto[]>>({ kind: "loading" });
  const [error, setError] = useState<string | null>(null);

  const manage = canManage(myRole);

  const load = useCallback(async () => {
    try {
      const members = await membersApi.list(workspaceId);
      setState({ kind: "loaded", data: members });
    } catch (e) {
      setState({ kind: "error", message: errorMessage(e) });
    }
  }, [workspaceId]);

  useEffect(() => {
    let cancelled = false;
    membersApi
      .list(workspaceId)
      .then((members) => {
        if (!cancelled) setState({ kind: "loaded", data: members });
      })
      .catch((e) => {
        if (!cancelled) setState({ kind: "error", message: errorMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  async function changeRole(userId: string, role: string) {
    setError(null);
    try {
      await membersApi.updateRole(workspaceId, userId, role);
      toast("success", "Role updated.");
      await load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function removeMember(userId: string) {
    setError(null);
    try {
      await membersApi.remove(workspaceId, userId);
      toast("success", "Member removed.");
      await load();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card className="p-5">
      {error && (
        <div className="mb-4">
          <Alert variant="error">{error}</Alert>
        </div>
      )}
      {state.kind === "loading" && <Skeleton className="h-40" />}
      {state.kind === "error" && <Alert variant="error">{state.message}</Alert>}
      {state.kind === "loaded" && (
        <Table>
          <THead>
            <tr>
              <TH>Member</TH>
              <TH>Role</TH>
              {manage && <TH>Actions</TH>}
            </tr>
          </THead>
          <TBody>
            {state.data.map((m) => (
              <TR key={m.userId}>
                <TD>
                  <div className="flex items-center gap-3">
                    <Avatar name={m.displayName ?? m.email ?? "?"} size="sm" />
                    <div>
                      <p className="font-medium">{m.displayName ?? "—"}</p>
                      <p className="text-muted-foreground text-xs">{m.email ?? m.userId}</p>
                    </div>
                  </div>
                </TD>
                <TD>
                  <Badge tone={m.role === "owner" ? "primary" : "neutral"}>{m.role}</Badge>
                </TD>
                {manage && (
                  <TD>
                    {m.role === "owner" ? (
                      <span className="text-muted-foreground text-xs">Owner is immutable</span>
                    ) : (
                      <div className="flex items-center gap-2">
                        <select
                          aria-label={`Change role for ${m.displayName ?? m.email}`}
                          defaultValue={m.role}
                          onChange={(e) => void changeRole(m.userId, e.target.value)}
                          className="border-border bg-surface rounded-lg border px-2 py-1 text-xs"
                        >
                          {ROLES.map((r) => (
                            <option key={r} value={r}>
                              {r}
                            </option>
                          ))}
                        </select>
                        <Button
                          variant="danger"
                          className="px-2 py-1 text-xs"
                          onClick={() => void removeMember(m.userId)}
                        >
                          <UserMinus size={12} /> Remove
                        </Button>
                      </div>
                    )}
                  </TD>
                )}
              </TR>
            ))}
          </TBody>
        </Table>
      )}
    </Card>
  );
}

// -- Invitations -----------------------------------------------------------

function InvitationsTab({ workspaceId, canManage }: { workspaceId: string; canManage: boolean }) {
  const { toast } = useToast();
  const [state, setState] = useState<LoadState<InvitationDto[]>>({ kind: "loading" });
  const [error, setError] = useState<string | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteRole, setInviteRole] = useState("member");
  const [inviting, setInviting] = useState(false);

  const load = useCallback(async () => {
    if (!canManage) return;
    try {
      const list = await invitationsApi.list(workspaceId);
      setState({ kind: "loaded", data: list });
    } catch (e) {
      setState({ kind: "error", message: errorMessage(e) });
    }
  }, [workspaceId, canManage]);

  useEffect(() => {
    if (!canManage) return;
    let cancelled = false;
    invitationsApi
      .list(workspaceId)
      .then((list) => {
        if (!cancelled) setState({ kind: "loaded", data: list });
      })
      .catch((e) => {
        if (!cancelled) setState({ kind: "error", message: errorMessage(e) });
      });
    return () => {
      cancelled = true;
    };
  }, [workspaceId, canManage]);

  if (!canManage) {
    return (
      <Card className="p-5">
        <Alert variant="info">Only owners and admins can manage invitations.</Alert>
      </Card>
    );
  }

  async function invite(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setInviting(true);
    try {
      const invitation = await invitationsApi.create(workspaceId, inviteEmail.trim(), inviteRole);
      setInviteEmail("");
      toast("success", `Invitation created — link: ${invitation.inviteUrl}`);
      await load();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setInviting(false);
    }
  }

  async function revoke(id: string) {
    setError(null);
    try {
      await invitationsApi.revoke(workspaceId, id);
      toast("success", "Invitation revoked.");
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <Card className="p-5">
      {error && (
        <div className="mb-4">
          <Alert variant="error">{error}</Alert>
        </div>
      )}
      <form onSubmit={invite} className="mb-5 flex flex-wrap items-end gap-3" noValidate>
        <div className="min-w-48 flex-1">
          <Field
            id="invite-email"
            label="Invite by email"
            type="email"
            placeholder="colleague@company.com"
            value={inviteEmail}
            onChange={(e) => setInviteEmail(e.target.value)}
            required
          />
        </div>
        <div className="w-40">
          <label htmlFor="invite-role" className="text-foreground mb-1.5 block text-sm font-medium">
            Role
          </label>
          <select
            id="invite-role"
            value={inviteRole}
            onChange={(e) => setInviteRole(e.target.value)}
            className="border-border bg-surface block w-full rounded-lg border px-2 py-2 text-sm"
          >
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </div>
        <Button type="submit" loading={inviting}>
          <Mail size={14} /> Invite
        </Button>
      </form>

      {state.kind === "loading" && <Skeleton className="h-32" />}
      {state.kind === "error" && <Alert variant="error">{state.message}</Alert>}
      {state.kind === "loaded" &&
        (state.data.length === 0 ? (
          <EmptyState
            icon={<Mail size={20} />}
            title="No open invitations"
            description="Invite teammates by email — they'll receive a link to join this workspace."
          />
        ) : (
          <Table>
            <THead>
              <tr>
                <TH>Email</TH>
                <TH>Role</TH>
                <TH>Expires</TH>
                <TH>Invite link</TH>
                <TH />
              </tr>
            </THead>
            <TBody>
              {state.data.map((inv) => (
                <TR key={inv.id}>
                  <TD>{inv.email}</TD>
                  <TD>
                    <Badge>{inv.role}</Badge>
                  </TD>
                  <TD className="text-muted-foreground text-xs">
                    {new Date(inv.expiresAt).toLocaleDateString()}
                  </TD>
                  <TD className="max-w-48 truncate text-xs">
                    <a href={inv.inviteUrl} className="text-primary hover:underline">
                      {inv.inviteUrl}
                    </a>
                  </TD>
                  <TD>
                    <Button
                      variant="danger"
                      className="px-2 py-1 text-xs"
                      onClick={() => void revoke(inv.id)}
                    >
                      Revoke
                    </Button>
                  </TD>
                </TR>
              ))}
            </TBody>
          </Table>
        ))}
    </Card>
  );
}

// -- Profile ---------------------------------------------------------------

function ProfileTab({
  workspaceId,
  canManage,
  initial,
}: {
  workspaceId: string;
  canManage: boolean;
  initial: { name: string; timezone: string; dailySendCap: number };
}) {
  const { reload } = useWorkspaces();
  const { toast } = useToast();
  const [name, setName] = useState(initial.name);
  const [timezone, setTimezone] = useState(initial.timezone);
  const [dailySendCap, setDailySendCap] = useState(String(initial.dailySendCap));
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string | null>>({});
  const [loading, setLoading] = useState(false);

  if (!canManage) {
    return (
      <Card className="p-5">
        <Alert variant="info">Only owners and admins can change workspace profile settings.</Alert>
      </Card>
    );
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const errors: Record<string, string | null> = {
      name: name.trim() ? null : "Workspace name is required.",
      dailySendCap:
        Number(dailySendCap) >= 1 && Number(dailySendCap) <= 10000
          ? null
          : "Daily send cap must be between 1 and 10000.",
    };
    setFieldErrors(errors);
    if (Object.values(errors).some(Boolean)) return;

    setLoading(true);
    try {
      await workspaceSettingsApi.update(workspaceId, {
        name: name.trim(),
        timezone,
        dailySendCap: Number(dailySendCap),
      });
      toast("success", "Workspace settings saved.");
      await reload();
    } catch (err) {
      setError(errorMessage(err));
      if (err instanceof ApiError && err.isUnauthorized) {
        window.location.href = "/login?expired=1";
      }
    } finally {
      setLoading(false);
    }
  }

  return (
    <Card className="p-5">
      <form onSubmit={onSubmit} className="max-w-md space-y-4" noValidate>
        {error && <Alert variant="error">{error}</Alert>}
        <Field
          id="ws-name"
          label="Workspace name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={fieldErrors.name}
          required
        />
        <Field
          id="ws-timezone"
          label="Timezone (IANA)"
          placeholder="Europe/Berlin"
          value={timezone}
          onChange={(e) => setTimezone(e.target.value)}
          hint="Used for campaign send windows."
        />
        <Field
          id="ws-cap"
          label="Daily send cap"
          type="number"
          min={1}
          max={10000}
          value={dailySendCap}
          onChange={(e) => setDailySendCap(e.target.value)}
          error={fieldErrors.dailySendCap}
          hint="Per-workspace safety limit on emails per day."
          required
        />
        <Button type="submit" loading={loading}>
          <SettingsIcon size={14} /> Save settings
        </Button>
      </form>
    </Card>
  );
}
