"use client";

import { useCallback, useEffect, useState } from "react";
import { Bot, CheckCircle2, Coins, XCircle } from "lucide-react";

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
import { AiAgentDto, aiApi, ApiError } from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

export function AiStudioContent() {
  const { selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";
  const { toast } = useToast();

  const [agents, setAgents] = useState<AiAgentDto[] | null>(null);
  const [runs, setRuns] = useState<
    | {
        id: string;
        kind: string;
        status: string;
        inputTokens: number;
        outputTokens: number;
        costMicros: number;
        error: string | null;
      }[]
    | null
  >(null);
  const [approvals, setApprovals] = useState<
    { id: string; action: string; status: string }[] | null
  >(null);
  const [usage, setUsage] = useState<{
    tokens: number;
    costMicros: number;
    budgetTokens: number;
  } | null>(null);
  const [agentName, setAgentName] = useState("");
  const [agentKind, setAgentKind] = useState("writer");
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    if (!workspaceId) return;
    aiApi
      .agents(workspaceId)
      .then(setAgents)
      .catch(() => setAgents([]));
    aiApi
      .runs(workspaceId)
      .then(setRuns)
      .catch(() => setRuns([]));
    aiApi
      .approvals(workspaceId, "pending")
      .then((a) => setApprovals(a.map((x) => ({ id: x.id, action: x.action, status: x.status }))))
      .catch(() => setApprovals([]));
    aiApi
      .usage(workspaceId)
      .then((u) =>
        setUsage({
          tokens: u.month.totalTokens,
          costMicros: u.month.costMicros,
          budgetTokens: u.budgetTokens,
        }),
      )
      .catch(() => setUsage(null));
  }, [workspaceId]);

  useEffect(() => {
    Promise.resolve().then(load);
  }, [load]);

  async function createAgent() {
    if (!agentName.trim()) return;
    setCreating(true);
    try {
      await aiApi.createAgent(workspaceId, { name: agentName.trim(), kind: agentKind });
      setAgentName("");
      toast("success", "Agent created.");
      load();
    } catch (e) {
      toast("error", errorMessage(e));
    } finally {
      setCreating(false);
    }
  }

  async function decide(id: string, action: "approve" | "reject") {
    try {
      await aiApi.messages(workspaceId, id, action);
      toast("success", `Approval ${action === "approve" ? "granted" : "rejected"}.`);
      load();
    } catch (e) {
      try {
        // approvals decide path for non-draft actions
        await aiApi.decideApproval(workspaceId, id, action === "approve" ? "approved" : "rejected");
        toast("success", `Approval ${action === "approve" ? "granted" : "rejected"}.`);
        load();
      } catch (e2) {
        toast("error", errorMessage(e2));
      }
    }
  }

  if (!workspaceId) return <Skeleton className="h-64" />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-foreground text-xl font-semibold tracking-tight">AI Studio</h1>
        <p className="text-muted-foreground mt-1 text-sm">
          Configure agents, review proposals and approvals, and watch spend. AI proposes —
          deterministic services execute.
        </p>
      </div>

      {usage && (
        <div className="border-border bg-surface rounded-xl border px-4 py-3">
          <div className="text-muted-foreground flex items-center gap-2 text-xs font-medium">
            <Coins size={13} /> Monthly AI usage
          </div>
          <div className="text-foreground mt-1 text-lg font-semibold">
            {usage.tokens.toLocaleString()} / {usage.budgetTokens.toLocaleString()} tokens
            <span className="text-muted-foreground ml-3 text-sm font-normal">
              ≈ ${(usage.costMicros / 1_000_000).toFixed(2)}
            </span>
          </div>
        </div>
      )}

      <Card>
        <CardHeader
          eyebrow="Agents"
          title="Configured agents"
          subtitle="Each agent runs under workspace permissions, budgets and approval rules."
        />
        <div className="space-y-3 p-4 pt-0">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              value={agentName}
              onChange={(e) => setAgentName(e.target.value)}
              placeholder="New agent name"
              className="w-56"
            />
            <select
              value={agentKind}
              onChange={(e) => setAgentKind(e.target.value)}
              className="border-border bg-surface text-foreground rounded-lg border px-3 py-2 text-sm"
            >
              <option value="writer">writer</option>
              <option value="researcher">researcher</option>
              <option value="reply_agent">reply agent</option>
              <option value="sales_agent">sales agent</option>
            </select>
            <Button onClick={createAgent} disabled={creating || !agentName.trim()}>
              <Bot size={15} className="mr-1.5" />
              Create agent
            </Button>
          </div>
          {agents === null && <Skeleton className="h-20" />}
          {agents !== null && agents.length === 0 && (
            <EmptyState
              icon={<Bot size={20} />}
              title="No agents yet"
              description="Create an agent to orchestrate writer, researcher or sales workflows."
            />
          )}
          {agents !== null && agents.length > 0 && (
            <Table>
              <THead>
                <TR>
                  <TH>Name</TH>
                  <TH>Kind</TH>
                  <TH>Model</TH>
                  <TH>Approval</TH>
                  <TH>Status</TH>
                </TR>
              </THead>
              <TBody>
                {agents.map((a) => (
                  <TR key={a.id}>
                    <TD>{a.name}</TD>
                    <TD>
                      <Badge tone="primary">{a.kind}</Badge>
                    </TD>
                    <TD className="text-muted-foreground text-xs">{a.model}</TD>
                    <TD>{a.requiresApproval ? "required" : "not required"}</TD>
                    <TD>
                      <Badge tone={a.enabled ? "success" : "neutral"}>
                        {a.enabled ? "enabled" : "disabled"}
                      </Badge>
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          )}
        </div>
      </Card>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader
            eyebrow="Governance"
            title="Pending approvals"
            subtitle="Nothing AI-proposed runs without a human decision."
          />
          <div className="space-y-2 p-4 pt-0">
            {approvals === null && <Skeleton className="h-16" />}
            {approvals !== null && approvals.length === 0 && (
              <p className="text-muted-foreground text-sm">No pending approvals.</p>
            )}
            {approvals !== null &&
              approvals.map((a) => (
                <div
                  key={a.id}
                  className="border-border flex items-center justify-between gap-2 rounded-lg border px-3 py-2"
                >
                  <Badge tone="warning">{a.action}</Badge>
                  <div className="flex gap-2">
                    <Button onClick={() => decide(a.id, "approve")}>
                      <CheckCircle2 size={14} className="mr-1" /> Approve
                    </Button>
                    <Button variant="secondary" onClick={() => decide(a.id, "reject")}>
                      <XCircle size={14} className="mr-1" /> Reject
                    </Button>
                  </div>
                </div>
              ))}
          </div>
        </Card>

        <Card>
          <CardHeader
            eyebrow="Audit"
            title="Recent runs"
            subtitle="Token spend and outcomes for every AI execution."
          />
          <div className="p-4 pt-0">
            {runs === null && <Skeleton className="h-24" />}
            {runs !== null && runs.length === 0 && (
              <p className="text-muted-foreground text-sm">No runs yet.</p>
            )}
            {runs !== null && runs.length > 0 && (
              <Table>
                <THead>
                  <TR>
                    <TH>Kind</TH>
                    <TH>Status</TH>
                    <TH>Tokens</TH>
                    <TH>Cost</TH>
                  </TR>
                </THead>
                <TBody>
                  {runs.slice(0, 15).map((r) => (
                    <TR key={r.id}>
                      <TD>
                        <Badge tone="neutral">{r.kind}</Badge>
                      </TD>
                      <TD>
                        <Badge
                          tone={
                            (r.status === "completed"
                              ? "success"
                              : r.status === "failed"
                                ? "danger"
                                : "warning") as BadgeTone
                          }
                        >
                          {r.status}
                        </Badge>
                        {r.error && <div className="text-danger text-xs">{r.error}</div>}
                      </TD>
                      <TD className="text-xs">
                        {(r.inputTokens + r.outputTokens).toLocaleString()}
                      </TD>
                      <TD className="text-xs">${(r.costMicros / 1_000_000).toFixed(4)}</TD>
                    </TR>
                  ))}
                </TBody>
              </Table>
            )}
            {runs !== null && runs.length > 0 && usage && usage.tokens >= usage.budgetTokens && (
              <Alert variant="warning">
                Monthly token budget exhausted — AI calls are blocked until the reset.
              </Alert>
            )}
          </div>
        </Card>
      </div>
    </div>
  );
}
