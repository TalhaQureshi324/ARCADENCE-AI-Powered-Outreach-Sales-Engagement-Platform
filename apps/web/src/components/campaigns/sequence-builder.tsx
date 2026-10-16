"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  addEdge,
  Background,
  Controls,
  MiniMap,
  ReactFlow,
  type Connection,
  type Edge,
  type Node,
  type NodeChange,
  type NodeTypes,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { GitBranch, Mail, Plus, Tag as TagIcon, Timer } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  campaignsApi,
  type CampaignDetailDto,
  type CampaignStepDto,
  type EdgeInput,
  type TagDto,
} from "@/lib/api/client";
import { ComposerDialog, type ComposerTarget } from "@/components/campaigns/composer-dialog";

const TYPE_COLORS: Record<string, string> = {
  email: "border-primary",
  wait: "border-muted-foreground",
  branch: "border-warning",
  task: "border-success",
  add_tag: "border-danger",
};

const TYPE_ICONS: Record<string, React.ReactNode> = {
  email: <Mail size={14} />,
  wait: <Timer size={14} />,
  branch: <GitBranch size={14} />,
  task: <Timer size={14} />,
  add_tag: <TagIcon size={14} />,
};

function keyOf(step: CampaignStepDto): string {
  return step.key ?? step.id;
}

function summary(step: CampaignStepDto): string {
  switch (step.type) {
    case "email":
      return step.variants[0]?.subject || "(no subject)";
    case "wait":
      return `Wait ${step.waitDays ?? "?"} day(s)${step.businessDays ? " · business" : ""}`;
    case "branch":
      return "If / else";
    case "task":
      return `Task due in ${step.taskDueDays ?? "?"} day(s)`;
    case "add_tag":
      return "Add tag";
    default:
      return step.type;
  }
}

/** Simple layered layout: BFS depth → column, in-degree order → row. */
function layout(steps: CampaignStepDto[], edges: Edge[]): Node[] {
  const incoming = new Map<string, number>();
  steps.forEach((s) => incoming.set(keyOf(s), 0));
  edges.forEach((e) => incoming.set(e.target, (incoming.get(e.target) ?? 0) + 1));
  const roots = steps.filter((s) => (incoming.get(keyOf(s)) ?? 0) === 0);
  const depth = new Map<string, number>();
  const queue: string[] = roots.map(keyOf);
  roots.forEach((s) => depth.set(keyOf(s), 0));
  while (queue.length > 0) {
    const k = queue.shift()!;
    const d = depth.get(k) ?? 0;
    edges
      .filter((e) => e.source === k)
      .forEach((e) => {
        if (!depth.has(e.target)) {
          depth.set(e.target, d + 1);
          queue.push(e.target);
        }
      });
  }
  const perColumn = new Map<number, number>();
  return steps.map((s) => {
    const k = keyOf(s);
    const d = depth.get(k) ?? 0;
    const row = perColumn.get(d) ?? 0;
    perColumn.set(d, row + 1);
    return {
      id: k,
      position: { x: d * 260 + 20, y: row * 130 + 20 },
      data: { step: s },
      type: "stepCard",
    } satisfies Node;
  });
}

const stepNodeType = {
  stepCard: ({ data }: { data: { step: CampaignStepDto } }) => {
    const step = data.step;
    return (
      <div
        className={`bg-surface-raised border-border shadow-raised w-52 rounded-xl border border-l-4 p-3 ${
          TYPE_COLORS[step.type] ?? ""
        }`}
      >
        <div className="text-foreground flex items-center gap-2 text-sm font-medium">
          {TYPE_ICONS[step.type]}
          {step.type}
          {step.key && <span className="text-muted-foreground text-[10px]">{step.key}</span>}
        </div>
        <div className="text-muted-foreground mt-1 truncate text-xs">{summary(step)}</div>
      </div>
    );
  },
} satisfies NodeTypes;

