"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Download, FolderKanban, Plus, Tags, Trash2 } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useToast } from "@/components/ui/toast";
import {
  ApiError,
  segmentsApi,
  tagsApi,
  type ContactDto,
  type DataQualityDto,
  type SegmentCondition,
  type SegmentDto,
  type SegmentFilterGroup,
  type SegmentPreviewDto,
  type TagDto,
} from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function isUnauthorized(e: unknown): boolean {
  return e instanceof ApiError && e.isUnauthorized;
}

function canEdit(role: string): boolean {
  return role !== "viewer";
}

const FIELDS: { value: SegmentCondition["field"]; label: string }[] = [
  { value: "state", label: "State" },
  { value: "verification_status", label: "Verification" },
  { value: "company", label: "Company" },
  { value: "tag", label: "Tag" },
  { value: "region", label: "Region" },
  { value: "job_title", label: "Job title" },
  { value: "email", label: "Email" },
];

const OPERATORS: { value: SegmentCondition["operator"]; label: string }[] = [
  { value: "eq", label: "equals" },
  { value: "neq", label: "not equals" },
  { value: "contains", label: "contains" },
  { value: "not_contains", label: "does not contain" },
  { value: "in", label: "is any of (comma-separated)" },
  { value: "not_in", label: "is none of (comma-separated)" },
];

interface DraftCondition {
  field: SegmentCondition["field"];
  operator: SegmentCondition["operator"];
  value: string;
}

function draftToFilters(conditions: DraftCondition[], op: "and" | "or"): SegmentFilterGroup {
  return {
    op,
    conditions: conditions.map((c) => {
      if (c.operator === "in" || c.operator === "not_in") {
        return {
          field: c.field,
          operator: c.operator,
          values: c.value
            .split(",")
            .map((v) => v.trim())
            .filter(Boolean),
        };
      }
      return { field: c.field, operator: c.operator, value: c.value };
    }),
  };
}

