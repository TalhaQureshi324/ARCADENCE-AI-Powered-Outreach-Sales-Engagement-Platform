"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  BadgeCheck,
  Bookmark,
  Search,
  ShieldAlert,
  Sparkles,
  Trash2,
  TriangleAlert,
} from "lucide-react";

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
  leadFinderApi,
  leadListsApi,
  type LeadFinderDiscoveredDto,
  type LeadFinderProviderDto,
  type LeadFinderSearchDto,
  type LeadListDto,
} from "@/lib/api/client";

type LoadState<T> =
  { kind: "loading" } | { kind: "loaded"; data: T } | { kind: "error"; message: string };

type SavedSearch = { id: string; name: string; filters: Record<string, string> };

const FILTER_KEYS = [
  { key: "industry", label: "Industry", placeholder: "e.g. software" },
  { key: "region", label: "Region", placeholder: "e.g. EU" },
  { key: "jobTitle", label: "Job title", placeholder: "e.g. Head of Growth" },
  { key: "companySize", label: "Company size", placeholder: "e.g. 11-50" },
  { key: "technology", label: "Technology", placeholder: "e.g. hubspot" },
] as const;

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function verificationTone(v: string): BadgeTone {
  if (v === "verified") return "success";
  if (v === "risky") return "warning";
  return "neutral";
}

function fullName(c: LeadFinderDiscoveredDto): string {
  return [c.firstName, c.lastName].filter(Boolean).join(" ") || "—";
}