export function SequenceBuilder({
  workspaceId,
  campaignId,
  detail,
  onSaved,
}: {
  workspaceId: string;
  campaignId: string;
  detail: CampaignDetailDto;
  onSaved: () => void;
}) {
  const [nodes, setNodes] = useState<Node[]>([]);
  const [edges, setEdges] = useState<Edge[]>([]);
  const [registry, setRegistry] = useState<Map<string, CampaignStepDto>>(new Map());
  const registryRef = useRef(registry);
  const keyCounter = useRef(0);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [tags, setTags] = useState<TagDto[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [compose, setCompose] = useState<ComposerTarget | null>(null);

  const steps = detail.steps;
  const selected = selectedKey ? registry.get(selectedKey) : undefined;

  useEffect(() => {
    const initialEdges: Edge[] = detail.edges.map((e, i) => {
      const from = steps.find((s) => s.id === e.from);
      const to = steps.find((s) => s.id === e.to);
      return {
        id: `e${i}`,
        source: from ? keyOf(from) : e.from,
        target: to ? keyOf(to) : e.to,
        label: e.branchKey ?? undefined,
        animated: e.branchKey === "yes",
      };
    });
    const initialRegistry = new Map(steps.map((s) => [keyOf(s), s]));
    Promise.resolve().then(() => {
      registryRef.current = initialRegistry;
      setRegistry(initialRegistry);
      setEdges(initialEdges);
      setNodes(layout(steps, initialEdges));
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [detail]);

  useEffect(() => {
    import("@/lib/api/client").then(({ tagsApi }) =>
      tagsApi
        .list(workspaceId)
        .then(setTags)
        .catch(() => setTags([])),
    );
  }, [workspaceId]);

  const onConnect = useCallback((connection: Connection) => {
    const source = registryRef.current.get(connection.source);
    setEdges((eds) => {
      let label: string | undefined;
      if (source?.type === "branch") {
        const hasYes = eds.some((e) => e.source === connection.source && e.label === "yes");
        label = hasYes ? "no" : "yes";
      }
      return addEdge({ ...connection, label }, eds);
    });
  }, []);

  const onNodesChange = useCallback((changes: NodeChange[]) => {
    setNodes((nds) => {
      // apply position changes only
      const byId = new Map(nds.map((n) => [n.id, n]));
      changes.forEach((c) => {
        if (c.type === "position" && byId.has(c.id)) {
          const n = byId.get(c.id)!;
          if (c.position) n.position = c.position;
        }
      });
      return [...nds];
    });
  }, []);

  function updateStep(key: string, mutate: (s: CampaignStepDto) => CampaignStepDto) {
    const s = registryRef.current.get(key);
    if (!s) return;
    const updated = mutate(s);
    const next = new Map<string, CampaignStepDto>(registryRef.current);
    next.set(key, updated);
    registryRef.current = next;
    setRegistry(next);
    setNodes((nds) => nds.map((n) => (n.id === key ? { ...n, data: { step: updated } } : n)));
  }

  function addStep(type: string) {
    keyCounter.current += 1;
    const key = `s-new-${keyCounter.current}`;
    const currentSteps = nodes
      .map((n) => registryRef.current.get(n.id))
      .filter((s): s is CampaignStepDto => Boolean(s));
    const base: CampaignStepDto = {
      id: `new-${key}`,
      position: steps.length + 1,
      type,
      name: "",
      waitDays: type === "wait" ? 1 : null,
      key,
      condition: type === "branch" ? { field: "state", operator: "eq", value: "active" } : null,
      tagId: null,
      taskDueDays: type === "task" ? 1 : null,
      businessDays: false,
      variants:
        type === "email"
          ? [
              {
                variantKey: "A",
                subject: "",
                preheader: "",
                bodyHtml: "",
                bodyText: "",
                signature: "",
              },
            ]
          : [],
    };
    const nextSteps = [...currentSteps, base];
    const nextEdges =
      nodes.length > 0
        ? [...edges, { id: `e-auto-${key}`, source: nodes[nodes.length - 1].id, target: key }]
        : edges;
    const nextRegistry = new Map<string, CampaignStepDto>(registryRef.current);
    nextRegistry.set(key, base);
    registryRef.current = nextRegistry;
    setRegistry(nextRegistry);
    setEdges(nextEdges);
    setSelectedKey(key);
    setNodes(layout(nextSteps, nextEdges));
  }

  function removeStep(key: string) {
    const nextSteps = nodes
      .map((n) => registryRef.current.get(n.id))
      .filter((s): s is CampaignStepDto => Boolean(s))
      .filter((s) => keyOf(s) !== key);
    const nextEdges = edges.filter((e) => e.source !== key && e.target !== key);
    const nextRegistry = new Map<string, CampaignStepDto>(registryRef.current);
    nextRegistry.delete(key);
    registryRef.current = nextRegistry;
    setRegistry(nextRegistry);
    setEdges(nextEdges);
    setSelectedKey(null);
    setNodes(layout(nextSteps, nextEdges));
  }

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const byKey = registryRef.current;
      const presentKeys = new Set(nodes.map((n) => n.id));
      const stepInputs = nodes.map((n) => {
        const s = byKey.get(n.id)!;
        return {
          type: s.type,
          name: s.name,
          waitDays: s.waitDays ?? undefined,
          key: keyOf(s),
          condition: s.condition ?? undefined,
          tagId: s.tagId ?? undefined,
          taskDueDays: s.taskDueDays ?? undefined,
          businessDays: s.businessDays || undefined,
          variants: s.variants.map((v) => ({
            variantKey: v.variantKey,
            subject: v.subject,
            preheader: v.preheader,
            bodyHtml: v.bodyHtml,
            bodyText: v.bodyText,
            signature: v.signature,
          })),
        };
      });
      const edgeInputs: EdgeInput[] = edges
        .filter((e) => presentKeys.has(e.source) && presentKeys.has(e.target))
        .map((e) => ({
          from: e.source,
          to: e.target,
          branchKey: (e.label as string) || undefined,
        }));
      await campaignsApi.update(workspaceId, campaignId, { steps: stepInputs, edges: edgeInputs });
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Save failed");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-3">
      {error && <Alert variant="error">{error}</Alert>}
      <div className="flex flex-wrap items-center gap-2">
        {["email", "wait", "branch", "task", "add_tag"].map((t) => (
          <Button key={t} variant="secondary" onClick={() => addStep(t)}>
            <Plus /> {t}
          </Button>
        ))}
        <Button variant="primary" onClick={() => void save()} disabled={saving}>
          {saving ? "Saving…" : "Save sequence"}
        </Button>
      </div>
      <div className="border-border h-[520px] rounded-xl border">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={stepNodeType}
          onNodesChange={onNodesChange}
          onConnect={onConnect}
          onNodeClick={(_, node) => setSelectedKey(node.id)}
          onEdgeClick={(_, edge) => setEdges((eds) => eds.filter((e) => e.id !== edge.id))}
          fitView
        >
          <Background />
          <Controls />
          <MiniMap pannable zoomable />
        </ReactFlow>
      </div>
      <p className="text-muted-foreground text-xs">
        Drag from a card&apos;s edge to another card to connect. Click a card to edit; click a
        connection to remove it. Branch steps need two labeled connections (yes / no) — set the
        label in the step editor.
      </p>

      {selected && (
        <div className="border-border bg-surface-raised space-y-3 rounded-xl border p-4">
          <div className="text-foreground flex items-center justify-between text-sm font-medium">
            <span className="flex items-center gap-2">
              {TYPE_ICONS[selected.type]} {selected.type} · {selected.key ?? selected.id}
            </span>
            <Button variant="ghost" onClick={() => removeStep(keyOf(selected))}>
              Remove step
            </Button>
          </div>
          <Input
            aria-label="Step name"
            placeholder="Step name"
            value={selected.name}
            onChange={(e) => updateStep(keyOf(selected), (s) => ({ ...s, name: e.target.value }))}
            maxLength={120}
          />
          {selected.type === "wait" && (
            <div className="flex items-center gap-2">
              <Input
                aria-label="Wait days"
                className="w-24"
                inputMode="numeric"
                value={String(selected.waitDays ?? "")}
                onChange={(e) =>
                  updateStep(keyOf(selected), (s) => ({
                    ...s,
                    waitDays: Math.max(0, Number(e.target.value) || 0),
                  }))
                }
              />
              <label className="text-muted-foreground flex items-center gap-1 text-sm">
                <input
                  type="checkbox"
                  aria-label="Business days only"
                  checked={selected.businessDays}
                  onChange={(e) =>
                    updateStep(keyOf(selected), (s) => ({ ...s, businessDays: e.target.checked }))
                  }
                />
                skip weekends
              </label>
            </div>
          )}
          {selected.type === "branch" && (
            <p className="text-muted-foreground text-xs">
              Condition uses the same fields as segments (state, verification_status, region,
              job_title, email, custom.*). Edit the JSON condition below — the visual condition
              editor arrives with the CRM phase.
            </p>
          )}
          {selected.type === "branch" && (
            <textarea
              aria-label="Branch condition JSON"
              className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 font-mono text-xs"
              rows={3}
              value={JSON.stringify(selected.condition ?? {}, null, 2)}
              onChange={(e) => {
                try {
                  const parsed = JSON.parse(e.target.value);
                  updateStep(keyOf(selected), (s) => ({ ...s, condition: parsed }));
                } catch {
                  // ignore invalid JSON while typing
                }
              }}
            />
          )}
          {selected.type === "task" && (
            <Input
              aria-label="Task due days"
              className="w-24"
              inputMode="numeric"
              value={String(selected.taskDueDays ?? "")}
              onChange={(e) =>
                updateStep(keyOf(selected), (s) => ({
                  ...s,
                  taskDueDays: Math.max(0, Number(e.target.value) || 0),
                }))
              }
            />
          )}
          {selected.type === "add_tag" && (
            <select
              aria-label="Tag to add"
              value={selected.tagId ?? ""}
              onChange={(e) =>
                updateStep(keyOf(selected), (s) => ({ ...s, tagId: e.target.value || null }))
              }
              className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
            >
              <option value="">Choose a tag…</option>
              {tags.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </select>
          )}
          {selected.type === "email" && selected.variants[0] && (
            <Button
              variant="secondary"
              onClick={() => {
                const fresh = registryRef.current.get(keyOf(selected)) ?? selected;
                const variant = fresh.variants[0];
                if (variant)
                  setCompose({
                    stepId: selected.id,
                    stepName: fresh.name || `Step ${fresh.position}`,
                    variantKey: variant.variantKey,
                    variant,
                  });
              }}
            >
              <Mail size={14} /> Compose email
            </Button>
          )}
        </div>
      )}

      <ComposerDialog
        open={compose !== null}
        workspaceId={workspaceId}
        campaignId={campaignId}
        target={compose}
        onClose={() => setCompose(null)}
      />
    </div>
  );
}