export function SegmentsTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const editable = canEdit(myRole);

  const [segments, setSegments] = useState<SegmentDto[] | null>(null);
  const [quality, setQuality] = useState<DataQualityDto | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [preview, setPreview] = useState<{ segment: SegmentDto; result: SegmentPreviewDto } | null>(
    null,
  );
  const [tagPicker, setTagPicker] = useState<SegmentDto | null>(null);

  const load = useCallback(() => {
    segmentsApi
      .list(workspaceId)
      .then((data) => setSegments(data))
      .catch((e) => setLoadError(errorMessage(e)));
    segmentsApi
      .dataQuality(workspaceId)
      .then((dq) => setQuality(dq))
      .catch(() => setQuality(null));
  }, [workspaceId]);

  useEffect(() => {
    load();
  }, [load]);

  async function handle(fn: () => Promise<void>) {
    setActionError(null);
    try {
      await fn();
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

  return (
    <div className="space-y-4">
      {loadError && <Alert variant="error">{loadError}</Alert>}
      {actionError && <Alert variant="error">{actionError}</Alert>}

      <Card>
        <CardHeader
          eyebrow="Deliverability hygiene"
          title="Data quality"
          subtitle="Verification and completeness rollup across every contact in this workspace."
        />
        {quality === null ? (
          <div className="px-6 pb-6">
            <Skeleton className="h-16" />
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-3 px-6 pb-6 sm:grid-cols-4 lg:grid-cols-6">
            <QualityStat label="Total contacts" value={quality.total} />
            <QualityStat label="Valid" value={quality.valid} tone="success" />
            <QualityStat label="Risky" value={quality.risky} tone="warning" />
            <QualityStat label="Invalid" value={quality.invalid} tone="danger" />
            <QualityStat label="Unverified" value={quality.unknown} />
            <QualityStat label="Missing name" value={quality.missingName} tone="warning" />
          </div>
        )}
      </Card>

      <Card>
        <CardHeader
          eyebrow="Audiences"
          title="Saved segments"
          subtitle={
            editable
              ? "Reusable AND/OR filters over your contacts — preview, export or tag the matches."
              : "Read-only — creating or tagging segments requires the member role or above."
          }
        />
        <div className="px-6 pb-2">
          {editable && (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus size={14} /> New segment
            </Button>
          )}
        </div>
        {segments === null && !loadError && <Skeleton className="mx-6 mb-6 h-40" />}
        {segments !== null && segments.length === 0 && (
          <div className="px-6 pb-6">
            <EmptyState
              icon={<FolderKanban size={22} />}
              title="No saved segments"
              description="Save a filter combination — for example “Region is EU and job title contains Engineer” — to reuse it for exports and campaigns."
              action={
                editable ? (
                  <Button onClick={() => setCreateOpen(true)}>
                    <Plus size={14} /> New segment
                  </Button>
                ) : undefined
              }
            />
          </div>
        )}
        {segments !== null && segments.length > 0 && (
          <div className="px-6 pb-6">
            <Table>
              <THead>
                <TR>
                  <TH>Segment</TH>
                  <TH>Rules</TH>
                  <TH>Created</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {segments.map((s) => (
                  <TR key={s.id}>
                    <TD>
                      <div className="text-foreground text-sm font-medium">{s.name}</div>
                    </TD>
                    <TD>
                      <SegmentRules filters={s.filters} />
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">
                        {new Date(s.createdAt).toLocaleDateString()}
                      </span>
                    </TD>
                    <TD>
                      <div className="flex items-center justify-end gap-1">
                        <>
                          <Button
                            variant="ghost"
                            aria-label={`Preview ${s.name}`}
                            onClick={() =>
                              handle(async () => {
                                const result = await segmentsApi.preview(workspaceId, s.id);
                                setPreview({ segment: s, result });
                              })
                            }
                          >
                            Preview
                          </Button>
                          <Button
                            variant="ghost"
                            aria-label={`Export ${s.name}`}
                            onClick={() =>
                              handle(async () => {
                                await segmentsApi.exportCsv(
                                  workspaceId,
                                  s.id,
                                  `${s.name.replace(/[^a-z0-9-]+/gi, "-").toLowerCase()}.csv`,
                                );
                                toast("success", "Export downloaded.");
                              })
                            }
                          >
                            <Download size={14} />
                          </Button>
                          {editable && (
                            <>
                              <Button
                                variant="ghost"
                                aria-label={`Tag members of ${s.name}`}
                                onClick={() => setTagPicker(s)}
                              >
                                <Tags size={14} />
                              </Button>
                              <Button
                                variant="ghost"
                                aria-label={`Delete ${s.name}`}
                                onClick={() =>
                                  handle(async () => {
                                    await segmentsApi.remove(workspaceId, s.id);
                                    toast("success", "Segment deleted.");
                                    load();
                                  })
                                }
                              >
                                <Trash2 size={14} />
                              </Button>
                            </>
                          )}
                        </>
                      </div>
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
        )}
      </Card>

      <SegmentCreateDialog
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(name) => {
          toast("success", `Segment “${name}” saved.`);
          load();
        }}
        workspaceId={workspaceId}
      />

      <Dialog
        open={preview !== null}
        onOpenChange={(next) => {
          if (!next) setPreview(null);
        }}
        title={preview ? `Preview · ${preview.segment.name}` : ""}
        description={preview ? `${preview.result.count} contact(s) match these rules.` : undefined}
      >
        {preview && (
          <div className="max-h-80 overflow-auto">
            {preview.result.count === 0 ? (
              <EmptyState
                icon={<FolderKanban size={20} />}
                title="No matches"
                description="No contacts currently satisfy this segment's rules."
              />
            ) : (
              <Table>
                <THead>
                  <TR>
                    <TH>Email</TH>
                    <TH>Name</TH>
                    <TH>State</TH>
                  </TR>
                </THead>
                <TBody>
                  {preview.result.sample.map((c: ContactDto) => (
                    <TR key={c.id}>
                      <TD>{c.emailDisplay || c.email}</TD>
                      <TD className="text-muted-foreground">
                        {[c.firstName, c.lastName].filter(Boolean).join(" ") || "—"}
                      </TD>
                      <TD>
                        <Badge tone={c.state === "active" ? "success" : "warning"}>{c.state}</Badge>
                      </TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
            {preview.result.count > preview.result.sample.length && (
              <p className="text-muted-foreground mt-2 text-xs">
                Showing the {preview.result.sample.length} most recent of {preview.result.count}{" "}
                matches. Use Export to download all of them.
              </p>
            )}
          </div>
        )}
      </Dialog>

      <BulkTagDialog
        workspaceId={workspaceId}
        segment={tagPicker}
        onClose={() => setTagPicker(null)}
        onDone={(tagged) => {
          toast("success", `Tagged ${tagged} new contact(s).`);
          setTagPicker(null);
        }}
      />
    </div>
  );
}

function QualityStat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone?: "success" | "warning" | "danger";
}) {
  const toneClass =
    tone === "success"
      ? "text-success"
      : tone === "warning"
        ? "text-warning"
        : tone === "danger"
          ? "text-danger"
          : "text-foreground";
  return (
    <div className="border-border bg-surface rounded-lg border px-3 py-2">
      <div className={`text-lg font-semibold ${toneClass}`}>{value}</div>
      <div className="text-muted-foreground text-xs">{label}</div>
    </div>
  );
}

function SegmentRules({ filters }: { filters: SegmentFilterGroup }) {
  const leafCount = countLeaves(filters);
  return (
    <div className="flex flex-wrap items-center gap-1">
      <Badge tone="neutral">{filters.op.toUpperCase()}</Badge>
      <span className="text-muted-foreground text-xs">
        {leafCount} rule{leafCount === 1 ? "" : "s"}
      </span>
    </div>
  );
}

function countLeaves(group: SegmentFilterGroup): number {
  return group.conditions.reduce((n, c) => n + ("conditions" in c ? countLeaves(c) : 1), 0);
}

function SegmentCreateDialog({
  open,
  onClose,
  onCreated,
  workspaceId,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (name: string) => void;
  workspaceId: string;
}) {
  const [name, setName] = useState("");
  const [op, setOp] = useState<"and" | "or">("and");
  const [conditions, setConditions] = useState<DraftCondition[]>([
    { field: "email", operator: "contains", value: "" },
  ]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function close() {
    setName("");
    setOp("and");
    setConditions([{ field: "email", operator: "contains", value: "" }]);
    setError(null);
    onClose();
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const created = await segmentsApi.create(workspaceId, {
        name: name.trim(),
        filters: draftToFilters(conditions, op),
      });
      onCreated(created.name);
      close();
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  const valid =
    name.trim().length > 0 &&
    conditions.every((c) =>
      c.operator === "in" || c.operator === "not_in"
        ? c.value.trim().length > 0
        : c.value.trim().length > 0,
    );

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) close();
      }}
      title="New segment"
      description="Contacts matching all (AND) or any (OR) of these rules belong to the segment."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid && !busy) void submit();
        }}
      >
        <Input
          aria-label="Segment name"
          placeholder="Segment name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          maxLength={120}
        />
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground text-sm">Match</span>
          <select
            aria-label="Match mode"
            value={op}
            onChange={(e) => setOp(e.target.value as "and" | "or")}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            <option value="and">ALL rules (AND)</option>
            <option value="or">ANY rule (OR)</option>
          </select>
        </div>
        <div className="space-y-2">
          {conditions.map((c, i) => (
            <div key={i} className="flex flex-wrap items-center gap-2">
              <select
                aria-label={`Rule ${i + 1} field`}
                value={c.field}
                onChange={(e) =>
                  setConditions((cs) =>
                    cs.map((x, j) =>
                      j === i ? { ...x, field: e.target.value as DraftCondition["field"] } : x,
                    ),
                  )
                }
                className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
              >
                {FIELDS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
              <select
                aria-label={`Rule ${i + 1} operator`}
                value={c.operator}
                onChange={(e) =>
                  setConditions((cs) =>
                    cs.map((x, j) =>
                      j === i
                        ? { ...x, operator: e.target.value as DraftCondition["operator"] }
                        : x,
                    ),
                  )
                }
                className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
              >
                {OPERATORS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                aria-label={`Rule ${i + 1} value`}
                placeholder="Value"
                value={c.value}
                onChange={(e) =>
                  setConditions((cs) =>
                    cs.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)),
                  )
                }
                className="min-w-40 flex-1"
              />
              {conditions.length > 1 && (
                <Button
                  type="button"
                  variant="ghost"
                  aria-label={`Remove rule ${i + 1}`}
                  onClick={() => setConditions((cs) => cs.filter((_, j) => j !== i))}
                >
                  <Trash2 size={14} />
                </Button>
              )}
            </div>
          ))}
        </div>
        <Button
          type="button"
          variant="secondary"
          onClick={() =>
            setConditions((cs) => [...cs, { field: "region", operator: "eq", value: "" }])
          }
        >
          <Plus size={14} /> Add rule
        </Button>
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button type="submit" disabled={!valid || busy}>
            {busy ? "Saving…" : "Save segment"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function BulkTagDialog({
  workspaceId,
  segment,
  onClose,
  onDone,
}: {
  workspaceId: string;
  segment: SegmentDto | null;
  onClose: () => void;
  onDone: (tagged: number) => void;
}) {
  const router = useRouter();
  const { toast } = useToast();
  const [tags, setTags] = useState<TagDto[] | null>(null);
  const [tagId, setTagId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (segment === null) return;
    let cancelled = false;
    Promise.resolve().then(() => {
      if (!cancelled) {
        setTagId("");
        setError(null);
      }
    });
    tagsApi
      .list(workspaceId)
      .then((t) => {
        if (!cancelled) setTags(t);
      })
      .catch((e) => {
        if (!cancelled) setError(errorMessage(e));
      });
    return () => {
      cancelled = true;
    };
  }, [segment, workspaceId]);

  async function apply() {
    if (segment === null || !tagId) return;
    setBusy(true);
    setError(null);
    try {
      const result = await segmentsApi.bulkTag(workspaceId, segment.id, tagId);
      onDone(result.tagged);
    } catch (e) {
      if (e instanceof ApiError && e.isUnauthorized) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setError(errorMessage(e));
      toast("error", errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog
      open={segment !== null}
      onOpenChange={(next) => {
        if (!next) onClose();
      }}
      title={segment ? `Tag members · ${segment.name}` : ""}
      description="Adds the tag to every matching contact. Contacts that already have it are skipped."
    >
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (tagId && !busy) void apply();
        }}
      >
        {tags === null ? (
          <Skeleton className="h-10" />
        ) : tags.length === 0 ? (
          <Alert variant="info">No tags yet — create one on the Tags &amp; Fields tab first.</Alert>
        ) : (
          <select
            aria-label="Tag to apply"
            value={tagId}
            onChange={(e) => setTagId(e.target.value)}
            className="border-border bg-surface text-foreground w-full rounded-lg border px-2 py-2 text-sm"
          >
            <option value="">Choose a tag…</option>
            {tags.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        )}
        {error && <Alert variant="error">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" disabled={!tagId || busy || tags?.length === 0}>
            {busy ? "Applying…" : "Apply tag"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
