import { expect, test } from "@playwright/test";

import {
  dismissFoundingOffer,
  STRONG_PASSWORD,
  latestEmailLink,
  testEmail,
} from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:44321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Phase 15.5 area 9 — CSV lead import through the real UI: upload, map,
 * start, finish, and verify the contacts landed in the Contacts tab.
 */
test("import CSV contacts through the import tab", async ({ page }) => {
  test.setTimeout(180_000);
  const email = testEmail("importer");

  await page.goto("/signup");
  await page.getByLabel("Your name").fill("Import User");
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
  await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
  await page.goto(link);
  await page.getByLabel("Display name").fill("Import User");
  await page.getByRole("button", { name: "Continue" }).click();

  await page.getByRole("button", { name: "New workspace" }).first().click();
  await page.getByLabel("Workspace name").fill("Import WS");
  await page.getByRole("button", { name: "Create workspace" }).click();
  await expect(page.getByText("Import WS").first()).toBeVisible();
  await dismissFoundingOffer(page);

  // Build a small CSV with mapped + ignored columns.
  const csv =
    [
      "email,first_name,last_name,notes",
      "ada@import.test,Ada,Byron,first row",
      "grace@import.test,Grace,Hopper,second row",
    ].join("\n") + "\n";

  await page.getByRole("link", { name: "Leads" }).click();
  await page.getByRole("tab", { name: "Import" }).click();
  await page.setInputFiles('input[aria-label="CSV file"]', {
    name: "contacts.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(csv, "utf-8"),
  });
  await page.getByRole("button", { name: "Upload & preview" }).click();
  await expect(page.getByText("Column mapping")).toBeVisible({ timeout: 15_000 });

  // Map the columns explicitly (headers are named, but assert real mapping).
  await page.getByLabel(`Target field for email`).selectOption("email");
  await page.getByLabel(`Target field for first_name`).selectOption("first_name");
  await page.getByLabel(`Target field for last_name`).selectOption("last_name");
  await page.getByRole("button", { name: "Start import" }).click();

  // Job completes (small file — poll the status badge).
  await expect(page.getByText("Completed", { exact: true }).first()).toBeVisible({
    timeout: 30_000,
  });

  // The imported contacts exist in the Contacts tab.
  await page.getByRole("tab", { name: "Contacts" }).click();
  await expect(page.getByText("ada@import.test").first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("grace@import.test").first()).toBeVisible();

  // Duplicate handling: re-running the same file must not duplicate rows.
  await page.getByRole("tab", { name: "Import" }).click();
  await page.setInputFiles('input[aria-label="CSV file"]', {
    name: "contacts.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(csv, "utf-8"),
  });
  await page.getByRole("button", { name: "Upload & preview" }).click();
  await expect(page.getByText("Column mapping")).toBeVisible({ timeout: 15_000 });
  await page.getByRole("button", { name: "Start import" }).click();
  await expect(page.getByText("Completed", { exact: true }).first()).toBeVisible({
    timeout: 30_000,
  });

  await page.getByRole("tab", { name: "Contacts" }).click();
  await expect(page.getByText("ada@import.test").first()).toBeVisible();
  // Count table ROWS (the email renders in both name and email cells).
  const adaRowCount = await page.getByRole("row", { name: /ada@import.test/ }).count();
  expect(adaRowCount).toBe(1); // duplicate strategy 'skip' prevented re-import
});
