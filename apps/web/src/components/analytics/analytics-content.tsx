"use client";

import { useCallback, useEffect, useState } from "react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useWorkspaces } from "@/components/shell/workspace-context";
import { ApiError } from "@/lib/api/client";

type Summary = Record<string, number | string>;
type Row = Record<string, string | number>;

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function Kpi({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <div className="border-border bg-surface rounded-lg border px-4 py-3" title={hint}>
      <div className="text-foreground text-xl font-semibold">{value}</div>
      <div className="text-muted-foreground text-xs">{label}</div>
    </div>
  );
}

/** Simple CSS bar chart (no chart lib): one row per day, scaled to max. */
function Bars({ data, field }: { data: Row[]; field: string }) {
  const max = Math.max(1, ...data.map((d) => Number(d[field] ?? 0)));
  if (data.length === 0) return <div className="text-muted-foreground text-sm">No data in range.</div>;
  return (
    <div className="space-y-1">
      {data.map((d) => (
        <div key={String(d.day)} className="flex items-center gap-2 text-xs">
          <span className="text-muted-foreground w-20 shrink-0">{d.day}</span>
          <div className="bg-primary/20 h-4 flex-1 rounded" aria-hidden>
            <div
              className="bg-primary h-4 rounded"
              style={{ width: `${(Number(d[field] ?? 0) / max) * 100}%` }}
              role="img"
              aria-label={`${d.day}: ${d[field]}`}
            />
          </div>
          <span className="w-10 text-right">{d[field]}</span>
        </div>
      ))}
    </div>
  );
}

