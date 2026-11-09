"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Archive, Check, RefreshCw, Send } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { useWorkspaces } from "@/components/shell/workspace-context";
import {
  ApiError,
  inboxApi,
  type InboxThreadDetailDto,
  type InboxThreadDto,
} from "@/lib/api/client";

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

const CLASSIFICATION_META: Record<string, { label: string; tone: BadgeTone }> = {
  interested: { label: "Interested", tone: "success" },
  not_interested: { label: "Not interested", tone: "danger" },
  question: { label: "Question", tone: "primary" },
  meeting_requested: { label: "Meeting", tone: "primary" },
  unsubscribe: { label: "Unsubscribe", tone: "danger" },
  out_of_office: { label: "OOO", tone: "warning" },
  automated: { label: "Automated", tone: "neutral" },
  bounce: { label: "Bounced", tone: "danger" },
  unknown: { label: "Unclassified", tone: "neutral" },
};

export function InboxContent() {
  const router = useRouter();
  void router;
  const { toast } = useToast();
  const { workspaces, selected } = useWorkspaces();
  const workspaceId = selected?.id ?? "";

  const [threads, setThreads] = useState<InboxThreadDto[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [classification, setClassification] = useState("");
  const [q, setQ] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<InboxThreadDetailDto | null>(null);
  const [replyText, setReplyText] = useState("");
  const [busy, setBusy] = useState(false);
  const [syncing, setSyncing] = useState(false);

  const threadsRef = useRef<InboxThreadDto[]>([]);
  useEffect(() => {
    threadsRef.current = threads;
  }, [threads]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = useCallback(() => {
    if (!workspaceId) return;
    inboxApi
      .list(workspaceId, { classification: classification || undefined, q: q || undefined })
      .then((page) => {
        setThreads(page.items);
        setNextCursor(page.nextCursor);
      })
      .catch((e) => setLoadError(errorMessage(e)));
  }, [workspaceId, classification, q]);

  const loadMore = useCallback(() => {
    if (!workspaceId || !nextCursor || loadingMore) return;
    setLoadingMore(true);
    inboxApi
      .list(workspaceId, {
        classification: classification || undefined,
        q: q || undefined,
        cursor: nextCursor,
      })
      .then((page) => {
        setThreads([...threadsRef.current, ...page.items]);
        setNextCursor(page.nextCursor);
      })
      .catch((e) => toast("error", errorMessage(e)))
      .finally(() => setLoadingMore(false));
  }, [workspaceId, nextCursor, loadingMore, classification, q]);

  useEffect(() => {
    Promise.resolve().then(load);
  }, [load]);

  const openThread = useCallback(
    (id: string) => {
      setSelectedId(id);
      setReplyText("");
      inboxApi
        .get(workspaceId, id)
        .then(setDetail)
        .catch((e) => toast("error", errorMessage(e)));
    },
    [workspaceId],
  );

  async function sync() {
    setSyncing(true);
    try {
      await inboxApi.sync(workspaceId);
      toast("success", "Mailboxes synced.");
      load();
    } catch (e) {
      toast("error", errorMessage(e));
    } finally {
      setSyncing(false);
    }
  }

  async function sendReply() {
    if (!selectedId || !replyText.trim()) return;
    setBusy(true);
    try {
      await inboxApi.reply(workspaceId, selectedId, replyText.trim());
      toast("success", "Reply sent.");
      setReplyText("");
      openThread(selectedId);
    } catch (e) {
      toast("error", errorMessage(e));
    } finally {
      setBusy(false);
    }
  }

  async function archive(id: string) {
    try {
      await inboxApi.archive(workspaceId, id);
      toast("success", "Archived.");
      setSelectedId(null);
      setDetail(null);
      load();
    } catch (e) {
      toast("error", errorMessage(e));
    }
  }

  async function markRead(id: string) {
    try {
      await inboxApi.markRead(workspaceId, id);
      toast("success", "Marked as read.");
      setThreads(threadsRef.current.map((t) => (t.id === id ? { ...t, unread: false } : t)));
      if (detail && detail.thread.id === id) {
        setDetail({ ...detail, thread: { ...detail.thread, unread: false } });
      }
    } catch (e) {
      toast("error", errorMessage(e));
    }
  }

  if (workspaces === null) return <Skeleton className="h-64" />;
  if (!selected)
    return (
      <EmptyState
        icon={<Send size={22} />}
        title="No workspace selected"
        description="Create a workspace to use the unified inbox."
      />
    );

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader
          eyebrow={`Workspace · /${selected.slug}`}
          title="Unibox"
          subtitle="Inbound replies across every connected mailbox, with campaign context and one-click replies."
        />
        <div className="mb-3 flex flex-wrap items-center gap-2 px-6">
          <select
            aria-label="Filter by classification"
            value={classification}
            onChange={(e) => setClassification(e.target.value)}
            className="border-border bg-surface text-foreground rounded-lg border px-2 py-2 text-sm"
          >
            <option value="">All classifications</option>
            {Object.keys(CLASSIFICATION_META).map((k) => (
              <option key={k} value={k}>
                {CLASSIFICATION_META[k].label}
              </option>
            ))}
          </select>
          <form
            className="flex flex-1 items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              load();
            }}
          >
            <Input
              aria-label="Search conversations"
              placeholder="Search subject or sender…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <Button type="submit" variant="secondary">
              Search
            </Button>
          </form>
          <Button variant="secondary" onClick={() => void sync()} disabled={syncing}>
            <RefreshCw size={14} /> {syncing ? "Syncing…" : "Sync mailboxes"}
          </Button>
        </div>
        {loadError && <Alert variant="error">{loadError}</Alert>}
        {threads === null && !loadError && <Skeleton className="mx-6 mb-6 h-40" />}
        {threads !== null && threads.length === 0 && (
          <div className="px-6 pb-6">
            <EmptyState
              icon={<Send size={20} />}
              title="No conversations"
              description="Replies land here automatically after a mailbox sync."
            />
          </div>
        )}
        {threads !== null && threads.length > 0 && (
          <div className="divide-border mx-6 mb-6 divide-y">
            {threads.map((t) => {
              const meta = CLASSIFICATION_META[t.classification] ?? {
                label: t.classification,
                tone: "neutral" as BadgeTone,
              };
              return (
                <button
                  key={t.id}
                  onClick={() => openThread(t.id)}
                  className={`hover:bg-surface-raised w-full rounded-lg px-3 py-2 text-left ${
                    selectedId === t.id ? "bg-surface-raised" : ""
                  }`}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-foreground text-sm font-medium">
                      {t.unread ? "● " : ""}
                      {t.contactEmail ?? "unknown sender"}
                    </span>
                    <Badge tone={meta.tone}>{meta.label}</Badge>
                  </div>
                  <div className="text-muted-foreground truncate text-xs">
                    {t.subject} · {t.campaignName ?? "no campaign"} ·{" "}
                    {new Date(t.lastMessageAt).toLocaleString()}
                  </div>
                </button>
              );
            })}
            {nextCursor && (
              <div className="mt-3 flex justify-center">
                <Button variant="secondary" onClick={loadMore} disabled={loadingMore}>
                  {loadingMore ? "Loading…" : "Load more"}
                </Button>
              </div>
            )}
          </div>
        )}
      </Card>

      {detail && (
        <Card>
          <CardHeader
            eyebrow={detail.thread.campaignName ?? "No campaign"}
            title={detail.thread.subject}
            subtitle={`To ${detail.thread.contactEmail ?? "unknown"}`}
          />
          <div className="space-y-3 px-6 pb-3">
            {detail.messages.map((m) => (
              <div key={m.id} className="border-border bg-surface rounded-lg border p-3">
                <div className="text-muted-foreground mb-1 flex justify-between text-xs">
                  <span>
                    {m.direction === "inbound" ? "From" : "You"}: {m.fromAddress}
                  </span>
                  <span>{new Date(m.occurredAt).toLocaleString()}</span>
                </div>
                <div className="text-foreground text-sm whitespace-pre-wrap">
                  {m.bodyText || m.bodyHtml.replace(/<[^>]+>/g, " ") || "(no body fetched)"}
                </div>
              </div>
            ))}
          </div>
          <div className="space-y-2 px-6 pb-6">
            <textarea
              aria-label="Reply body"
              placeholder="Write a reply…"
              value={replyText}
              onChange={(e) => setReplyText(e.target.value)}
              rows={4}
              className="border-border bg-surface text-foreground w-full rounded-lg border px-3 py-2 text-sm"
            />
            {detail.notes.length > 0 && (
              <div className="space-y-2">
                <h4 className="text-muted-foreground text-xs font-semibold tracking-wide uppercase">
                  Internal notes
                </h4>
                {detail.notes.map((n) => (
                  <div
                    key={n.id}
                    className="border-border bg-surface-sunken rounded-lg border px-3 py-2"
                  >
                    <div className="text-foreground text-sm">{n.body}</div>
                    <div className="text-muted-foreground mt-1 text-xs">
                      {new Date(n.createdAt).toLocaleString()}
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div className="flex gap-2">
              <Button onClick={() => void sendReply()} disabled={busy || !replyText.trim()}>
                <Send size={14} /> {busy ? "Sending…" : "Send reply"}
              </Button>
              <Button
                variant="secondary"
                aria-label="Mark done and archive"
                onClick={() => archive(detail.thread.id)}
              >
                <Archive size={14} /> Archive
              </Button>
              <Button
                variant="ghost"
                aria-label="Mark read"
                onClick={() => void markRead(detail.thread.id)}
              >
                <Check size={14} /> Mark read
              </Button>
            </div>
          </div>
        </Card>
      )}
    </div>
  );
}
