import { describe, expect, it } from "vitest";

import { sanitizeRedirect } from "@/lib/auth/redirects";

describe("sanitizeRedirect", () => {
  it("allows safe relative paths", () => {
    expect(sanitizeRedirect("/app")).toBe("/app");
    expect(sanitizeRedirect("/onboarding?step=1")).toBe("/onboarding?step=1");
  });

  it("falls back when no target is given", () => {
    expect(sanitizeRedirect(null)).toBe("/app");
    expect(sanitizeRedirect(undefined, "/other")).toBe("/other");
    expect(sanitizeRedirect("")).toBe("/app");
  });

  it("blocks absolute and external URLs (open-redirect)", () => {
    expect(sanitizeRedirect("https://evil.example.com")).toBe("/app");
    expect(sanitizeRedirect("http://evil.example.com/app")).toBe("/app");
    expect(sanitizeRedirect("javascript:alert(1)")).toBe("/app");
  });

  it("blocks protocol-relative hosts", () => {
    expect(sanitizeRedirect("//evil.example.com")).toBe("/app");
  });

  it("blocks backslash tricks", () => {
    expect(sanitizeRedirect("/\\evil.example.com")).toBe("/app");
    expect(sanitizeRedirect("/app\\..\\..")).toBe("/app");
  });

  it("blocks CRLF injection", () => {
    expect(sanitizeRedirect("/app\r\nSet-Cookie: x=1")).toBe("/app");
  });
});
