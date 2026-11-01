"use client";

import { useCallback, useEffect, useState } from "react";
import { Eye, Globe, Target } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import { ApiError, visitorApi } from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

export function VisitorsContent() {
  const { selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";
  const { toast } = useToast();

  const [sites, setSites] = useState<{ id: string; domain: string; siteKey: string; active: boolean }[] | null>(null);
  const [stats, setStats] = useState<{
    pageviews: number;
    conversions: number;
    sessions: number;
    perDay: { date: string; pageviews: number; conversions: number }[];
    topPaths: { name: string; count: number }[];
    referrers: { name: string; count: number }[];
    utmCampaigns: { name: string; count: number }[];
  } | null>(null);
  const [domain, setDomain] = useState("");
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    if (!workspaceId) return;
    visitorApi.sites(workspaceId).then(setSites).catch(() => setSites([]));
    visitorApi.analytics(workspaceId, 7).then(setStats).catch(() => setStats(null));
  }, [workspaceId]);

  useEffect(() => {
    Promise.resolve().then(load);
  }, [load]);

  async function createSite() {
    if (!domain.trim()) return;
    setCreating(true);
    try {
      const res = await visitorApi.createSite(workspaceId, domain.trim());
      toast("success", `Site key created: ${res.siteKey.slice(0, 12)}…`);
      setDomain("");
      load();
    } catch (e) {
      toast("error", errorMessage(e));
    } finally {
      setCreating(false);
    }
  }

  if (!workspaceId) return <Skeleton className="h-64" />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-foreground text-xl font-semibold tracking-tight">Website visitors</h1>
        <p className="text-muted-foreground mt-1 text-sm">
          Anonymous, aggregated traffic from sites you control. Consent-gated — the script only fires after
          <code className="bg-surface-sunken mx-1 rounded px-1">window.__arcadenceConsent</code>
          is set by your consent flow.
        </p>
      </div>

      <Card>
        <CardHeader
          eyebrow="Setup"
          title="Tracked sites"
          subtitle="Register a domain, embed the snippet, and pageviews plus conversions start flowing."
        />
        <div className="space-y-3 p-4 pt-0">
          <div className="flex flex-wrap items-center gap-2">
            <Input value={domain} onChange={(e) => setDomain(e.target.value)} placeholder="yourdomain.com" className="w-64" />
            <button
              onClick={createSite}
              disabled={creating || !domain.trim()}
              className="bg-primary text-primary-foreground hover:bg-primary-hover rounded-lg px-4 py-2 text-sm font-medium disabled:opacity-50"
            >
              <Globe size={15} className="mr-1.5 inline" />
              Add site
            </button>
          </div>
          {sites === null && <Skeleton className="h-20" />}
          {sites !== null && sites.length === 0 && (
            <EmptyState icon={<Globe size={20} />} title="No sites tracked" description="Add your first domain to get a tracking snippet." />
          )}
          {sites !== null && sites.length > 0 && (
            <Table>
              <THead>
                <TR>
                  <TH>Domain</TH>
                  <TH>Site key</TH>
                  <TH>Status</TH>
                </TR>
              </THead>
              <TBody>
                {sites.map((s) => (
                  <TR key={s.id}>
                    <TD>{s.domain}</TD>
                    <TD className="text-muted-foreground font-mono text-xs">{s.siteKey.slice(0, 16)}…</TD>
                    <TD>{s.active ? "active" : "inactive"}</TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          )}
        </div>
      </Card>

      {stats && (
        <div className="grid gap-4 sm:grid-cols-3">
          <Card>
            <div className="p-4">
              <div className="text-muted-foreground flex items-center gap-2 text-xs font-medium">
                <Eye size={13} /> Pageviews (7d)
              </div>
              <div className="text-foreground mt-1 text-2xl font-semibold">{stats.pageviews.toLocaleString()}</div>
            </div>
          </Card>
          <Card>
            <div className="p-4">
              <div className="text-muted-foreground flex items-center gap-2 text-xs font-medium">
                <Target size={13} /> Conversions (7d)
              </div>
              <div className="text-foreground mt-1 text-2xl font-semibold">{stats.conversions.toLocaleString()}</div>
            </div>
          </Card>
          <Card>
            <div className="p-4">
              <div className="text-muted-foreground text-xs font-medium">Sessions (7d)</div>
              <div className="text-foreground mt-1 text-2xl font-semibold">{stats.sessions.toLocaleString()}</div>
            </div>
          </Card>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <Card>
          <CardHeader eyebrow="Behavior" title="Top paths" />
          <div className="p-4 pt-0">
            {stats === null && <Skeleton className="h-32" />}
            {stats !== null && stats.topPaths.length === 0 && <p className="text-muted-foreground text-sm">No traffic yet.</p>}
            {stats !== null && stats.topPaths.length > 0 && (
              <Table>
                <THead>
                  <TR>
                    <TH>Path</TH>
                    <TH>Views</TH>
                  </TR>
                </THead>
                <TBody>
                  {stats.topPaths.map((p) => (
                    <TR key={p.name}>
                      <TD className="text-xs">{p.name}</TD>
                      <TD>{p.count.toLocaleString()}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </div>
        </Card>
        <Card>
          <CardHeader eyebrow="Sources" title="Referrers" />
          <div className="p-4 pt-0">
            {stats === null && <Skeleton className="h-32" />}
            {stats !== null && stats.referrers.length === 0 && <p className="text-muted-foreground text-sm">No traffic yet.</p>}
            {stats !== null && stats.referrers.length > 0 && (
              <Table>
                <THead>
                  <TR>
                    <TH>Referrer</TH>
                    <TH>Views</TH>
                  </TR>
                </THead>
                <TBody>
                  {stats.referrers.map((r) => (
                    <TR key={r.name}>
                      <TD className="text-xs">{r.name}</TD>
                      <TD>{r.count.toLocaleString()}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </div>
        </Card>
        <Card>
          <CardHeader eyebrow="Campaigns" title="UTM campaigns" />
          <div className="p-4 pt-0">
            {stats === null && <Skeleton className="h-32" />}
            {stats !== null && stats.utmCampaigns.length === 0 && <p className="text-muted-foreground text-sm">No tagged traffic yet.</p>}
            {stats !== null && stats.utmCampaigns.length > 0 && (
              <Table>
                <THead>
                  <TR>
                    <TH>Campaign</TH>
                    <TH>Views</TH>
                  </TR>
                </THead>
                <TBody>
                  {stats.utmCampaigns.map((c) => (
                    <TR key={c.name}>
                      <TD className="text-xs">{c.name}</TD>
                      <TD>{c.count.toLocaleString()}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
          </div>
        </Card>
      </div>

      {stats && (
        <Alert variant="info">
          Aggregated, anonymous traffic only — Arcadence does not identify individual visitors from anonymous traffic.
        </Alert>
      )}
    </div>
  );
}
