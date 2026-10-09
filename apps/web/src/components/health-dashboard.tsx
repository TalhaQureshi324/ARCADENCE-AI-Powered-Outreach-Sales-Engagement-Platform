"use client";

import { useEffect, useState } from "react";

import { StatusBadge, type StatusTone } from "@/components/status-badge";
import { API_BASE_URL, probeApi, type ApiProbeResult } from "@/lib/api";

const probeResultTone: Record<ApiProbeResult["kind"], StatusTone> = {
  ok: "ok",
  degraded: "down",
  unreachable: "down",
};

const probeResultLabel: Record<ApiProbeResult["kind"], string> = {
  ok: "API up",
  degraded: "Degraded",
  unreachable: "API unreachable",
};

export function HealthDashboard() {
  const [result, setResult] = useState<ApiProbeResult | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    void probeApi().then((probe) => {
      if (!cancelled) {
        setResult(probe);
        setLoading(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const recheck = () => {
    setLoading(true);
    void probeApi().then((probe) => {
      setResult(probe);
      setLoading(false);
    });
  };

  const tone: StatusTone = loading ? "loading" : result ? probeResultTone[result.kind] : "loading";
  const label = loading ? "Checking…" : result ? probeResultLabel[result.kind] : "Checking…";

  return (
    <section
      aria-busy={loading}
      aria-labelledby="health-heading"
      className="w-full max-w-md rounded-2xl border border-neutral-200 bg-white p-6 shadow-sm dark:border-neutral-800 dark:bg-neutral-900"
    >
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-xs font-medium tracking-wider text-neutral-500 uppercase dark:text-neutral-400">
            Arcadence · Phase 0 foundation
          </p>
          <h1
            id="health-heading"
            className="mt-1 text-xl font-semibold text-neutral-900 dark:text-neutral-50"
          >
            System health
          </h1>
        </div>
        <StatusBadge tone={tone} label={label} />
      </div>

      <div aria-live="polite" className="mt-6 space-y-4 text-sm">
        {loading && <p className="text-neutral-500 dark:text-neutral-400">Contacting the API…</p>}

        {!loading && result?.kind === "ok" && (
          <dl className="space-y-3">
            <div className="flex justify-between gap-4">
              <dt className="text-neutral-500 dark:text-neutral-400">Service</dt>
              <dd className="font-medium text-neutral-900 dark:text-neutral-100">
                {result.health.service}
              </dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-neutral-500 dark:text-neutral-400">Version</dt>
              <dd className="font-medium text-neutral-900 dark:text-neutral-100">
                {result.health.version}
              </dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-neutral-500 dark:text-neutral-400">Liveness</dt>
              <dd className="font-medium text-neutral-900 dark:text-neutral-100">
                {result.health.status}
              </dd>
            </div>
            <div>
              <dt className="text-neutral-500 dark:text-neutral-400">Readiness checks</dt>
              <dd className="mt-2 space-y-1.5">
                {Object.entries(result.ready.checks).map(([name, status]) => (
                  <div key={name} className="flex items-center justify-between gap-4">
                    <span className="text-neutral-700 dark:text-neutral-300">{name}</span>
                    <StatusBadge tone={status === "ok" ? "ok" : "down"} label={status} />
                  </div>
                ))}
              </dd>
            </div>
          </dl>
        )}

        {!loading && result?.kind === "unreachable" && (
          <div className="rounded-lg bg-red-50 p-3 text-red-700 dark:bg-red-950/40 dark:text-red-300">
            <p className="font-medium">The API could not be reached.</p>
            <p className="mt-1 break-words">{result.message}</p>
            <p className="mt-2">
              Expected at{" "}
              <code className="rounded bg-red-100 px-1 dark:bg-red-900/50">{API_BASE_URL}</code> —
              start it with{" "}
              <code className="rounded bg-red-100 px-1 dark:bg-red-900/50">scripts/dev-api.sh</code>
              .
            </p>
          </div>
        )}

        {!loading && result?.kind === "degraded" && (
          <div className="rounded-lg bg-amber-50 p-3 text-amber-800 dark:bg-amber-950/40 dark:text-amber-300">
            <p className="font-medium">{result.message}</p>
          </div>
        )}
      </div>

      <div className="mt-6 flex items-center justify-between border-t border-neutral-200 pt-4 dark:border-neutral-800">
        <span className="text-xs text-neutral-500 dark:text-neutral-400">API: {API_BASE_URL}</span>
        <button
          type="button"
          onClick={recheck}
          disabled={loading}
          className="rounded-lg bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-indigo-500 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Re-check
        </button>
      </div>
    </section>
  );
}