export function AnalyticsContent() {
  const { workspaces, selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [summary, setSummary] = useState<Summary | null>(null);
  const [series, setSeries] = useState<Row[]>([]);
  const [campaigns, setCampaigns] = useState<Row[]>([]);
  const [mailboxes, setMailboxes] = useState<Row[]>([]);
  const [warnings, setWarnings] = useState<Row[]>([]);
  const [error, setError] = useState<string | null>(null);

  const qs = () => {
    const p = new URLSearchParams();
    if (from) p.set("from", from);
    if (to) p.set("to", to);
    const s = p.toString();
    return s ? `?${s}` : "";
  };

  const load = useCallback(() => {
    if (!workspaceId) return;
    const suffix = qs();
    fetch(`/api/analytics?ws=${workspaceId}${suffix}`).catch(() => undefined);
    import("@/lib/api/client").then(({ analyticsApi }) => {
      analyticsApi
        .summary(workspaceId, from || undefined, to || undefined)
        .then(setSummary)
        .catch((e) => setError(errorMessage(e)));
      analyticsApi
        .timeseries(workspaceId, from || undefined, to || undefined)
        .then((arr) => setSeries(arr as unknown as Row[]))
        .catch(() => setSeries([]));
      analyticsApi
        .campaigns(workspaceId, from || undefined, to || undefined)
        .then((arr) => setCampaigns(arr as unknown as Row[]))
        .catch(() => setCampaigns([]));
      analyticsApi
        .mailboxes(workspaceId, from || undefined, to || undefined)
        .then((arr) => setMailboxes(arr as unknown as Row[]))
        .catch(() => setMailboxes([]));
      analyticsApi
        .anomalies(workspaceId)
        .then((r) => setWarnings((r.warnings as unknown as Row[]) ?? []))
        .catch(() => setWarnings([]));
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId, from, to]);

  useEffect(() => {
    Promise.resolve().then(load);
  }, [load]);

  function exportCsv() {
    void (async () => {
      const { accessToken: token, API_BASE_URL } = await import("@/lib/api/client");
      const res = await fetch(
        `${API_BASE_URL}/api/v1/workspaces/${workspaceId}/analytics/export.csv${qs()}`,
        { headers: token ? { Authorization: `Bearer ${token}` } : {} },
      );
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = "analytics.csv";
      a.click();
      URL.revokeObjectURL(url);
    })();
  }

  if (!workspaces)
    return <Skeleton className="h-64" />;
  if (!selected)
    return <EmptyState title="No workspace selected" description="Create a workspace to see analytics." />;

  const num = (k: string) => Number(summary?.[k] ?? 0);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          eyebrow={`Workspace · /${selected.slug}`}
          title="Analytics"
          subtitle="All numbers derive from append-only campaign events — reconciliation-tested, no open tracking."
        />
        <div className="mb-3 flex flex-wrap items-center gap-2 px-6">
          <input
            aria-label="From date"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          />
          <input
            aria-label="To date"
            type="date"
            value={to}
            onChange={(e) => setTo(e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          />
          <Button variant="secondary" onClick={load}>
            Apply range
          </Button>
          <Button variant="ghost" onClick={exportCsv}>
            Export CSV
          </Button>
        </div>
        {error && <Alert variant="error">{error}</Alert>}
      </Card>

      {warnings.length > 0 && (
        <Card>
          <CardHeader eyebrow="Transparent thresholds" title="Anomaly warnings" />
          <div className="space-y-2 px-6 pb-6">
            {warnings.map((w, i) => (
              <Alert key={i} variant="warning">
                {String(w.type)}: {String(w.detail)}
              </Alert>
            ))}
          </div>
        </Card>
      )}

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        <Kpi label="Emails submitted" value={num("emailsSubmitted")} />
        <Kpi label="Unique leads contacted" value={num("uniqueLeadsContacted")} />
        <Kpi label="Provider-accepted" value={num("accepted")} hint="Relay receipt, not inbox delivery" />
        <Kpi label="Hard bounces" value={num("bounceHard")} />
        <Kpi label="Soft bounces" value={num("bounceSoft")} />
        <Kpi label="Replies" value={num("replies")} />
        <Kpi label="Positive replies" value={num("positiveReplies")} />
        <Kpi label="Unsubscribes" value={num("unsubscribes")} />
        <Kpi label="Meetings booked" value={num("meetingsBooked")} />
      </div>

      <Card>
        <CardHeader eyebrow="Trends" title="Daily sends" subtitle="Bars scale to the max day in range." />
        <div className="px-6 pb-6">
          <Bars data={series} field="sends" />
        </div>
      </Card>

      <Card>
        <CardHeader eyebrow="Trends" title="Daily replies" />
        <div className="px-6 pb-6">
          <Bars data={series} field="replies" />
        </div>
      </Card>

      <Card>
        <CardHeader eyebrow="Per campaign" title="Campaign comparison" />
        {campaigns.length === 0 ? (
          <div className="px-6 pb-6">
            <EmptyState title="No campaign activity" description="Activate a campaign to see comparisons." />
          </div>
        ) : (
          <div className="px-6 pb-6">
            <Table>
              <THead>
                <TR>
                  <TH>Campaign</TH>
                  <TH>Sends</TH>
                  <TH>Accepted</TH>
                  <TH>Hard bounces</TH>
                  <TH>Replies</TH>
                  <TH>Positive</TH>
                  <TH>Meetings</TH>
                </TR>
              </THead>
              <TBody>
                {campaigns.map((c, i) => (
                  <TR key={i}>
                    <TD>{String(c.name)}</TD>
                    <TD>{String(c.sends)}</TD>
                    <TD>{String(c.accepted)}</TD>
                    <TD>{String(c.bounceHard)}</TD>
                    <TD>{String(c.replies)}</TD>
                    <TD>{String(c.positiveReplies)}</TD>
                    <TD>{String(c.meetings)}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
        )}
      </Card>

      <Card>
        <CardHeader
          eyebrow="Per mailbox"
          title="Mailbox health comparison"
          subtitle="Accepted vs bounced per sending account."
        />
        {mailboxes.length === 0 ? (
          <div className="px-6 pb-6 text-muted-foreground text-sm">No mailbox activity in range.</div>
        ) : (
          <div className="px-6 pb-6">
            <Table>
              <THead>
                <TR>
                  <TH>Mailbox</TH>
                  <TH>Sends</TH>
                  <TH>Accepted</TH>
                  <TH>Hard bounces</TH>
                  <TH>Soft bounces</TH>
                </TR>
              </THead>
              <TBody>
                {mailboxes.map((m, i) => (
                  <TR key={i}>
                    <TD>{String(m.mailbox)}</TD>
                    <TD>{String(m.sends)}</TD>
                    <TD>{String(m.accepted)}</TD>
                    <TD>{String(m.bounceHard)}</TD>
                    <TD>{String(m.bounceSoft)}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </div>
        )}
      </Card>
    </div>
  );
}
