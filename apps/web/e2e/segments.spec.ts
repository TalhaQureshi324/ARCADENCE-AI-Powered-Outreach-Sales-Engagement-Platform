import { readFileSync } from "node:fs";

import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (port 8080). */
test.describe("leads module", () => {
  test("contacts CRUD, dedup rejection, search, tags and lists", async ({ page }) => {
    test.setTimeout(150_000);
    const email = testEmail("leads");

    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Leads User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Leads User");
    await page.getByRole("button", { name: "Continue" }).click();

    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Leads WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Leads WS").first()).toBeVisible();

    await page.getByRole("link", { name: "Leads" }).click();
    await expect(page).toHaveURL(/\/app\/leads/);
    await expect(page.getByText("No contacts yet")).toBeVisible();

    // Create a contact.
    await page.getByRole("button", { name: "New contact" }).first().click();
    await page.getByLabel("Email", { exact: true }).fill("Ada.Lovelace@Example.com");
    await page.getByLabel("First name").fill("Ada");
    await page.getByLabel("Last name").fill("Lovelace");
    await page.getByLabel("Job title").fill("Mathematician");
    await page.getByRole("button", { name: "Create contact" }).click();

    // Row appears with normalized email + display value.
    await expect(page.getByText("Ada.Lovelace@Example.com", { exact: true })).toBeVisible();
    await expect(page.getByText("ada.lovelace@example.com", { exact: true })).toBeVisible();

    // Duplicate email is rejected with the conflict surfaced in the dialog.
    await page.getByRole("button", { name: "New contact" }).first().click();
    await page.getByLabel("Email", { exact: true }).fill("ada.lovelace@example.com");
    await page.getByRole("button", { name: "Create contact" }).click();
    await expect(page.getByText("A contact with this email already exists.")).toBeVisible();
    await page.getByRole("button", { name: "Cancel" }).click();

    // Search finds the contact.
    await page.getByLabel("Search contacts").fill("lovelace");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("Ada.Lovelace@Example.com", { exact: true })).toBeVisible();

    // Tags: create one.
    await page.getByRole("tab", { name: "Tags & Fields" }).click();
    await page.getByLabel("New tag name").fill("warm-lead");
    await page.getByRole("button", { name: "Add", exact: true }).first().click();
    await expect(page.getByText("warm-lead").first()).toBeVisible();

    // Lists: create one and add the contact.
    await page.getByRole("tab", { name: "Lists" }).click();
    await page.getByLabel("List name").fill("Q3 founders");
    await page.getByRole("button", { name: "Add", exact: true }).first().click();
    await expect(page.getByText("Q3 founders").first()).toBeVisible();
    const dialog = page.getByRole("dialog");
    await page.getByRole("button", { name: "Members" }).first().click();
    await dialog.getByRole("checkbox").first().check();
    await dialog.getByRole("button", { name: /Add 1 to list/ }).click();
    await expect(page.getByText("1 contact(s) added to Q3 founders.")).toBeVisible();
    await expect(dialog.getByText("Ada.Lovelace@Example.com", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: "Close" }).click();
  });

  test("segments: data quality, AND builder, preview, export, bulk tag; contact merge", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const email = testEmail("segments");

    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Segments User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Segments User");
    await page.getByRole("button", { name: "Continue" }).click();

    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Segments WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Segments WS").first()).toBeVisible();

    await page.getByRole("link", { name: "Leads" }).click();

    // Seed three contacts with distinct regions/titles.
    async function createContact(
      emailAddr: string,
      firstName: string,
      jobTitle: string,
      region: string,
    ) {
      await page.getByRole("button", { name: "New contact" }).first().click();
      await page.getByLabel("Email", { exact: true }).fill(emailAddr);
      await page.getByLabel("First name").fill(firstName);
      await page.getByLabel("Job title").fill(jobTitle);
      await page.getByLabel("Region").fill(region);
      await page.getByRole("button", { name: "Create contact" }).click();
      await expect(page.getByText(emailAddr, { exact: true })).toBeVisible();
    }
    await createContact("ada@seg.test", "Ada", "Engineer", "EU");
    await createContact("grace@seg.test", "Grace", "Designer", "US");
    await createContact("merge-me@seg.test", "M", "Engineer", "EU");

    // Data-quality rollup reflects the seeded rows.
    await page.getByRole("tab", { name: "Segments" }).click();
    await expect(page.getByText("Total contacts").first()).toBeVisible();
    await expect(page.getByText("No saved segments")).toBeVisible();

    // Build an AND segment: Region equals EU AND Job title contains Engineer.
    await page.getByRole("button", { name: "New segment" }).first().click();
    await page.getByLabel("Segment name").fill("EU engineers");
    await page.getByLabel("Rule 1 field").selectOption("region");
    await page.getByLabel("Rule 1 operator").selectOption("eq");
    await page.getByLabel("Rule 1 value").fill("EU");
    await page.getByRole("button", { name: "Add rule" }).click();
    await page.getByLabel("Rule 2 field").selectOption("job_title");
    await page.getByLabel("Rule 2 operator").selectOption("contains");
    await page.getByLabel("Rule 2 value").fill("Engineer");
    await page.getByRole("button", { name: "Save segment" }).click();
    await expect(page.getByText("EU engineers").first()).toBeVisible();
    await expect(page.getByText("2 rules").first()).toBeVisible();

    // Preview matches exactly Ada + merge-me.
    await page.getByRole("button", { name: "Preview EU engineers" }).click();
    const previewDialog = page.getByRole("dialog");
    await expect(previewDialog.getByText("2 contact(s) match these rules.")).toBeVisible();
    await expect(previewDialog.getByText("ada@seg.test")).toBeVisible();
    await expect(previewDialog.getByText("merge-me@seg.test")).toBeVisible();
    await previewDialog.getByRole("button", { name: "Close" }).click();

    // Export downloads a CSV with exactly the two member rows.
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", { name: "Export EU engineers" }).click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe("eu-engineers.csv");
    const csvPath = await download.path();
    const csv = readFileSync(csvPath ?? "", "utf8");
    assertCsvMembers(csv);

    // Bulk tag: create a tag, then apply it to the segment's members.
    await page.getByRole("tab", { name: "Tags & Fields" }).click();
    await page.getByLabel("New tag name").fill("warm-lead");
    await page.getByRole("button", { name: "Add", exact: true }).first().click();
    await expect(page.getByText("warm-lead").first()).toBeVisible();
    await page.getByRole("tab", { name: "Segments" }).click();
    await expect(page.getByText("EU engineers").first()).toBeVisible();
    await page.getByRole("button", { name: "Tag members of EU engineers" }).click();
    const tagDialog = page.getByRole("dialog");
    await tagDialog.getByLabel("Tag to apply").selectOption({ label: "warm-lead" });
    await tagDialog.getByRole("button", { name: "Apply tag" }).click();
    await expect(page.getByText("Tagged 2 new contact(s).")).toBeVisible();

    // Merge: fold merge-me into Ada. The kept row keeps its email and gains
    // nothing (both are complete); the source row disappears.
    await page.getByRole("tab", { name: "Contacts" }).click();
    await page.getByRole("button", { name: "Merge merge-me@seg.test" }).click();
    const mergeDialog = page.getByRole("dialog");
    await mergeDialog.getByLabel("Contact to keep").selectOption({ label: "ada@seg.test — Ada" });
    await mergeDialog.getByRole("button", { name: "Merge", exact: true }).click();
    await expect(page.getByText(/Merged\. /)).toBeVisible();
    await page.getByLabel("Search contacts").fill("merge-me");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText(/No contacts match/)).toBeVisible();
  });
});

function assertCsvMembers(csv: string) {
  const lines = csv.trim().split(/\r?\n/);
  if (lines.length !== 3) {
    throw new Error(`expected header + 2 rows in export, got ${lines.length}: ${csv}`);
  }
  if (!/ada@seg\.test/.test(csv) || !/merge-me@seg\.test/.test(csv)) {
    throw new Error(`export missing member rows: ${csv}`);
  }
}
