"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ShieldOff, Trash2, Users } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useToast } from "@/components/ui/toast";
import { ApiError, verificationApi, type SuppressionDto } from "@/lib/api/client";

type LoadState<T> =
  { kind: "loading" } | { kind: "loaded"; data: T } | { kind: "error"; message: string };

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function isUnauthorized(e: unknown): boolean {
  return e instanceof ApiError && e.isUnauthorized;
}

const REASON_META: Record<string, { tone: BadgeTone; label: string }> = {
  bounce: { tone: "danger", label: "Hard bounce" },
  complaint: { tone: "danger", label: "Spam complaint" },
  unsubscribe: { tone: "warning", label: "Unsubscribed" },
  manual: { tone: "neutral", label: "Manual" },
  invalid: { tone: "warning", label: "Invalid address" },
};

export function SuppressionsTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const editable = myRole !== "viewer";
  const canReactivate = myRole === "owner" || myRole === "admin";
  const [suppressions, setSuppressions] = useState<LoadState<SuppressionDto[]>>({
    kind: "loading",
  });
  const [email, setEmail] = useState("");
  const [reason, setReason] = useState("manual");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);

  const reload = useCallback(() => {
    verificationApi.suppressions
      .list(workspaceId)
      .then((data) => setSuppressions({ kind: "loaded", data }))
      .catch((e) => setSuppressions({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  useEffect(() => {
    reload();
  }, [reload]);

  async function create() {
    setCreating(true);
    setError(null);
    try {
      await verificationApi.suppressions.create(workspaceId, {
        email: email.trim(),
        reason,
      });
      setEmail("");
      toast("success", "Contact suppressed.");
      reload();
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

  async function remove(suppression: SuppressionDto) {
    setError(null);
    try {
      await verificationApi.suppressions.remove(workspaceId, suppression.id);
      toast("success", `${suppression.email} reactivated.`);
      setConfirmId(null);
      reload();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Deliverability"
        title="Suppression list"
        subtitle="Suppressed emails never receive campaigns and are blocked at import. Unsubscribe and complaint suppressions are permanent; removing other suppressions reactivates the contact (admin+)."
      />
      {editable && (
        <form
          className="mb-4 flex max-w-2xl flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            void create();
          }}
        >
          <Input
            aria-label="Suppress email"
            type="email"
            placeholder="email@domain.com"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <select
            aria-label="Suppression reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            <option value="manual">Manual</option>
            <option value="invalid">Invalid address</option>
            <option value="bounce">Hard bounce</option>
            <option value="unsubscribe">Unsubscribed</option>
            <option value="complaint">Spam complaint</option>
          </select>
          <Button type="submit" loading={creating} disabled={!email.trim()}>
            <ShieldOff size={14} /> Suppress
          </Button>
        </form>
      )}
      {error && <Alert variant="error">{error}</Alert>}
      {suppressions.kind === "loading" && <Skeleton className="h-32" />}
      {suppressions.kind === "error" && <Alert variant="error">{suppressions.message}</Alert>}
      {suppressions.kind === "loaded" &&
        (suppressions.data.length === 0 ? (
          <EmptyState
            icon={<Users size={22} />}
            title="No suppressed emails"
            description="Suppressed emails are blocked from all campaigns and imports. Verify contacts to auto-suppress invalid addresses."
          />
        ) : (
          <Table>
            <THead>
              <TR>
                <TH>Email</TH>
                <TH>Reason</TH>
                <TH>Source</TH>
                <TH>Added</TH>
                {editable && (
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                )}
              </TR>
            </THead>
            <TBody>
              {suppressions.data.map((s) => {
                const meta = REASON_META[s.reason] ?? {
                  tone: "neutral" as BadgeTone,
                  label: s.reason,
                };
                const permanent = s.reason === "unsubscribe" || s.reason === "complaint";
                return (
                  <TR key={s.id}>
                    <TD>
                      <span className="text-foreground text-sm font-medium">{s.email}</span>
                    </TD>
                    <TD>
                      <Badge tone={meta.tone}>{meta.label}</Badge>
                      {permanent && (
                        <span className="text-muted-foreground ml-2 text-xs">permanent</span>
                      )}
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm capitalize">{s.source}</span>
                    </TD>
                    <TD>
                      <span className="text-muted-foreground text-sm">
                        {new Date(s.createdAt).toLocaleDateString()}
                      </span>
                    </TD>
                    {editable && (
                      <TD>
                        <div className="flex items-center justify-end gap-1">
                          {confirmId === s.id ? (
                            canReactivate ? (
                              <>
                                <Button
                                  variant="danger"
                                  aria-label={`Confirm reactivation of ${s.email}`}
                                  onClick={() => remove(s)}
                                >
                                  Reactivate
                                </Button>
                                <Button
                                  variant="ghost"
                                  aria-label="Cancel reactivation"
                                  onClick={() => setConfirmId(null)}
                                >
                                  Cancel
                                </Button>
                              </>
                            ) : (
                              <span className="text-muted-foreground text-xs">
                                Admin role required to reactivate
                              </span>
                            )
                          ) : (
                            <Button
                              variant="ghost"
                              aria-label={`Remove suppression for ${s.email}`}
                              disabled={permanent}
                              title={
                                permanent
                                  ? `${s.reason} suppressions are permanent`
                                  : canReactivate
                                    ? "Remove suppression and reactivate"
                                    : "Admin role required"
                              }
                              onClick={() => setConfirmId(s.id)}
                            >
                              <Trash2 size={14} />
                            </Button>
                          )}
                        </div>
                      </TD>
                    )}
                  </TR>
                );
              })}
            </TBody>
          </Table>
        ))}
    </Card>
  );
}
