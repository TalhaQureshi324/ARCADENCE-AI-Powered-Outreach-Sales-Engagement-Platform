import { describe, expect, it, vi } from "vitest";

import { ApiError, workspacesApi } from "@/lib/api/client";

function jsonResponse(payload: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    text: () => Promise.resolve(JSON.stringify(payload)),
  } as unknown as Response;
}

const session = { access_token: "token-123" };

vi.mock("@/lib/supabase/client", () => ({
  createClient: () => ({
    auth: { getSession: () => Promise.resolve({ data: { session } }) },
  }),
}));

describe("workspacesApi", () => {
  it("attaches the Supabase access token and lists workspaces", async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse([{ id: "w1", name: "WS", role: "owner" }])),
    );
    vi.stubGlobal("fetch", fetchMock);

    const list = await workspacesApi.list();
    expect(list).toHaveLength(1);
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer token-123");
  });

  it("throws ApiError with envelope details on failure", async () => {
    vi.stubGlobal("fetch", () =>
      Promise.resolve(
        jsonResponse(
          {
            error: {
              code: "workspace_not_found",
              message: "The requested workspace does not exist.",
            },
          },
          false,
          404,
        ),
      ),
    );

    const error = await workspacesApi.list().catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe("workspace_not_found");
    expect((error as ApiError).status).toBe(404);
  });

  it("sends a JSON body on create", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(jsonResponse({ id: "w2" })));
    vi.stubGlobal("fetch", fetchMock);

    await workspacesApi.create("Acme");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("/api/v1/workspaces");
    expect(init.method).toBe("POST");
    expect(init.body).toContain("Acme");
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});
