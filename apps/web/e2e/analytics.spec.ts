import { execSync } from "node:child_process";

import { expect, test } from "@playwright/test";

import {
  dismissFoundingOffer,
  STRONG_PASSWORD,
  latestEmailLink,
  testEmail,
} from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:44321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Phase 15.5 area 19 — analytics accuracy: seed a known campaign_daily_stats
 * row directly and verify the dashboard renders exactly those numbers.
 */
test("analytics renders the exact seeded daily numbers", async ({ page }) => {
  test.setTimeout(180_000);
  const email = testEmail("analyst");

  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Analytics User");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
  await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
  await page.goto(link);
  await page.getByLabel("Display name").fill("Analytics User");
  await page.getByRole("button", { name: "Continue" }).click();

  await page.getByRole("button", { name: "New workspace" }).first().click();
  await page.getByLabel("Workspace name").fill("Analytics WS");
  await page.getByRole("button", { name: "Create workspace" }).click();
  await expect(page.getByText("Analytics WS").first()).toBeVisible();
  await dismissFoundingOffer(page);

  // Honest empty state first.
  await page.getByRole("link", { name: "Analytics" }).click();
  await expect(page.getByText("No data in range").first()).toBeVisible({
    timeout: 15_000,
  });

  // Seed one day of known numbers for THIS workspace's first campaign shell.
  const wsId = execSync(
    `docker exec supabase_db_arcadence psql -U postgres -d postgres -t -A -c ` +
      `"select id from public.workspace where name = 'Analytics WS' order by created_at desc limit 1"`,
    { stdio: "pipe" },
  )
    .toString()
    .trim();
  expect(wsId).toMatch(/[0-9a-f-]{36}/);
  execSync(
    `docker exec supabase_db_arcadence psql -U postgres -d postgres -t -A -c ` +
      `"insert into public.campaign (id, workspace_id, created_by, name, status) ` +
      `values (gen_random_uuid(), '${wsId}', null, 'Seeded campaign', 'active'); ` +
      `insert into public.campaign_daily_stats (workspace_id, campaign_id, day, ` +
      `send_scheduled, accepted, provider_rejected, bounce_hard, bounce_soft, ` +
      `replies, reply_classified, meeting_booked, unsubscribe, unique_leads_contacted) ` +
      `select w.id, c.id, current_date, 20, 18, 2, 1, 1, 5, 5, 2, 1, 15 ` +
      `from public.workspace w cross join public.campaign c ` +
      `where w.id = '${wsId}' and c.workspace_id = w.id"`,
    { stdio: "pipe" },
  );

  await page.reload();
  await expect(page.getByText("No data in range")).toHaveCount(0, {
    timeout: 15_000,
  });

  // Exact-number accuracy checks against the seeded row.
  await expect(page.getByText("18").first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("20", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("15", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Seeded campaign").first()).toBeVisible();
});
