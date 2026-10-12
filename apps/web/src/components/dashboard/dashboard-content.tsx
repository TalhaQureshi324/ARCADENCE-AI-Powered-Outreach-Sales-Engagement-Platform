"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Building2, Inbox, Plus, Users } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { ApiError, workspacesApi, type WorkspaceDto } from "@/lib/api/client";
import { API_BASE_URL } from "@/lib/config";

type WorkspacesState =
  | { kind: "loading" }
  | { kind: "loaded"; workspaces: WorkspaceDto[] }
  | { kind: "error"; message: string };

export function DashboardContent({
  userName,
  email,
  needsOnboarding,
}: {
  userName: string;
  email: string;
  needsOnboarding: boolean;
}) {
  const [state, setState] = useState<WorkspacesState>({ kind: "loading" });
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    const onWorkspaceChanged = () => setReloadKey((k) => k + 1);
    window.addEventListener("arcadence:workspace-changed", onWorkspaceChanged);
    return () => window.removeEventListener("arcadence:workspace-changed", onWorkspaceChanged);
  }, []);

  useEffect(() => {
    let cancelled = false;
    workspacesApi
      .list()
      .then((workspaces) => {
        if (!cancelled) {
          setState({ kind: "loaded", workspaces });
          setApiUp(true);
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setState({
            kind: "error",
            message: error instanceof ApiError ? error.message : "The API could not be reached.",
          });
          setApiUp(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <Breadcrumbs items={[{ label: "Home", href: "/app" }, { label: "Dashboard" }]} />

      {needsOnboarding && (
        <Alert variant="info">
          <Link href="/onboarding" className="font-medium underline underline-offset-2">
            Finish setting up your profile
          </Link>{" "}
          — it takes one step.
        </Alert>
      )}

      <Card>
        <CardHeader
          eyebrow="Dashboard"
          title={`Welcome, ${userName}`}
          subtitle={`Signed in as ${email}`}
        />
        <p className="text-muted-foreground text-sm leading-6">
          This is your control center. Workspace tools below are live; outreach features are rolling
          out subphase by subphase.
        </p>
      </Card>

      {/* Workspaces — live data from the Scala API */}
      <section aria-labelledby="workspaces-heading" className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 id="workspaces-heading" className="text-foreground text-sm font-semibold">
            Your workspaces
          </h2>
          {state.kind === "loaded" && (
            <CreateWorkspaceButton onCreated={() => setReloadKey((k) => k + 1)} />
          )}
        </div>

        {state.kind === "loading" && (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <Skeleton className="h-32" />
            <Skeleton className="h-32" />
          </div>
        )}

        {state.kind === "error" && (
          <Alert variant="error">
            {state.message}{" "}
            <Button
              variant="secondary"
              className="ml-2 px-2 py-1 text-xs"
              onClick={() => setReloadKey((k) => k + 1)}
            >
              Retry
            </Button>
          </Alert>
        )}

        {state.kind === "loaded" &&
          (state.workspaces.length === 0 ? (
            <EmptyState
              icon={<Building2 size={24} />}
              title="No workspaces yet"
              description="A workspace isolates one client or team — its leads, campaigns and inbox live inside it. Create your first one to get started."
              action={<CreateWorkspaceButton onCreated={() => setReloadKey((k) => k + 1)} />}
            />
          ) : (
            <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {state.workspaces.map((ws) => (
                <li key={ws.id}>
                  <Card className="flex h-full flex-col justify-between p-5">
                    <div>
                      <div className="flex items-center justify-between gap-2">
                        <h3 className="text-foreground truncate text-sm font-semibold">
                          {ws.name}
                        </h3>
                        <Badge tone={ws.role === "owner" ? "primary" : "neutral"}>{ws.role}</Badge>
                      </div>
                      <p className="text-muted-foreground mt-1 truncate text-xs">/{ws.slug}</p>
                    </div>
                    <p className="text-muted-foreground mt-4 text-xs">
                      Daily send cap: {ws.dailySendCap} · {ws.timezone}
                    </p>
                  </Card>
                </li>
              ))}
            </ul>
          ))}
      </section>

      {/* Roadmap placeholders — honest states, no fake data */}
      <section aria-labelledby="soon-heading" className="space-y-3">
        <h2 id="soon-heading" className="text-foreground text-sm font-semibold">
          Coming next
        </h2>
        <div className="grid gap-4 sm:grid-cols-2">
          <EmptyState
            icon={<Inbox size={20} />}
            title="Unified inbox"
            description="Two-way email sync with every connected account in one thread list."
            action={<Badge tone="neutral">Phase 6</Badge>}
          />
          <EmptyState
            icon={<Users size={20} />}
            title="Lead management"
            description="Import, organize and verify lead lists with suppression built in."
            action={<Badge tone="neutral">Phase 3</Badge>}
          />
        </div>
      </section>

      {/* System status — real health probe */}
      <SystemStatus apiUp={apiUp} />
    </div>
  );
}

function CreateWorkspaceButton({ onCreated }: { onCreated: () => void }) {
  // The shell owns the create dialog and listens for this event.
  return (
    <Button
      variant="secondary"
      className="px-3 py-1.5 text-xs"
      type="button"
      onClick={() => window.dispatchEvent(new CustomEvent("arcadence:create-workspace"))}
    >
      <Plus size={14} /> New workspace
    </Button>
  );
}

function SystemStatus({ apiUp }: { apiUp: boolean | null }) {
  const [apiHealth, setApiHealth] = useState<"checking" | "up" | "down">("checking");

  useEffect(() => {
    let cancelled = false;
    fetch(`${API_BASE_URL}/health`)
      .then((res) => {
        if (!cancelled) setApiHealth(res.ok ? "up" : "down");
      })
      .catch(() => {
        if (!cancelled) setApiHealth("down");
      });
    return () => {
      cancelled = true;
    };
  }, [apiUp]);

  const scala =
    apiHealth === "up" ? "operational" : apiHealth === "down" ? "unreachable" : "checking…";
  const auth = "operational"; // an authenticated session IS the auth check

  return (
    <Card>
      <CardHeader eyebrow="System" title="Platform status" />
      <dl className="grid gap-3 text-sm sm:grid-cols-2">
        <div className="bg-surface-sunken flex items-center justify-between gap-4 rounded-lg px-3 py-2.5">
          <dt className="text-muted-foreground">Supabase Auth</dt>
          <dd>
            <Badge tone="success">{auth}</Badge>
          </dd>
        </div>
        <div className="bg-surface-sunken flex items-center justify-between gap-4 rounded-lg px-3 py-2.5">
          <dt className="text-muted-foreground">Scala API</dt>
          <dd>
            <Badge tone={apiHealth === "up" ? "success" : "danger"}>{scala}</Badge>
          </dd>
        </div>
      </dl>
    </Card>
  );
}
