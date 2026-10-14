"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Download, Upload } from "lucide-react";

import { Alert } from "@/components/ui/alert";
import { Badge, type BadgeTone } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { TBody, TD, TH, THead, TR, Table } from "@/components/ui/table";
import { useToast } from "@/components/ui/toast";
import {
  ApiError,
  importsApi,
  leadListsApi,
  type ImportJobDto,
  type LeadListDto,
} from "@/lib/api/client";

type LoadState<T> =
  { kind: "loading" } | { kind: "loaded"; data: T } | { kind: "error"; message: string };

function errorMessage(e: unknown): string {
  return e instanceof ApiError ? e.message : "Something went wrong — is the API running?";
}

function isUnauthorized(e: unknown): boolean {
  return e instanceof ApiError && e.isUnauthorized;
}

const TARGET_FIELDS = [
  { value: "email", label: "Email (required)" },
  { value: "first_name", label: "First name" },
  { value: "last_name", label: "Last name" },
  { value: "job_title", label: "Job title" },
  { value: "company", label: "Company (creates if missing)" },
  { value: "phone", label: "Phone" },
  { value: "region", label: "Region" },
  { value: "timezone", label: "Timezone" },
  { value: "website", label: "Website" },
  { value: "notes", label: "Notes" },
];

const STATUS_META: Record<string, { tone: BadgeTone; label: string }> = {
  created: { tone: "neutral", label: "Mapping" },
  queued: { tone: "primary", label: "Queued" },
  running: { tone: "primary", label: "Running" },
  completed: { tone: "success", label: "Completed" },
  failed: { tone: "danger", label: "Failed" },
  cancelled: { tone: "neutral", label: "Cancelled" },
};

