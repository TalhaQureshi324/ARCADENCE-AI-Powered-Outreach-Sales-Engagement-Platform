import { afterEach, describe, expect, it, vi } from "vitest";

import { probeApi } from "@/lib/api";

function jsonResponse(payload: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: () => Promise.resolve(payload),
  } as unknown as Response;
}

const health = { service: "arcadence-api", version: "0.1.0-SNAPSHOT", status: "up" };
const ready = {
  service: "arcadence-api",
  version: "0.1.0-SNAPSHOT",
  status: "ready",
  checks: { process: "ok" },
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("probeApi", () => {
  it("returns ok when health and readiness both pass", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) =>
        Promise.resolve(jsonResponse(url.endsWith("/ready") ? ready : health)),
      ),
    );

    const result = await probeApi("http://test");
    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.health.service).toBe("arcadence-api");
      expect(result.ready.checks.process).toBe("ok");
    }
  });

  it("returns degraded when the API responds but is not ready", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn((url: string) =>
        Promise.resolve(
          jsonResponse(
            url.endsWith("/ready") ? { ...ready, status: "not_ready", checks: {} } : health,
          ),
        ),
      ),
    );

    const result = await probeApi("http://test");
    expect(result.kind).toBe("degraded");
  });

  it("returns unreachable when the network fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.reject(new Error("connection refused"))),
    );

    const result = await probeApi("http://test");
    expect(result.kind).toBe("unreachable");
    if (result.kind === "unreachable") {
      expect(result.message).toContain("connection refused");
    }
  });

  it("returns unreachable on non-2xx responses", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() => Promise.resolve(jsonResponse({}, false, 500))),
    );

    const result = await probeApi("http://test");
    expect(result.kind).toBe("unreachable");
  });
});
