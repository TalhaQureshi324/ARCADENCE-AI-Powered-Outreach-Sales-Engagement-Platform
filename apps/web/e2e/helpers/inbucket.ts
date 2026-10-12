import { expect } from "@playwright/test";

const MAILPIT_API = process.env.MAILPIT_API ?? "http://127.0.0.1:54324/api/v1";

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
