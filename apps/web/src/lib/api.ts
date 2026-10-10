export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export interface HealthPayload {
  service: string;
  version: string;
  status: string;
}

export interface ReadyPayload {
  service: string;
  version: string;
  status: string;
  checks: Record<string, string>;
}

export type ApiProbeResult =
  | { kind: "ok"; health: HealthPayload; ready: ReadyPayload }
  | { kind: "degraded"; message: string; health: HealthPayload | null }
  | { kind: "unreachable"; message: string };

async function getJson<T>(url: string): Promise<T> {
  const res = await fetch(url, { cache: "no-store" });
  if (!res.ok) {
    throw new Error(`${url} responded with HTTP ${res.status}`);
  }
  return (await res.json()) as T;
}

/** Probes the Scala API's health endpoints. Never throws: failures become typed results. */
export async function probeApi(baseUrl: string = API_BASE_URL): Promise<ApiProbeResult> {
  try {
    const [health, ready] = await Promise.all([
      getJson<HealthPayload>(`${baseUrl}/health`),
      getJson<ReadyPayload>(`${baseUrl}/ready`),
    ]);
    if (health.status === "up" && ready.status === "ready") {
      return { kind: "ok", health, ready };
    }
    return { kind: "degraded", message: "API is reachable but reports a degraded state.", health };
  } catch (error) {
    return {
      kind: "unreachable",
      message: error instanceof Error ? error.message : "Unknown network error.",
    };
  }
}