export function LeadFinderContent() {
  const { selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";
  const { toast } = useToast();

  const [providers, setProviders] = useState<LoadState<LeadFinderProviderDto[]>>({
    kind: "loading",
  });
  const [credits, setCredits] = useState<LoadState<number>>({ kind: "loading" });
  const [lists, setLists] = useState<LoadState<LeadListDto[]>>({ kind: "loading" });
  const [searches, setSearches] = useState<LoadState<LeadFinderSearchDto[]>>({ kind: "loading" });
  const [results, setResults] = useState<LoadState<LeadFinderDiscoveredDto[]>>({ kind: "loading" });
  const [activeSearchId, setActiveSearchId] = useState<string | null>(null);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [searching, setSearching] = useState(false);
  const [saved, setSaved] = useState<SavedSearch[]>([]);
  const [savedName, setSavedName] = useState("");
  const [addOpen, setAddOpen] = useState(false);
  const [chosenListId, setChosenListId] = useState("");
  const [adding, setAdding] = useState(false);

  const supported = useMemo(() => {
    if (providers.kind !== "loaded") return null;
    return providers.data[0]?.supportedFilters ?? [];
  }, [providers]);

  useEffect(() => {
    Promise.resolve().then(() => {
      try {
        const raw = localStorage.getItem(`leadfinder-saved-${workspaceId}`);
        if (raw) setSaved(JSON.parse(raw) as SavedSearch[]);
        else setSaved([]);
      } catch {
        setSaved([]);
      }
    });
  }, [workspaceId]);

  const refreshCredits = useCallback(() => {
    if (!workspaceId) return;
    setCredits({ kind: "loading" });
    leadFinderApi
      .credits(workspaceId)
      .then((c) => setCredits({ kind: "loaded", data: c.balance }))
      .catch((e) => setCredits({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  const refreshSearches = useCallback(() => {
    if (!workspaceId) return;
    leadFinderApi
      .searches(workspaceId)
      .then((s) => setSearches({ kind: "loaded", data: s }))
      .catch((e) => setSearches({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  const loadResults = useCallback(
    (searchId: string) => {
      setActiveSearchId(searchId);
      setResults({ kind: "loading" });
      leadFinderApi
        .results(workspaceId, searchId)
        .then((r) => setResults({ kind: "loaded", data: r }))
        .catch((e) => setResults({ kind: "error", message: errorMessage(e) }));
    },
    [workspaceId],
  );

  useEffect(() => {
    if (!workspaceId) return;
    leadFinderApi
      .providers(workspaceId)
      .then((p) => setProviders({ kind: "loaded", data: p }))
      .catch((e) => setProviders({ kind: "error", message: errorMessage(e) }));
    Promise.resolve().then(() => {
      setLists({ kind: "loading" });
      leadListsApi
        .list(workspaceId)
        .then((l) => {
          setLists({ kind: "loaded", data: l });
          if (l[0]) setChosenListId(l[0].id);
        })
        .catch((e) => setLists({ kind: "error", message: errorMessage(e) }));
      refreshCredits();
      refreshSearches();
    });
  }, [workspaceId, refreshCredits, refreshSearches]);

  function runSearch() {
    if (!workspaceId || searching) return;
    setSearching(true);
    leadFinderApi
      .search(
        workspaceId,
        Object.fromEntries(Object.entries(filters).filter(([, v]) => v.trim() !== "")),
      )
      .then((s) => {
        refreshCredits();
        refreshSearches();
        if (s.status === "done") {
          loadResults(s.id);
          toast(
            "success",
            s.resultsCount > 0
              ? `Found ${s.resultsCount} contacts — ${s.creditsCharged} credits charged.`
              : "No contacts matched. Your reservation was refunded.",
          );
        } else {
          setResults({ kind: "error", message: s.error ?? "The provider request failed." });
          toast("info", "Search failed — reserved credits were refunded.");
        }
      })
      .catch((e) => toast("error", errorMessage(e)))
      .finally(() => setSearching(false));
  }

  function saveSearch() {
    const name = savedName.trim() || `Search ${new Date().toLocaleDateString()}`;
    const next = [...saved, { id: crypto.randomUUID(), name, filters: { ...filters } }];
    setSaved(next);
    setSavedName("");
    try {
      localStorage.setItem(`leadfinder-saved-${workspaceId}`, JSON.stringify(next));
    } catch {}
    toast("success", `Saved "${name}".`);
  }

  function deleteSaved(id: string) {
    const next = saved.filter((s) => s.id !== id);
    setSaved(next);
    try {
      localStorage.setItem(`leadfinder-saved-${workspaceId}`, JSON.stringify(next));
    } catch {}
  }

  function applySaved(s: SavedSearch) {
    setFilters({ ...s.filters });
  }

  function addToList() {
    if (!activeSearchId || !chosenListId) return;
    setAdding(true);
    leadFinderApi
      .addToList(workspaceId, activeSearchId, chosenListId)
      .then((r) => {
        setAddOpen(false);
        toast(
          "success",
          `${r.added} added · ${r.skippedDuplicates} duplicate(s) skipped · ${r.skippedSuppressed} suppressed`,
        );
      })
      .catch((e) => toast("error", errorMessage(e)))
      .finally(() => setAdding(false));
  }

  if (!workspaceId) return <Skeleton className="h-64" />;

  const eligibleCount =
    results.kind === "loaded"
      ? results.data.filter((c) => c.email && c.permittedUse !== "sandbox_only").length
      : 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-foreground text-xl font-semibold tracking-tight">Lead Finder</h1>
          <p className="text-muted-foreground mt-1 text-sm">
            Search agreement-backed data providers. Every result carries its source, confidence and
            permitted use.
          </p>
        </div>
        <div className="border-border bg-surface rounded-xl border px-4 py-2 text-right">
          <div className="text-muted-foreground text-xs font-medium">Credit balance</div>
          <div className="text-foreground text-lg font-semibold" data-testid="credit-balance">
            {credits.kind === "loaded" ? credits.data : credits.kind === "loading" ? "…" : "—"}
          </div>
        </div>
      </div>

      {providers.kind === "error" && <Alert variant="error">{providers.message}</Alert>}
      {providers.kind === "loaded" && providers.data.length === 0 && (
        <Alert variant="warning">
          No lead data providers are integrated yet. Only providers with a signed data agreement can
          be enabled.
        </Alert>
      )}

      <Card>
        <CardHeader
          eyebrow="Search"
          title="Find contacts"
          subtitle="Credits are reserved per search, charged per delivered result, and refunded when a search fails or returns nothing."
        />
        <div className="space-y-4 p-4 pt-0">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            {FILTER_KEYS.map((f) => {
              const unsupported = supported !== null && !supported.includes(f.key);
              return (
                <label key={f.key} className="block">
                  <span className="text-muted-foreground mb-1 block text-xs font-medium">
                    {f.label}
                    {unsupported && (
                      <span
                        className="text-warning ml-1"
                        title={`${f.label} is not supported by the active provider`}
                      >
                        · unsupported
                      </span>
                    )}
                  </span>
                  <Input
                    value={filters[f.key] ?? ""}
                    onChange={(e) => setFilters({ ...filters, [f.key]: e.target.value })}
                    placeholder={f.placeholder}
                    disabled={unsupported}
                    title={
                      unsupported
                        ? `The active provider does not support filtering by ${f.label}`
                        : undefined
                    }
                  />
                </label>
              );
            })}
          </div>
          {supported !== null && supported.length > 0 && (
            <p className="text-muted-foreground text-xs">
              {supported.length < FILTER_KEYS.length
                ? "Filters marked “unsupported” are not offered by the active provider and are disabled."
                : "All filters are supported by the active provider."}
            </p>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={runSearch} disabled={searching}>
              <Search size={15} className="mr-1.5" />
              {searching ? "Searching…" : "Search"}
            </Button>
            <div className="flex items-center gap-1.5">
              <Input
                value={savedName}
                onChange={(e) => setSavedName(e.target.value)}
                placeholder="Save this search as…"
                className="w-44"
              />
              <Button variant="secondary" onClick={saveSearch}>
                <Bookmark size={15} className="mr-1.5" />
                Save
              </Button>
            </div>
          </div>
          {saved.length > 0 && (
            <div className="flex flex-wrap items-center gap-2">
              {saved.map((s) => (
                <span
                  key={s.id}
                  className="border-border bg-surface-sunken inline-flex items-center gap-1.5 rounded-full border py-1 pr-1 pl-3 text-xs"
                >
                  <button className="hover:underline" onClick={() => applySaved(s)}>
                    {s.name}
                  </button>
                  <button
                    aria-label={`Delete saved search ${s.name}`}
                    className="text-muted-foreground hover:text-danger"
                    onClick={() => deleteSaved(s.id)}
                  >
                    <Trash2 size={12} />
                  </button>
                </span>
              ))}
            </div>
          )}
        </div>
      </Card>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader
            eyebrow="Results"
            title={activeSearchId ? "Search results" : "No search selected"}
            subtitle={
              results.kind === "loaded"
                ? `${results.data.length} contacts · ${eligibleCount} eligible to add`
                : undefined
            }
          />
          <div className="p-4 pt-0">
            {results.kind === "loading" && <Skeleton className="h-48" />}
            {results.kind === "error" && <Alert variant="error">{results.message}</Alert>}
            {results.kind === "loaded" && results.data.length === 0 && (
              <EmptyState
                icon={<Sparkles size={22} />}
                title="No results yet"
                description="Run a search to preview contacts with provenance, verification status and permitted use."
              />
            )}
            {results.kind === "loaded" && results.data.length > 0 && (
              <>
                {results.data.some((c) => c.permittedUse === "sandbox_only") && (
                  <Alert variant="warning">
                    <TriangleAlert size={14} className="mr-1.5 inline" />
                    Some results are sandbox data (sandbox-only). They are labeled for testing and
                    can never be added to outreach lists.
                  </Alert>
                )}
                <Table>
                  <THead>
                    <TR>
                      <TH>Contact</TH>
                      <TH>Email</TH>
                      <TH>Company</TH>
                      <TH>Confidence</TH>
                      <TH>Permitted use</TH>
                    </TR>
                  </THead>
                  <TBody>
                    {results.data.map((c) => (
                      <TR key={c.id}>
                        <TD>
                          <div className="text-foreground text-sm font-medium">{fullName(c)}</div>
                          <div className="text-muted-foreground text-xs">{c.jobTitle ?? "—"}</div>
                        </TD>
                        <TD>
                          {c.email ? (
                            <div className="flex items-center gap-1.5">
                              <span className="text-sm">{c.email}</span>
                              <Badge tone={verificationTone(c.emailVerification)}>
                                {c.emailVerification === "verified" && (
                                  <BadgeCheck size={11} className="mr-0.5 inline" />
                                )}
                                {c.emailVerification}
                              </Badge>
                            </div>
                          ) : (
                            <span className="text-muted-foreground text-xs">not provided</span>
                          )}
                        </TD>
                        <TD>
                          <div className="text-sm">{c.companyName ?? "—"}</div>
                          <div className="text-muted-foreground text-xs">
                            {[c.industry, c.region, c.companySize].filter(Boolean).join(" · ") ||
                              "—"}
                          </div>
                        </TD>
                        <TD>
                          <span className="text-sm font-medium">{c.confidence}</span>
                          <span className="text-muted-foreground text-xs"> / 100</span>
                        </TD>
                        <TD>
                          {c.permittedUse === "sandbox_only" ? (
                            <Badge tone="warning">
                              <ShieldAlert size={11} className="mr-0.5 inline" />
                              sandbox only
                            </Badge>
                          ) : (
                            <Badge tone="primary">licensed</Badge>
                          )}
                          <div className="text-muted-foreground mt-0.5 text-xs">
                            via {c.provider}
                          </div>
                        </TD>
                      </TR>
                    ))}
                  </TBody>
                </Table>
                <div className="mt-4 flex justify-end">
                  <Button
                    onClick={() => setAddOpen(true)}
                    disabled={eligibleCount === 0 || !activeSearchId}
                  >
                    Add eligible to list ({eligibleCount})
                  </Button>
                </div>
              </>
            )}
          </div>
        </Card>

        <Card>
          <CardHeader
            eyebrow="History"
            title="Recent searches"
            subtitle="Results stay retrievable; credits were settled per search."
          />
          <div className="p-4 pt-0">
            {searches.kind === "loading" && <Skeleton className="h-32" />}
            {searches.kind === "error" && <Alert variant="error">{searches.message}</Alert>}
            {searches.kind === "loaded" && searches.data.length === 0 && (
              <p className="text-muted-foreground text-sm">No searches yet.</p>
            )}
            {searches.kind === "loaded" && searches.data.length > 0 && (
              <ul className="divide-border divide-y">
                {searches.data.map((s) => (
                  <li key={s.id} className="flex items-center justify-between gap-2 py-2">
                    <button
                      className="min-w-0 flex-1 text-left"
                      onClick={() => loadResults(s.id)}
                      title="Load results"
                    >
                      <div className="flex items-center gap-2">
                        <Badge
                          tone={
                            s.status === "done"
                              ? "success"
                              : s.status === "failed"
                                ? "danger"
                                : "neutral"
                          }
                        >
                          {s.status}
                        </Badge>
                        <span className="text-muted-foreground text-xs">
                          {s.resultsCount} results · {s.creditsCharged} credits
                        </span>
                      </div>
                      {s.error && (
                        <div className="text-danger mt-0.5 truncate text-xs">{s.error}</div>
                      )}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </Card>
      </div>

      <Dialog
        open={addOpen}
        onOpenChange={setAddOpen}
        title="Add eligible contacts to a list"
        description="Duplicates already in this workspace and suppressed addresses are skipped automatically. Sandbox-only contacts are never added."
      >
        <div className="space-y-4">
          {lists.kind === "loaded" && lists.data.length === 0 && (
            <Alert variant="warning">Create a list on the Leads page first.</Alert>
          )}
          {lists.kind === "loaded" && lists.data.length > 0 && (
            <label className="block">
              <span className="text-muted-foreground mb-1 block text-xs font-medium">
                Target list
              </span>
              <select
                value={chosenListId}
                onChange={(e) => setChosenListId(e.target.value)}
                className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 text-sm"
              >
                {lists.data.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setAddOpen(false)}>
              Cancel
            </Button>
            <Button onClick={addToList} disabled={adding || !chosenListId}>
              {adding ? "Adding…" : "Add to list"}
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}
