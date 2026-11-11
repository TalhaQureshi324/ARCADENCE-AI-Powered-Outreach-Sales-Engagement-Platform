import { expect } from "@playwright/test";

const MAILPIT_API = process.env.MAILPIT_API ?? "http://127.0.0.1:44324/api/v1";

interface MailpitSummary {
  messages: { ID: string; To: { Address: string }[]; Subject: string }[];
}

interface MailpitMessage {
  Text?: string;
  HTML: string;
  Snippet?: string;
}

/** Waits for an email to `to` (optionally filtered by subject) and returns the newest matching link. */
export async function latestEmailLink(
  to: string,
  urlPattern: RegExp,
  subjectRe?: RegExp,
): Promise<string> {
  const deadline = Date.now() + 30_000;

  while (Date.now() < deadline) {
    const list = await fetch(`${MAILPIT_API}/search?query=to:${encodeURIComponent(to)}`);
    if (list.ok) {
      const summary = (await list.json()) as MailpitSummary;
      const candidates = (summary.messages ?? []).filter((m) =>
        subjectRe ? subjectRe.test(m.Subject) : true,
      );
      for (const message of candidates) {
        const detail = await fetch(`${MAILPIT_API}/message/${message.ID}`);
        if (detail.ok) {
          const body = (await detail.json()) as MailpitMessage;
          const haystack = `${body.Text ?? ""} ${body.HTML ?? ""} ${body.Snippet ?? ""}`;
          const match = haystack.match(urlPattern);
          if (match) {
            // Undo quoted-printable soft line breaks and HTML entities.
            return match[0]
              .replace(/&amp;/g, "&")
              .replace(/=\r?\n/g, "")
              .replace(/[ "'<>]+$/, "");
          }
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1_500));
  }

  throw new Error(
    `No email addressed to "${to}" with a matching link arrived in Mailpit within 30s`,
  );
}

/** Unique per-run address. */
export function testEmail(prefix: string): string {
  const stamp = Date.now().toString(36) + Math.floor(Math.random() * 1_000).toString(36);
  return `${prefix}-${stamp}@arcadence.test`;
}

export const STRONG_PASSWORD = "Passw0rd-e2e";

export async function expectVisibleText(page: import("@playwright/test").Page, text: string) {
  await expect(page.getByText(text)).toBeVisible();
}

/** The Founding 10,000 offer modal (Phase 14.2B) appears for fresh unclaimed
 * users ~1.2s after a workspace exists and blocks clicks behind its overlay.
 * Deterministic handling: write the 7-day snooze immediately (stops any
 * mounted modal's next check), persist it for future navigations, then
 * dismiss a modal that is already on screen (retried — it mounts late under
 * API latency).
 */
export async function dismissFoundingOffer(page: import("@playwright/test").Page) {
  const snooze = () =>
    window.localStorage.setItem(
      "arc-founding-snoozed",
      String(Date.now() + 7 * 24 * 3600 * 1000)
    );
  await page.evaluate(snooze).catch(() => {});
  await page.addInitScript(snooze);
  const maybeLater = page.getByRole("button", { name: "Maybe later" });
  for (let round = 0; round < 10; round++) {
    const visible = await maybeLater.isVisible().catch(() => false);
    if (!visible) {
      await page.waitForTimeout(1000);
      continue;
    }
    await maybeLater.click();
    await maybeLater.waitFor({ state: "hidden", timeout: 5000 }).catch(() => {});
    return;
  }
}
