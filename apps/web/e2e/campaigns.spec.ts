import { execSync } from "node:child_process";

import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (port 8080). */
test.describe("campaigns module", () => {
  test("campaign lifecycle: create, sequence, audience, mailbox, activate, pause", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const email = testEmail("campaigns");

    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Campaigns User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Campaigns User");
    await page.getByRole("button", { name: "Continue" }).click();

    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Campaigns WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Campaigns WS").first()).toBeVisible();

    // Seed a lead list so the campaign has an audience.
    await page.getByRole("link", { name: "Leads" }).click();
    await page.getByRole("tab", { name: "Lists" }).click();
    await page.getByLabel("List name").fill("Launch audience");
    await page.getByRole("button", { name: "Add", exact: true }).first().click();
    await expect(page.getByText("Launch audience").first()).toBeVisible();

    // Create the campaign.
    await page.getByRole("link", { name: "Campaigns" }).click();
    await expect(page).toHaveURL(/\/app\/campaigns/);
    await expect(page.getByText("No campaigns yet")).toBeVisible();
    await page.getByRole("button", { name: "New campaign" }).first().click();
    await page.getByLabel("Campaign name").fill("Q4 Launch");
    await page.getByLabel("Campaign description").fill("Launch sequence for the Q4 push");
    await page.getByRole("button", { name: "Create campaign" }).click();

    // Detail page opens; activation is blocked without prerequisites.
    await expect(page.getByText("Launch sequence for the Q4 push")).toBeVisible();
    await page.getByRole("button", { name: "Activate campaign" }).click();
    await expect(page.getByText(/Cannot activate/)).toBeVisible();

    // Add an email step and a wait step.
    await page.getByRole("button", { name: "Add step" }).click();
    await page.getByLabel("Step name").fill("Intro");
    await page.getByLabel("Subject").fill("Welcome aboard");
    await page.getByLabel("Body text").fill("Hello and welcome to the launch.");
    await page.getByRole("button", { name: "Add step" }).last().click();
    await expect(page.getByText("Welcome aboard")).toBeVisible();
    await page.getByRole("button", { name: "Add step" }).click();
    await page.getByLabel("Step type").selectOption("wait");
    await page.getByLabel("Wait days").fill("2");
    await page.getByRole("button", { name: "Add step" }).last().click();
    await expect(page.getByText("2 day(s)")).toBeVisible();

    // Assign the audience.
    await page.getByRole("button", { name: "Add audience" }).click();
    await page.getByLabel("Lead list").selectOption({ label: "Launch audience" });
    await page.getByRole("dialog").getByRole("button", { name: "Add audience" }).click();
    await expect(page.getByText("List · Launch audience")).toBeVisible();

    // Activation is still blocked without a mailbox.
    await page.getByRole("button", { name: "Activate campaign" }).click();
    await expect(page.getByText(/needs at least one sending mailbox/)).toBeVisible();

    // Seed a sending account directly (real connect validation is covered by
    // email-accounts.spec.ts) and assign it.
    const wsId = await page.evaluate(() => window.location.href); // placeholder no-op
    void wsId;
    execSync(
      `docker exec supabase_db_arcadence psql -U postgres -d postgres -t -A -c ` +
        `"insert into public.email_accounts (workspace_id, provider, email_address) ` +
        `select id, 'smtp_imap', 'sender-4-1@arcadence.test' from public.workspace order by created_at desc limit 1 ` +
        `returning id"`,
      { stdio: "pipe" },
    );
    await page.reload();
    await page.getByRole("button", { name: "Add mailbox" }).click();
    await page
      .getByLabel("Sending account")
      .selectOption({ label: "sender-4-1@arcadence.test (smtp_imap)" });
    await page.getByRole("dialog").getByRole("button", { name: "Add mailbox" }).click();
    await expect(page.getByText("sender-4-1@arcadence.test (smtp_imap)")).toBeVisible();

    // Activate; the sequence becomes locked (structural UI hidden) and shows the hint.
    await page.getByRole("button", { name: "Activate campaign" }).click();
    await expect(page.getByText("Active", { exact: true }).first()).toBeVisible();
    await expect(page.getByText(/locked while the campaign is not a draft/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Add step" })).toHaveCount(0);

    // Pause and resume preserve the campaign state.
    await page.getByRole("button", { name: "Pause campaign" }).click();
    await expect(page.getByText("Paused", { exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: "Resume campaign" }).click();
    await expect(page.getByText("Active", { exact: true }).first()).toBeVisible();

    // Rename is refused while running.
    await page.getByRole("button", { name: "Edit details" }).click();
    await page.getByLabel("Campaign name").fill("Renamed mid-flight");
    await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
    await expect(page.getByText(/locked while the campaign is running/)).toBeVisible();
    await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();

    // The list page shows the campaign as active.
    await page.getByRole("button", { name: "All campaigns" }).click();
    await expect(page.getByText("Q4 Launch")).toBeVisible();
    await expect(page.getByText("Active").first()).toBeVisible();
  });

  test("composer: tokens, autosave, validation and preview against a real contact", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const email = testEmail("composer");

    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Composer User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Composer User");
    await page.getByRole("button", { name: "Continue" }).click();

    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Composer WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Composer WS").first()).toBeVisible();

    // A sample contact for preview (with a first name to resolve).
    await page.getByRole("link", { name: "Leads" }).click();
    await page.getByRole("button", { name: "New contact" }).first().click();
    await page.getByLabel("Email", { exact: true }).fill("ada@composer.test");
    await page.getByLabel("First name").fill("Ada");
    await page.getByRole("button", { name: "Create contact" }).click();
    await expect(page.getByText("ada@composer.test")).toBeVisible();

    // Campaign with one email step.
    await page.getByRole("link", { name: "Campaigns" }).click();
    await page.getByRole("button", { name: "New campaign" }).first().click();
    await page.getByLabel("Campaign name").fill("Composer flow");
    await page.getByRole("button", { name: "Create campaign" }).click();
    await expect(page.getByRole("button", { name: "Add step" })).toBeVisible();
    await page.getByRole("button", { name: "Add step" }).click();
    await page.getByLabel("Subject").fill("Welcome {{first_name}}");
    await page.getByLabel("Body text").fill("Hello {{first_name|there}}, {{last_name}}!");
    await page.getByRole("button", { name: "Add step" }).last().click();
    await expect(page.getByText("Welcome {{first_name}}").first()).toBeVisible();

    // Open the composer on the step's variant A.
    await page.getByRole("button", { name: "Compose step 1" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByLabel("Subject")).toHaveValue("Welcome {{first_name}}");

    // Edit + autosave; the unknown-token badge flags the typo.
    await dialog.getByLabel("Subject").fill("Hey {{first_name|there}} {{comapny}}");
    await expect(dialog.getByText("Unknown: comapny")).toBeVisible({ timeout: 15000 });
    await expect(dialog.getByText("All changes saved")).toBeVisible();
    await expect(dialog.getByText("Unknown: comapny")).toBeVisible();

    // Preview against the real contact: first_name resolves, last_name missing.
    await dialog.getByLabel("Sample contact").selectOption({ label: "ada@composer.test" });
    await dialog.getByRole("button", { name: "Render preview" }).click();
    await expect(dialog.getByText("first_name = Ada")).toBeVisible();
    await expect(dialog.getByText("last_name missing")).toBeVisible();
    await expect(dialog.getByText("Subject:")).toContainText("Hey Ada");
    await dialog.getByRole("button", { name: "Close" }).click();
  });
});
