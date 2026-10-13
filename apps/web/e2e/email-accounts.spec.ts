import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (port 8080). */
test.describe("email accounts page", () => {
  test("provider cards, groups CRUD and honest empty state", async ({ page }) => {
    test.setTimeout(120_000);
    const email = testEmail("accounts");

    // Sign up and confirm.
    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Accounts User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Accounts User");
    await page.getByRole("button", { name: "Continue" }).click();

    // Create the workspace the accounts belong to.
    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Accounts WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Accounts WS").first()).toBeVisible();

    // Navigate via the sidebar entry.
    await page.getByRole("link", { name: "Email Accounts" }).click();
    await expect(page).toHaveURL(/\/app\/email-accounts/);

    // Provider cards: all three providers with honest capability lists.
    await expect(page.getByText("Gmail / Google Workspace")).toBeVisible();
    await expect(page.getByText("Microsoft 365 / Outlook")).toBeVisible();
    await expect(page.getByText("SMTP / IMAP")).toBeVisible();
    // Honest availability — connect flows are not wired yet, and the UI says so.
    await expect(page.getByText("Setup arrives in subphase 2.2")).toBeVisible();
    await expect(page.getByText("Setup arrives in subphase 2.3")).toBeVisible();
    await expect(page.getByText("Setup arrives in subphase 2.4")).toBeVisible();

    // Accounts table shows the honest empty state.
    await expect(
      page.getByText("No email accounts connected yet")
    ).toBeVisible();

    // Create an account group.
    await page.getByLabel("New group name").fill("EU senders");
    await page.getByRole("button", { name: "Create group" }).click();
    const groupRow = page.getByText("EU senders").first();
    await expect(groupRow).toBeVisible();
    await expect(page.getByText("0 accounts").first()).toBeVisible();

    // Rename the group inline.
    await page.getByRole("button", { name: "Rename group EU senders" }).click();
    await page.getByLabel("Group name", { exact: true }).fill("EU — primary");
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await expect(page.getByText("EU — primary").first()).toBeVisible();

    // Delete it with the two-step confirmation.
    await page.getByRole("button", { name: "Delete group EU — primary" }).click();
    await page.getByRole("button", { name: "Confirm delete" }).click();
    await expect(page.getByText("No groups yet")).toBeVisible();
  });
});
