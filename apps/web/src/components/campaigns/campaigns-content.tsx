"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Copy, Pencil, Plus, Rocket, Trash2 } from "lucide-react";
import { ComposerDialog, type ComposerTarget } from "@/components/campaigns/composer-dialog";
import { SequenceBuilder } from "@/components/campaigns/sequence-builder";

import { Alert } from "@/components/ui/alert";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import {
  ApiError,
  campaignsApi,
  emailAccountsApi,
  leadListsApi,
  type CampaignDetailDto,
  type CampaignDto,
  type CampaignStepInput,
  type EmailAccountDto,
  type LeadListDto,
} from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function isUnauthorized(e: unknown): boolean {
  return e instanceof ApiError && e.isUnauthorized;
}

function canManage(role: string): boolean {
  return role === "owner" || role === "admin" || role === "campaign_manager";
}

const STATUS_META: Record<string, { label: string; tone: BadgeTone }> = {
  draft: { label: "Draft", tone: "neutral" },
  active: { label: "Active", tone: "success" },
  paused: { label: "Paused", tone: "warning" },
  completed: { label: "Completed", tone: "primary" },
  cancelled: { label: "Cancelled", tone: "danger" },
  archived: { label: "Archived", tone: "neutral" },
};

export function CampaignsListContent() {
  const router = useRouter();
  const { toast } = useToast();
  const { workspaces, selected } = useWorkspaces();
  const manageable = selected ? canManage(selected.role) : false;

  const [campaigns, setCampaigns] = useState<CampaignDto[] | null>(null);
  const [statusFilter, setStatusFilter] = useState("");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const load = useCallback(
    (status: string) => {
      campaignsApi
        .list(selected?.id ?? "", status || undefined)
        .then(setCampaigns)
        .catch((e) => setLoadError(errorMessage(e)));
    },
    [selected?.id],
  );

  useEffect(() => {
    if (!selected) return;
    Promise.resolve().then(() => {
      setCampaigns(null);
      setLoadError(null);
    });
    load(statusFilter);
  }, [load, statusFilter, selected]);

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
        icon={<Rocket size={22} />}
        title="No workspace selected"
        description="Create a workspace from the selector above to build campaigns."
      />
    );
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader
          eyebrow={`Workspace · /${selected.slug}`}
          title="Campaigns"
          subtitle="Sequences of emails and waits, sent to a lead list through your connected mailboxes."
        />
      </Card>

      <Card>
        <CardHeader
          eyebrow="Outbound"
          title="All campaigns"
          subtitle={
            manageable
              ? "Drafts are fully editable. Activation locks the sequence so running enrollments stay consistent."
              : "Read-only — managing campaigns requires the campaign manager role or above."
          }
        />
        <div className="mb-4 flex flex-wrap items-center gap-2 px-6">
          <select
            aria-label="Filter by status"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            <option value="">All statuses</option>
            <option value="draft">Draft</option>
            <option value="active">Active</option>
            <option value="paused">Paused</option>
            <option value="completed">Completed</option>
            <option value="cancelled">Cancelled</option>
            <option value="archived">Archived</option>
          </select>
          {manageable && (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus size={14} /> New campaign
            </Button>
          )}
        </div>
        {loadError && <Alert variant="error">{loadError}</Alert>}
        {campaigns === null && !loadError && <Skeleton className="mx-6 mb-6 h-40" />}
        {campaigns !== null && campaigns.length === 0 && (
          <div className="px-6 pb-6">
            <EmptyState
              icon={<Rocket size={22} />}
              title="No campaigns yet"
              description="Create a campaign, give it a sequence of emails and waits, then activate it when the preflight is clean."
              action={
                manageable ? (
                  <Button onClick={() => setCreateOpen(true)}>
                    <Plus size={14} /> New campaign
                  </Button>
                ) : undefined
              }
            />
          </div>
        )}
        {campaigns !== null && campaigns.length > 0 && (
          <div className="px-6 pb-6">
            <Table>
              <THead>
                <TR>
                  <TH>Campaign</TH>
                  <TH>Status</TH>
                  <TH>Steps</TH>
                  <TH>Timezone</TH>
                  <TH>Created</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {campaigns.map((c) => {
                  const meta = STATUS_META[c.status] ?? {
                    label: c.status,
                    tone: "neutral" as BadgeTone,
                  };
                  return (
                    <TR key={c.id}>
                      <TD>
                        <Link
                          href={`/app/campaigns/${c.id}`}
                          className="text-foreground text-sm font-medium hover:underline"
                        >
                          {c.name}
                        </Link>
                        {c.description && (
                          <div className="text-muted-foreground max-w-md truncate text-xs">
                            {c.description}
                          </div>
                        )}
                      </TD>
                      <TD>
                        <Badge tone={meta.tone}>{meta.label}</Badge>
                      </TD>
                      <TD>
                        <span className="text-muted-foreground text-sm">{c.stepCount}</span>
                      </TD>
                      <TD>
                        <span className="text-muted-foreground text-sm">{c.timezone}</span>
                      </TD>
                      <TD>
                        <span className="text-muted-foreground text-sm">
                          {new Date(c.createdAt).toLocaleDateString()}
                        </span>
                      </TD>
                      <TD>
                        {manageable && (
                          <div className="flex items-center justify-end gap-1">
                            <Button
                              variant="ghost"
                              aria-label={`Duplicate ${c.name}`}
                              onClick={async () => {
                                try {
                                  await campaignsApi.duplicate(selected.id, c.id);
                                  toast("success", "Campaign duplicated as a draft.");
                                  load(statusFilter);
                                } catch (e) {
                                  toast("error", errorMessage(e));
                                }
                              }}
                            >
                              <Copy size={14} />
                            </Button>
                            {c.status === "draft" && (
                              <Button
                                variant="ghost"
                                aria-label={`Delete ${c.name}`}
                                onClick={async () => {
                                  try {
                                    await campaignsApi.remove(selected.id, c.id);
                                    toast("success", "Draft deleted.");
                                    load(statusFilter);
                                  } catch (e) {
                                    toast("error", errorMessage(e));
                                  }
                                }}
                              >
                                <Trash2 size={14} />
                              </Button>
                            )}
                          </div>
                        )}
                      </TD>
                    </TR>
                  );
                })}
              </TBody>
            </Table>
          </div>
        )}
      </Card>

      <CreateCampaignDialog
        open={createOpen}
        workspaceId={selected.id}
        onClose={() => setCreateOpen(false)}
        onCreated={(name, id) => {
          toast("success", `Campaign “${name}” created as a draft.`);
          router.push(`/app/campaigns/${id}`);
        }}
      />
    </div>
  );
}

