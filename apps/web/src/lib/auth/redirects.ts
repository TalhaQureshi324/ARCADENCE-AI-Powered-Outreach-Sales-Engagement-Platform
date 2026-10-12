/**
 * Only allow relative, same-origin redirect targets. Blocks open redirects
 * via absolute URLs, protocol-relative hosts, backslashes and CRLF.
 */
export function sanitizeRedirect(raw: string | null | undefined, fallback = "/app"): string {
  if (!raw) return fallback;
  if (!raw.startsWith("/")) return fallback;
  if (raw.startsWith("//")) return fallback;
  if (raw.includes("\\")) return fallback;
  if (/[\r\n]/.test(raw)) return fallback;
  return raw;
}
