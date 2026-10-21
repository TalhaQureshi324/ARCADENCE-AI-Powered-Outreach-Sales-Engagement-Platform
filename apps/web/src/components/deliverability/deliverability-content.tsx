"use client";

import { useCallback, useEffect, useState } from "react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import { ApiError } from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

/** Deliverability Command Center (Phase 8): DNS auth diagnostics with
  * copyable instructions, sender-health snapshots with automatic pause
  * notices, and permission-gated placement tests (honest "unavailable"
  * until an external provider is configured).
  */
export function DeliverabilityContent() {
  const { selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";
  const { toast } = useToast();

  const [domain, setDomain] = useState("");
  const [provider, setProvider] = useState("smtp_imap");
  const [selectors, setSelectors] = useState("google._domainkey, selector1._domainkey, selector2._domainkey");
  const [checking, setChecking] = useState(false);
  const [result, setResult] = useState<{
    domain: string;
    overall: string;
    findings: { record: string; ok: boolean; message: string; remediation: string }[];
  } | null>(null);
  const [history, setHistory] = useState<{ checkedAt: string; status: string; reasons: unknown }[]>([]);
  const [accountId, setAccountId] = useState("");
  const [health, setHealth] = useState<{
    status: string;
    reasons: string[];
    bounceHardRate: number;
    authFailures: number;
  } | null>(null);
  const [seedAddresses, setSeedAddresses] = useState("seed1@inbox.test, seed2@spam.test");
  const [tests, setTests] = useState<
    { id: string; status: string; statusReason: string | null; sampleSize: number }[]
  >([]);
  const [error, setError] = useState<string | null>(null);

  const loadTests = useCallback(() => {
    if (!workspaceId) return;
    import("@/lib/api/client").then(({ deliverabilityApi }) => {
      deliverabilityApi
        .placementTests(workspaceId)
        .then(setTests)
        .catch(() => setTests([]));
    });
  }, [workspaceId]);

  useEffect(() => {
    Promise.resolve().then(loadTests);
  }, [loadTests]);

  async function check() {
    setChecking(true);
    setError(null);
    try {
      const r = await import("@/lib/api/client").then(({ deliverabilityApi }) =>
        deliverabilityApi.checkDomain(
          workspaceId,
          domain.trim(),
          provider,
          selectors.split(",").map((s) => s.trim()).filter(Boolean),
        ),
      );
      setResult(r);
      const h = await import("@/lib/api/client").then(({ deliverabilityApi }) =>
        deliverabilityApi.domainHistory(workspaceId, r.domain),
      );
      setHistory((h.history as { checkedAt: string; status: string; reasons: unknown }[]) ?? []);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setChecking(false);
    }
  }

  async function checkHealth() {
    setError(null);
    try {
      const h = await import("@/lib/api/client").then(({ deliverabilityApi }) =>
        deliverabilityApi.accountHealth(workspaceId, accountId),
      );
      setHealth(h as typeof health);
      toast("success", "Health snapshot captured.");
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function createPlacementTest() {
    setError(null);
    try {
      await import("@/lib/api/client").then(({ deliverabilityApi }) =>
        deliverabilityApi.createPlacementTest(
          workspaceId,
          accountId,
          Math.min(5, seedAddresses.split(",").filter((s) => s.trim()).length) || 2,
          seedAddresses.split(",").map((s) => s.trim()).filter(Boolean),
        ),
      );
      toast("success", "Placement test recorded.");
      loadTests();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="space-y-4">
      {error && <Alert variant="error">{error}</Alert>}

      <Card>
        <CardHeader
          eyebrow="Authentication"
          title="Domain DNS diagnostics"
          subtitle="SPF/DKIM/DMARC checks with provider-aware interpretation. A DNS pass does NOT guarantee inbox placement."
        />
        <div className="flex flex-wrap items-center gap-2 px-6">
          <Input aria-label="Sending domain" placeholder="yourdomain.com" value={domain} onChange={(e) => setDomain(e.target.value)} />
          <select
            aria-label="Provider"
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            <option value="smtp_imap">SMTP/IMAP</option>
            <option value="gmail">Gmail</option>
            <option value="microsoft">Microsoft 365</option>
          </select>
          <Input aria-label="DKIM selectors" value={selectors} onChange={(e) => setSelectors(e.target.value)} />
          <Button onClick={() => void check()} disabled={!domain.trim() || checking}>
            {checking ? "Checking…" : "Check DNS"}
          </Button>
        </div>
        {result && (
          <div className="space-y-2 px-6 pb-6 pt-4">
            <Badge tone={result.overall === "pass" ? "success" : result.overall === "warn" ? "warning" : "danger"}>
              {result.overall.toUpperCase()}
            </Badge>
            {result.findings.map((f, i) => (
              <div key={i} className="border-border bg-surface rounded-lg border p-3 text-sm">
                <div className="flex items-center gap-2">
                  <Badge tone={f.ok ? "success" : "danger"}>{f.record}</Badge>
                  <span className="text-foreground">{f.message}</span>
                </div>
                {f.remediation && (
                  <div className="text-muted-foreground mt-1 text-xs">
                    Fix: {f.remediation}
                  </div>
                )}
              </div>
            ))}
            {history.length > 0 && (
              <div className="text-muted-foreground text-xs">
                {history.length} historical check(s) recorded — latest {history[0]?.status} at{" "}
                {new Date(history[0]?.checkedAt).toLocaleString()}
              </div>
            )}
          </div>
        )}
      </Card>

      <Card>
        <CardHeader
          eyebrow="Sender health"
          title="Mailbox health snapshot"
          subtitle="Transparent thresholds: risk at hard-bounce >= 10% of accepted, >= 5 auth failures, or suppression >= 15%; warning at half those."
        />
        <div className="flex items-center gap-2 px-6 pb-4">
          <Input aria-label="Sending account ID" placeholder="Account UUID" value={accountId} onChange={(e) => setAccountId(e.target.value)} />
          <Button variant="secondary" onClick={() => void checkHealth()} disabled={!accountId.trim()}>
            Snapshot health
          </Button>
        </div>
        {health && (
          <div className="space-y-2 px-6 pb-6">
            <Badge tone={health.status === "healthy" ? "success" : health.status === "warning" ? "warning" : "danger"}>
              {health.status.toUpperCase()}
            </Badge>
            {health.reasons.map((r, i) => (
              <Alert key={i} variant={health.status === "risk" ? "error" : "warning"}>
                {r}
              </Alert>
            ))}
            <div className="text-muted-foreground text-xs">
              Hard-bounce rate: {health.bounceHardRate.toFixed(1)}% · auth failures: {health.authFailures}
            </div>
          </div>
        )}
      </Card>

      <Card>
        <CardHeader
          eyebrow="Placement tests"
          title="Controlled inbox placement"
          subtitle="Permission-gated (admin+), limited to 20 seeds and 5 active tests. Seed results represent only the tested mailboxes — NOT a universal inbox-placement guarantee."
        />
        <div className="flex items-center gap-2 px-6 pb-4">
          <Input aria-label="Sending account ID for placement test" placeholder="Account UUID" value={accountId} onChange={(e) => setAccountId(e.target.value)} />
          <Input aria-label="Seed addresses" value={seedAddresses} onChange={(e) => setSeedAddresses(e.target.value)} />
          <Button variant="secondary" onClick={() => void createPlacementTest()} disabled={!accountId.trim()}>
            Create test
          </Button>
        </div>
        {tests.length > 0 && (
          <div className="space-y-2 px-6 pb-6">
            {tests.map((t) => (
              <div key={t.id} className="border-border bg-surface rounded-lg border p-3 text-sm">
                <div className="flex items-center justify-between">
                  <Badge tone={t.status === "done" ? "success" : t.status === "unavailable" ? "warning" : "primary"}>
                    {t.status}
                  </Badge>
                  <span className="text-muted-foreground text-xs">seeds: {t.sampleSize}</span>
                </div>
                {t.statusReason && <div className="text-muted-foreground mt-1 text-xs">{t.statusReason}</div>}
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
