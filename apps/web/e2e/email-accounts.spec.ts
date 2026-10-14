import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (port 8080) WITHOUT Google
 * OAuth credentials (the not-configured path is the honest local default).
 */
test.describe("email accounts page", () => {
  test("provider cards, groups CRUD, honest empty state and OAuth callback guard", async ({
    page,
  }) => {
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
    // Gmail (2.2) and Microsoft (2.3) flows are built but unconfigured
    // locally — the UI says so instead of showing dead buttons. SMTP/IMAP
    // (2.4) needs no console setup and offers a real connect form.
    await expect(page.getByText("Google OAuth not configured on this server")).toBeVisible();
    await expect(page.getByText("Microsoft OAuth not configured on this server")).toBeVisible();

    // SMTP/IMAP connect form opens from the provider card and rejects a
    // dead server honestly (connection refused on 127.0.0.1:1).
    await page.getByRole("button", { name: "Connect SMTP" }).click();
    await expect(page.getByText("Connect an SMTP/IMAP account")).toBeVisible();
    await page.getByLabel("Email address").fill("dead@server.test");
    await page.getByLabel("Username").fill("dead@server.test");
    await page.getByLabel("Password").fill("nope");
    await page.getByLabel("SMTP host").fill("127.0.0.1");
    await page.getByLabel("SMTP port").fill("1");
    await page.getByLabel("IMAP host").fill("127.0.0.1");
    await page.getByLabel("IMAP port").fill("1");
    await page.getByRole("button", { name: "Verify and connect" }).click();
    await expect(page.locator("[role='alert']").first()).toBeVisible({ timeout: 20000 });
    await page.getByRole("button", { name: "Cancel" }).click();

    // Accounts table shows the honest empty state.
    await expect(page.getByText("No email accounts connected yet")).toBeVisible();

    // OAuth callback guard through the REAL API: a forged state parameter is
    // rejected and the browser lands back here with a visible error banner.
    await page.goto(
      "http://localhost:8080/api/v1/email-connections/oauth/google/callback?code=x&state=forged.signature",
    );
    await expect(page).toHaveURL(/gmail=invalid_state/);
    await expect(page.getByText(/connect request could not be verified/i)).toBeVisible();
    // The banner URL was cleaned after display.
    expect(await page.evaluate(() => window.location.search)).toBe("");

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
