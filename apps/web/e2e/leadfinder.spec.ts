import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (port 8080). */
test.describe("lead finder module", () => {
  test("search sandbox provider, see provenance labels, licensing blocks add-to-list", async ({
    page,
  }) => {
    test.setTimeout(150_000);
    const email = testEmail("leadfinder");

    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Finder User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Finder User");
    await page.getByRole("button", { name: "Continue" }).click();

    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("Finder WS");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("Finder WS").first()).toBeVisible();

    await page.getByRole("link", { name: "Lead Finder" }).click();
    await expect(page).toHaveURL(/\/app\/leadfinder/);

    // Starter grant lands in the auditable ledger and shows as balance.
    await expect(page.getByTestId("credit-balance")).toHaveText("50", { timeout: 15_000 });

    // Filtered search against the sandbox provider.
    await page.getByLabel("Industry").fill("software");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("Sandbox Co").first()).toBeVisible({ timeout: 20_000 });

    // Provenance and verification labels are visible per contact.
    await expect(page.getByText("sandra.sandbox@example-sandbox.test").first()).toBeVisible();
    await expect(page.getByText("verified").first()).toBeVisible();
    await expect(page.getByText("sandbox only").first()).toBeVisible();
    await expect(page.getByText("via sandbox").first()).toBeVisible();

    // Licensing: sandbox-only contacts are never eligible for outreach lists.
    await expect(page.getByText("eligible to add")).toHaveText(/0 eligible to add/);
    await expect(page.getByRole("button", { name: /Add eligible to list/ })).toBeDisabled();
    await expect(page.getByText(/sandbox data/i).first()).toBeVisible();

    // Credits: reservation released, charged only for delivered results
    // (industry=software matches two of the three sandbox fixtures).
    await expect(page.getByTestId("credit-balance")).toHaveText("48");
  });
});
