import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (port 8080). */
test.describe("ai studio module", () => {
  test("create agent, see usage budget card and empty approvals", async ({ page }) => {
    test.setTimeout(150_000);
    const email = testEmail("aistudio");

    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Studio User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Studio User");
    await page.getByRole("button", { name: "Continue" }).click();

    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Studio WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Studio WS").first()).toBeVisible();

    await page.getByRole("link", { name: "AI Studio" }).click();
    await expect(page).toHaveURL(/\/app\/aistudio/);

    // Budget card shows the default workspace budget.
    await expect(page.getByText(/Monthly AI usage/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/200,000 tokens/)).toBeVisible();

    // Create an agent through the UI.
    await page.getByPlaceholder("New agent name").fill("Outreach writer");
    await page.getByRole("button", { name: "Create agent" }).click();
    await expect(page.getByText("Outreach writer").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("required", { exact: true }).first()).toBeVisible();

    // Governance defaults: nothing pending, no runs yet.
    await expect(page.getByText("No pending approvals.")).toBeVisible();
    await expect(page.getByText("No runs yet.")).toBeVisible();
  });
});