function CreateCampaignDialog({
  open,
  workspaceId,
  onClose,
  onCreated,
}: {
  open: boolean;
  workspaceId: string;
  onClose: () => void;
  onCreated: (name: string, id: string) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function close() {
    setName("");
    setDescription("");
    setError(null);
    onClose();
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const created = await campaignsApi.create(workspaceId, {
        name: name.trim(),
        description: description.trim() || undefined,
      });
      onCreated(created.name, created.id);
      close();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) close();
      }}
      title="New campaign"
      description="Campaigns start as drafts — add steps, an audience and mailboxes before activating."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim() && !busy) void submit();
        }}
      >
        <Input
          aria-label="Campaign name"
          placeholder="Campaign name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={140}
        />
        <Input
          aria-label="Campaign description"
          placeholder="Description (optional)"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={500}
        />
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button type="submit" disabled={!name.trim() || busy}>
            {busy ? "Creating…" : "Create campaign"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

// -- Detail ---------------------------------------------------------------------

export function CampaignDetailContent({ campaignId }: { campaignId: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const { selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";
  const manageable = selected ? canManage(selected.role) : false;

  const [detail, setDetail] = useState<CampaignDetailDto | null>(null);
  const [lists, setLists] = useState<LeadListDto[]>([]);
  const [mailboxes, setMailboxes] = useState<EmailAccountDto[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [addStepOpen, setAddStepOpen] = useState(false);
  const [addAudienceOpen, setAddAudienceOpen] = useState(false);
  const [addMailboxOpen, setAddMailboxOpen] = useState(false);
  const [composeTarget, setComposeTarget] = useState<ComposerTarget | null>(null);
  const [builderView, setBuilderView] = useState<"visual" | "list">("visual");

  const load = useCallback(() => {
    if (!workspaceId) return;
    campaignsApi
      .get(workspaceId, campaignId)
      .then(setDetail)
      .catch((e) => setLoadError(errorMessage(e)));
    leadListsApi
      .list(workspaceId)
      .then(setLists)
      .catch(() => setLists([]));
    emailAccountsApi
      .list(workspaceId)
      .then(setMailboxes)
      .catch(() => setMailboxes([]));
  }, [workspaceId, campaignId]);

  useEffect(() => {
    load();
  }, [load]);

  async function run(fn: () => Promise<void>, successMessage?: string) {
    setActionError(null);
    try {
      await fn();
      if (successMessage) toast("success", successMessage);
      load();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setActionError(errorMessage(e));
    }
  }

  if (loadError) {
    return (
      <div className="space-y-4">
        <Alert variant="error">{loadError}</Alert>
        <Button variant="ghost" onClick={() => router.push("/app/campaigns")}>
          <ArrowLeft size={14} /> Back to campaigns
        </Button>
      </div>
    );
  }
  if (!detail) return <Skeleton className="h-64" />;

  const c = detail.campaign;
  const meta = STATUS_META[c.status] ?? { label: c.status, tone: "neutral" as BadgeTone };
  const editable = c.status === "draft";
  const structuralHint = editable
    ? undefined
    : "The sequence, audience and mailboxes are locked while the campaign is not a draft — duplicate it to iterate.";

  const transitions: {
    action: string;
    label: string;
    variant: "primary" | "secondary" | "ghost";
  }[] = [];
  if (c.status === "draft")
    transitions.push({ action: "activate", label: "Activate", variant: "primary" });
  if (c.status === "active") {
    transitions.push({ action: "pause", label: "Pause", variant: "secondary" });
    transitions.push({ action: "complete", label: "Complete", variant: "ghost" });
    transitions.push({ action: "cancel", label: "Cancel", variant: "ghost" });
  }
  if (c.status === "paused") {
    transitions.push({ action: "resume", label: "Resume", variant: "primary" });
    transitions.push({ action: "cancel", label: "Cancel", variant: "ghost" });
  }
  if (c.status === "completed" || c.status === "cancelled" || c.status === "draft")
    transitions.push({ action: "archive", label: "Archive", variant: "ghost" });

  const listName = (id: string | null) =>
    id ? (lists.find((l) => l.id === id)?.name ?? "list") : null;

  async function addStep(step: CampaignStepInput) {
    await campaignsApi.update(workspaceId, campaignId, {
      steps: [
        ...detail!.steps.map((s) => ({
          type: s.type,
          name: s.name,
          waitDays: s.waitDays ?? undefined,
          variants: s.variants,
        })),
        step,
      ],
    });
    setAddStepOpen(false);
    toast("success", "Step added.");
    load();
  }

  return (
    <div className="space-y-6">
      <Button variant="ghost" onClick={() => router.push("/app/campaigns")}>
        <ArrowLeft size={14} /> All campaigns
      </Button>

      <Card>
        <CardHeader
          eyebrow={`Campaign · ${c.status}`}
          title={c.name}
          subtitle={c.description || undefined}
        />
        <div className="flex flex-wrap items-center gap-2 px-6 pb-2">
          <Badge tone={meta.tone}>{meta.label}</Badge>
          <span className="text-muted-foreground text-sm">
            {c.stepCount} step{c.stepCount === 1 ? "" : "s"} · {c.timezone} · {c.dailyCap}/day ·
            created {new Date(c.createdAt).toLocaleDateString()}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-2 px-6 pb-6">
          {actionError && <Alert variant="error">{actionError}</Alert>}
          {manageable &&
            transitions.map((t) => (
              <Button
                key={t.action}
                variant={t.variant}
                aria-label={`${t.label} campaign`}
                onClick={() =>
                  run(
                    async () => {
                      await campaignsApi.transition(workspaceId, c.id, t.action);
                    },
                    {
                      activate: "Campaign activated.",
                      pause: "Campaign paused.",
                      resume: "Campaign resumed.",
                      complete: "Campaign completed.",
                      cancel: "Campaign cancelled.",
                      archive: "Campaign archived.",
                    }[t.action] ?? "Campaign updated.",
                  )
                }
              >
                {t.label}
              </Button>
            ))}
          {manageable && (
            <Button variant="ghost" onClick={() => setEditOpen(true)}>
              Edit details
            </Button>
          )}
          {manageable && (
            <Button
              variant="ghost"
              onClick={() =>
                run(async () => {
                  const copy = await campaignsApi.duplicate(workspaceId, c.id);
                  router.push(`/app/campaigns/${copy.campaign.id}`);
                }, "Duplicated as a new draft.")
              }
            >
              <Copy size={14} /> Duplicate
            </Button>
          )}
          {manageable && c.status === "draft" && (
            <Button
              variant="ghost"
              onClick={() =>
                run(async () => {
                  await campaignsApi.remove(workspaceId, c.id);
                  router.push("/app/campaigns");
                }, "Draft deleted.")
              }
            >
              <Trash2 size={14} /> Delete draft
            </Button>
          )}
        </div>
      </Card>

      <Card>
        <CardHeader
          eyebrow="Sequence"
          title="Steps"
          subtitle={
            structuralHint ??
            "Drag cards to build the flow. Emails personalize per lead; waits schedule; branches route on lead data."
          }
        />
        {manageable && editable && (
          <div className="flex gap-1 px-6 pb-2">
            <Button
              variant={builderView === "visual" ? "primary" : "ghost"}
              onClick={() => setBuilderView("visual")}
            >
              Visual
            </Button>
            <Button
              variant={builderView === "list" ? "primary" : "ghost"}
              onClick={() => setBuilderView("list")}
            >
              Chronological
            </Button>
          </div>
        )}
        {manageable && editable && builderView === "visual" && (
          <div className="px-6 pb-6">
            <SequenceBuilder
              workspaceId={workspaceId}
              campaignId={campaignId}
              detail={detail}
              onSaved={() => {
                toast("success", "Sequence saved.");
                load();
              }}
            />
          </div>
        )}
        {!(manageable && editable && builderView === "visual") && (
          <>
            {detail.steps.length === 0 ? (
              <div className="px-6 pb-6">
                <EmptyState
                  icon={<Rocket size={20} />}
                  title="No steps yet"
                  description="Add an email step to start the sequence — waits go between emails."
                />
              </div>
            ) : (
              <div className="px-6 pb-6">
                <Table>
                  <THead>
                    <TR>
                      <TH>#</TH>
                      <TH>Type</TH>
                      <TH>Name</TH>
                      <TH>Wait</TH>
                      <TH>Variants</TH>
                      <TH>
                        <span className="sr-only">Actions</span>
                      </TH>
                    </TR>
                  </THead>
                  <TBody>
                    {detail.steps.map((s) => (
                      <TR key={s.id}>
                        <TD>{s.position}</TD>
                        <TD>
                          <Badge tone={s.type === "email" ? "primary" : "neutral"}>{s.type}</Badge>
                        </TD>
                        <TD>{s.name || "—"}</TD>
                        <TD>{s.waitDays !== null ? `${s.waitDays} day(s)` : "—"}</TD>
                        <TD>
                          <span className="text-muted-foreground text-sm">
                            {s.variants
                              .map((v) => `${v.variantKey}: ${v.subject || "(no subject)"}`)
                              .join(" · ") || "—"}
                          </span>
                        </TD>
                        <TD>
                          {manageable && editable && s.type === "email" && (
                            <Button
                              variant="ghost"
                              aria-label={`Compose step ${s.position}`}
                              onClick={() => {
                                const variant = s.variants[0];
                                if (variant)
                                  setComposeTarget({
                                    stepId: s.id,
                                    stepName: s.name || `Step ${s.position}`,
                                    variantKey: variant.variantKey,
                                    variant,
                                  });
                              }}
                            >
                              <Pencil size={14} /> Compose
                            </Button>
                          )}
                          {manageable && editable && (
                            <Button
                              variant="ghost"
                              aria-label={`Remove step ${s.position}`}
                              onClick={() =>
                                run(async () => {
                                  await campaignsApi.update(workspaceId, campaignId, {
                                    steps: detail.steps
                                      .filter((x) => x.id !== s.id)
                                      .map((x) => ({
                                        type: x.type,
                                        name: x.name,
                                        waitDays: x.waitDays ?? undefined,
                                        variants: x.variants,
                                      })),
                                  });
                                  toast("success", "Step removed.");
                                })
                              }
                            >
                              <Trash2 size={14} />
                            </Button>
                          )}
                        </TD>
                      </TR>
                    ))}
                  </TBody>
                </Table>
              </div>
            )}
            {manageable && editable && (
              <div className="px-6 pb-6">
                <Button onClick={() => setAddStepOpen(true)}>
                  <Plus size={14} /> Add step
                </Button>
              </div>
            )}
          </>
        )}
      </Card>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader
            eyebrow="Who receives it"
            title="Audience"
            subtitle="Lead lists or saved segments. Everyone enrolled keeps the campaign definition they started with."
          />
          {detail.audiences.length === 0 ? (
            <div className="text-muted-foreground px-6 pb-4 text-sm">No audience assigned yet.</div>
          ) : (
            <div className="space-y-2 px-6 pb-4">
              {detail.audiences.map((a) => (
                <div key={a.id} className="flex items-center justify-between gap-2">
                  <span className="text-foreground text-sm">
                    {a.leadListId
                      ? `List · ${listName(a.leadListId)}`
                      : `Segment · ${a.segmentId?.slice(0, 8)}…`}
                  </span>
                  {manageable && editable && (
                    <Button
                      variant="ghost"
                      aria-label="Remove audience"
                      onClick={() =>
                        run(async () => {
                          await campaignsApi.update(workspaceId, campaignId, {
                            audiences: detail.audiences
                              .filter((x) => x.id !== a.id)
                              .map((x) =>
                                x.leadListId
                                  ? { leadListId: x.leadListId }
                                  : { segmentId: x.segmentId },
                              ),
                          });
                          toast("success", "Audience removed.");
                        })
                      }
                    >
                      <Trash2 size={14} />
                    </Button>
                  )}
                </div>
              ))}
            </div>
          )}
          {manageable && editable && (
            <div className="px-6 pb-6">
              <Button variant="secondary" onClick={() => setAddAudienceOpen(true)}>
                <Plus size={14} /> Add audience
              </Button>
            </div>
          )}
        </Card>

        <Card>
          <CardHeader
            eyebrow="Who sends it"
            title="Mailboxes"
            subtitle="Connected sending accounts rotate through this campaign's sends."
          />
          {detail.mailboxIds.length === 0 ? (
            <div className="text-muted-foreground px-6 pb-4 text-sm">
              No mailboxes assigned yet — activation needs at least one.
            </div>
          ) : (
            <div className="space-y-2 px-6 pb-4">
              {detail.mailboxIds.map((id) => {
                const box = mailboxes.find((m) => m.id === id);
                return (
                  <div key={id} className="flex items-center justify-between gap-2">
                    <span className="text-foreground text-sm">
                      {box ? `${box.emailAddress} (${box.provider})` : `${id.slice(0, 8)}…`}
                    </span>
                    {manageable && editable && (
                      <Button
                        variant="ghost"
                        aria-label="Remove mailbox"
                        onClick={() =>
                          run(async () => {
                            await campaignsApi.update(workspaceId, campaignId, {
                              mailboxIds: detail.mailboxIds.filter((x) => x !== id),
                            });
                            toast("success", "Mailbox removed.");
                          })
                        }
                      >
                        <Trash2 size={14} />
                      </Button>
                    )}
                  </div>
                );
              })}
            </div>
          )}
          {manageable && editable && (
            <div className="px-6 pb-6">
              <Button variant="secondary" onClick={() => setAddMailboxOpen(true)}>
                <Plus size={14} /> Add mailbox
              </Button>
            </div>
          )}
        </Card>
      </div>

      <EditDetailsDialog
        open={editOpen}
        campaign={c}
        onClose={() => setEditOpen(false)}
        onSaved={() => {
          setEditOpen(false);
          toast("success", "Campaign updated.");
          load();
        }}
        workspaceId={workspaceId}
        campaignId={campaignId}
      />

      <AddStepDialog
        open={addStepOpen}
        onClose={() => setAddStepOpen(false)}
        onAdd={addStep}
        busy={false}
      />

      <AddAudienceDialog
        open={addAudienceOpen}
        lists={lists}
        onClose={() => setAddAudienceOpen(false)}
        onAdd={(listId) =>
          run(async () => {
            await campaignsApi.update(workspaceId, campaignId, {
              audiences: [
                ...detail.audiences.map((a) =>
                  a.leadListId ? { leadListId: a.leadListId } : { segmentId: a.segmentId },
                ),
                { leadListId: listId },
              ],
            });
            setAddAudienceOpen(false);
            toast("success", "Audience added.");
          })
        }
      />

      <ComposerDialog
        open={composeTarget !== null}
        workspaceId={workspaceId}
        campaignId={campaignId}
        target={composeTarget}
        onClose={() => setComposeTarget(null)}
      />

      <AddMailboxDialog
        open={addMailboxOpen}
        mailboxes={mailboxes}
        assigned={detail.mailboxIds}
        onClose={() => setAddMailboxOpen(false)}
        onAdd={(accountId) =>
          run(async () => {
            await campaignsApi.update(workspaceId, campaignId, {
              mailboxIds: [...detail.mailboxIds, accountId],
            });
            setAddMailboxOpen(false);
            toast("success", "Mailbox assigned.");
          })
        }
      />
    </div>
  );
}

function EditDetailsDialog({
  open,
  campaign,
  onClose,
  onSaved,
  workspaceId,
  campaignId,
}: {
  open: boolean;
  campaign: CampaignDto;
  onClose: () => void;
  onSaved: () => void;
  workspaceId: string;
  campaignId: string;
}) {
  const [name, setName] = useState(campaign.name);
  const [description, setDescription] = useState(campaign.description);
  const [timezone, setTimezone] = useState(campaign.timezone);
  const [dailyCap, setDailyCap] = useState(String(campaign.dailyCap));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      Promise.resolve().then(() => {
        setName(campaign.name);
        setDescription(campaign.description);
        setTimezone(campaign.timezone);
        setDailyCap(String(campaign.dailyCap));
        setError(null);
      });
    }
  }, [open, campaign]);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      await campaignsApi.update(workspaceId, campaignId, {
        name: name.trim(),
        description: description.trim(),
        timezone: timezone.trim(),
        dailyCap: Number(dailyCap) || undefined,
      });
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="Edit campaign details"
      description="Name and timezone are locked while the campaign is active or paused."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (name.trim() && !busy) void submit();
        }}
      >
        <Input
          aria-label="Campaign name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={140}
        />
        <Input
          aria-label="Campaign description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          maxLength={500}
        />
        <Input
          aria-label="Campaign timezone"
          value={timezone}
          onChange={(e) => setTimezone(e.target.value)}
          placeholder="Etc/UTC"
        />
        <Input
          aria-label="Daily send cap"
          value={dailyCap}
          onChange={(e) => setDailyCap(e.target.value)}
          inputMode="numeric"
        />
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={!name.trim() || busy}>
            {busy ? "Saving…" : "Save"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function AddStepDialog({
  open,
  onClose,
  onAdd,
}: {
  open: boolean;
  onClose: () => void;
  onAdd: (step: CampaignStepInput) => Promise<void>;
  busy: boolean;
}) {
  const [kind, setKind] = useState<"email" | "wait">("email");
  const [name, setName] = useState("");
  const [subject, setSubject] = useState("");
  const [preheader, setPreheader] = useState("");
  const [body, setBody] = useState("");
  const [waitDays, setWaitDays] = useState("1");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      Promise.resolve().then(() => {
        setKind("email");
        setName("");
        setSubject("");
        setPreheader("");
        setBody("");
        setWaitDays("1");
        setError(null);
      });
    }
  }, [open]);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      if (kind === "email") {
        await onAdd({
          type: "email",
          name: name.trim(),
          variants: [
            {
              variantKey: "A",
              subject: subject.trim(),
              preheader: preheader.trim(),
              bodyHtml: `<p>${body.trim().replace(/\n/g, "<br/>")}</p>`,
              bodyText: body.trim(),
            },
          ],
        });
      } else {
        await onAdd({ type: "wait", waitDays: Math.max(0, Number(waitDays) || 0) });
      }
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const valid = kind === "email" ? subject.trim().length > 0 : waitDays !== "";

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="Add step"
      description="Personalization tokens and A/Z variants arrive in the composer subphase."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid && !busy) void submit();
        }}
      >
        <select
          aria-label="Step type"
          value={kind}
          onChange={(e) => setKind(e.target.value as "email" | "wait")}
          className="border-border bg-surface text-foreground w-full rounded-lg border px-2 py-2 text-sm"
        >
          <option value="email">Email</option>
          <option value="wait">Wait</option>
        </select>
        {kind === "email" ? (
          <>
            <Input
              aria-label="Step name"
              placeholder="Step name (optional)"
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={120}
            />
            <Input
              aria-label="Subject"
              placeholder="Subject"
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
              maxLength={200}
            />
            <Input
              aria-label="Preheader"
              placeholder="Preheader (optional)"
              value={preheader}
              onChange={(e) => setPreheader(e.target.value)}
              maxLength={200}
            />
            <textarea
              aria-label="Body text"
              placeholder="Plain-text body"
              value={body}
              onChange={(e) => setBody(e.target.value)}
              rows={5}
              className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 text-sm"
            />
          </>
        ) : (
          <Input
            aria-label="Wait days"
            value={waitDays}
            onChange={(e) => setWaitDays(e.target.value)}
            inputMode="numeric"
            placeholder="Days to wait"
          />
        )}
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={!valid || busy}>
            {busy ? "Adding…" : "Add step"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function AddAudienceDialog({
  open,
  lists,
  onClose,
  onAdd,
}: {
  open: boolean;
  lists: LeadListDto[];
  onClose: () => void;
  onAdd: (listId: string) => Promise<void>;
}) {
  const [listId, setListId] = useState("");

  useEffect(() => {
    if (open) Promise.resolve().then(() => setListId(""));
  }, [open]);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="Add audience"
      description="Pick a lead list. Segment audiences arrive with the visual builder subphase."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (listId) void onAdd(listId);
        }}
      >
        {lists.length === 0 ? (
          <Alert variant="info">No lead lists yet — create one on the Leads page first.</Alert>
        ) : (
          <select
            aria-label="Lead list"
            value={listId}
            onChange={(e) => setListId(e.target.value)}
            className="border-border bg-surface text-foreground w-full rounded-lg border px-2 py-2 text-sm"
          >
            <option value="">Choose a list…</option>
            {lists.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={!listId || lists.length === 0}>
            Add audience
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function AddMailboxDialog({
  open,
  mailboxes,
  assigned,
  onClose,
  onAdd,
}: {
  open: boolean;
  mailboxes: EmailAccountDto[];
  assigned: string[];
  onClose: () => void;
  onAdd: (accountId: string) => Promise<void>;
}) {
  const [accountId, setAccountId] = useState("");
  const available = mailboxes.filter((m) => !assigned.includes(m.id));

  useEffect(() => {
    if (open) Promise.resolve().then(() => setAccountId(""));
  }, [open]);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title="Add mailbox"
      description="Only connected sending accounts from this workspace can be assigned."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (accountId) void onAdd(accountId);
        }}
      >
        {available.length === 0 ? (
          <Alert variant="info">
            No unassigned mailboxes — connect one on the Email Accounts page.
          </Alert>
        ) : (
          <select
            aria-label="Sending account"
            value={accountId}
            onChange={(e) => setAccountId(e.target.value)}
            className="border-border bg-surface text-foreground w-full rounded-lg border px-2 py-2 text-sm"
          >
            <option value="">Choose a mailbox…</option>
            {available.map((m) => (
              <option key={m.id} value={m.id}>
                {m.emailAddress} ({m.provider})
              </option>
            ))}
          </select>
        )}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={!accountId || available.length === 0}>
            Add mailbox
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