export function ImportsTab({ workspaceId, myRole }: { workspaceId: string; myRole: string }) {
  const router = useRouter();
  const { toast } = useToast();
  const editable = myRole !== "viewer";
  const [jobs, setJobs] = useState<LoadState<ImportJobDto[]>>({ kind: "loading" });
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [headers, setHeaders] = useState<string[] | null>(null);
  const [preview, setPreview] = useState<string[][]>([]);
  const [jobId, setJobId] = useState<string | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [strategy, setStrategy] = useState("skip");
  const [targetListId, setTargetListId] = useState("");
  const [lists, setLists] = useState<LeadListDto[]>([]);
  const [mappingError, setMappingError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const reload = useCallback(() => {
    importsApi
      .list(workspaceId)
      .then((data) => setJobs({ kind: "loaded", data }))
      .catch((e) => setJobs({ kind: "error", message: errorMessage(e) }));
  }, [workspaceId]);

  useEffect(() => {
    reload();
  }, [reload]);

  // poll while any job is queued/running
  useEffect(() => {
    const active =
      jobs.kind === "loaded" &&
      jobs.data.some((j) => j.status === "queued" || j.status === "running");
    if (!active) return;
    const timer = setInterval(reload, 1500);
    return () => clearInterval(timer);
  }, [jobs, reload]);

  useEffect(() => {
    leadListsApi
      .list(workspaceId)
      .then(setLists)
      .catch(() => setLists([]));
  }, [workspaceId]);

  async function upload() {
    if (!file) return;
    setUploading(true);
    setMappingError(null);
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      const result = await importsApi.upload(workspaceId, file.name, bytes);
      setHeaders(result.headers);
      setPreview(result.preview);
      setJobId(result.job.id);
      setMapping(
        Object.fromEntries(result.headers.map((_, i) => [String(i), i === 0 ? "email" : ""])),
      );
      toast("success", "File parsed — map the columns to continue.");
      reload();
    } catch (e) {
      if (isUnauthorized(e)) {
        const { createClient } = await import("@/lib/supabase/client");
        await createClient().auth.signOut();
        router.push("/login?expired=1");
        return;
      }
      setMappingError(errorMessage(e));
    } finally {
      setUploading(false);
    }
  }

  async function startImport() {
    if (!jobId) return;
    setStarting(true);
    setMappingError(null);
    try {
      await importsApi.start(workspaceId, jobId, {
        mapping,
        duplicateStrategy: strategy,
        targetListId: targetListId || undefined,
      });
      toast("success", "Import started.");
      setHeaders(null);
      setPreview([]);
      setJobId(null);
      setFile(null);
      if (fileInput.current) fileInput.current.value = "";
      reload();
    } catch (e) {
      setMappingError(errorMessage(e));
    } finally {
      setStarting(false);
    }
  }

  return (
    <>
      <Card>
        <CardHeader
          eyebrow="Lead management"
          title="CSV import"
          subtitle="Upload a CSV, map its columns, and Arcadence imports contacts with duplicate handling and a per-row error report."
        />
        {editable ? (
          <div className="flex flex-wrap items-center gap-2">
            <input
              aria-label="CSV file"
              type="file"
              accept=".csv,text/csv"
              ref={fileInput}
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              className="text-muted-foreground text-sm"
            />
            <Button onClick={upload} loading={uploading} disabled={!file}>
              <Upload size={14} /> Upload &amp; preview
            </Button>
          </div>
        ) : (
          <Alert variant="info">Importing requires the member role or above.</Alert>
        )}
        {mappingError && (
          <div className="mt-3">
            <Alert variant="error">{mappingError}</Alert>
          </div>
        )}
        {headers && (
          <div className="border-border mt-4 rounded-lg border p-4">
            <h3 className="text-foreground mb-3 text-sm font-semibold">Column mapping</h3>
            <div className="space-y-2">
              {headers.map((h, i) => (
                <div key={`${i}-${h}`} className="flex items-center gap-2 text-sm">
                  <span className="text-muted-foreground w-48 truncate">
                    {h || `Column ${i + 1}`}
                  </span>
                  <select
                    aria-label={`Target field for ${h}`}
                    value={mapping[String(i)] ?? ""}
                    onChange={(e) => setMapping((m) => ({ ...m, [String(i)]: e.target.value }))}
                    className="border-border bg-surface text-foreground rounded-lg border px-2 py-1 text-xs"
                  >
                    <option value="">— ignore —</option>
                    {TARGET_FIELDS.map((f) => (
                      <option key={f.value} value={f.value}>
                        {f.label}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
            {preview.length > 0 && (
              <div className="mt-3">
                <div className="text-muted-foreground mb-1 text-xs font-semibold uppercase">
                  Preview (first rows)
                </div>
                <pre className="border-border bg-surface-sunken max-h-32 overflow-auto rounded border p-2 text-xs">
                  {preview.map((r) => r.join(" | ")).join("\n")}
                </pre>
              </div>
            )}
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <select
                aria-label="Duplicate strategy"
                value={strategy}
                onChange={(e) => setStrategy(e.target.value)}
                className="border-border bg-surface text-foreground rounded-lg border px-2 py-1 text-sm"
              >
                <option value="skip">Skip duplicates</option>
                <option value="update">Update non-empty fields</option>
                <option value="reject">Reject duplicates (error report)</option>
              </select>
              <select
                aria-label="Target list"
                value={targetListId}
                onChange={(e) => setTargetListId(e.target.value)}
                className="border-border bg-surface text-foreground rounded-lg border px-2 py-1 text-sm"
              >
                <option value="">No target list</option>
                {lists.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </select>
              <Button onClick={startImport} loading={starting}>
                Start import
              </Button>
            </div>
          </div>
        )}
      </Card>

      <Card>
        <CardHeader
          eyebrow="History"
          title="Import jobs"
          subtitle="Progress, totals and downloadable error reports."
        />
        {jobs.kind === "loading" && <Skeleton className="h-32" />}
        {jobs.kind === "error" && <Alert variant="error">{jobs.message}</Alert>}
        {jobs.kind === "loaded" &&
          (jobs.data.length === 0 ? (
            <EmptyState
              icon={<Upload size={22} />}
              title="No imports yet"
              description="Upload a CSV above to create the first import job."
            />
          ) : (
            <Table>
              <THead>
                <TR>
                  <TH>File</TH>
                  <TH>Status</TH>
                  <TH>Progress</TH>
                  <TH>Inserted</TH>
                  <TH>Skipped</TH>
                  <TH>Invalid</TH>
                  <TH>Duplicates</TH>
                  <TH>
                    <span className="sr-only">Actions</span>
                  </TH>
                </TR>
              </THead>
              <TBody>
                {jobs.data.map((j) => {
                  const meta = STATUS_META[j.status] ?? {
                    tone: "neutral" as BadgeTone,
                    label: j.status,
                  };
                  return (
                    <TR key={j.id}>
                      <TD>
                        <span className="text-foreground text-sm font-medium">{j.fileName}</span>
                        {j.errorMessage && (
                          <div className="text-danger text-xs">{j.errorMessage}</div>
                        )}
                      </TD>
                      <TD>
                        <Badge tone={meta.tone}>{meta.label}</Badge>
                      </TD>
                      <TD>
                        <span className="text-muted-foreground text-sm">
                          {j.processedRows}/{j.totalRows}
                        </span>
                      </TD>
                      <TD>
                        <Badge tone="success">{j.insertedRows}</Badge>
                      </TD>
                      <TD>
                        <Badge tone="neutral">{j.skippedRows}</Badge>
                      </TD>
                      <TD>
                        <Badge tone={j.invalidRows > 0 ? "warning" : "neutral"}>
                          {j.invalidRows}
                        </Badge>
                      </TD>
                      <TD>
                        <Badge tone="neutral">{j.duplicateRows}</Badge>
                      </TD>
                      <TD>
                        <div className="flex items-center justify-end gap-1">
                          {editable && (j.status === "failed" || j.status === "completed") && (
                            <Button
                              variant="ghost"
                              onClick={async () => {
                                await importsApi.retry(workspaceId, j.id);
                                toast(
                                  "success",
                                  "Import retried (already-imported rows are skipped).",
                                );
                                reload();
                              }}
                            >
                              Retry
                            </Button>
                          )}
                          {j.invalidRows > 0 && (
                            <a
                              href={`${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}/api/v1/workspaces/${workspaceId}/imports/${j.id}/errors.csv`}
                              className="text-primary hover:underline"
                              aria-label={`Download error report for ${j.fileName}`}
                              target="_blank"
                              rel="noreferrer"
                            >
                              <Download size={14} />
                            </a>
                          )}
                        </div>
                      </TD>
                    </TR>
                  );
                })}
              </TBody>
            </Table>
          ))}
      </Card>
    </>
  );
}
