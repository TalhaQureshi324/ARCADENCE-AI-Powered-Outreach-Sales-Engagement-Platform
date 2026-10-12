import { expect, test } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

/** Requires the Scala API to be running locally (scripts/dev-api.sh). */
test.describe("application shell", () => {
  test("dashboard shell: sidebar, workspace create, theme toggle, command palette", async ({
    page,
  }) => {
    page.on("response", (res) => {
      if (res.url().includes(":8080")) {
        console.log("API", res.status(), res.url());
      }
    });
    page.on("console", (msg) => {
      if (msg.type() === "error") console.log("BROWSER ERROR:", msg.text());
    });
    const email = testEmail("shell");

    // Sign up and confirm.
    await page.goto("/signup");
    await page.getByLabel("Your name").fill("Shell User");
    await page.getByLabel("Work email").fill(email);
    await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
    await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Shell User");
    await page.getByRole("button", { name: "Continue" }).click();

    // Shell is visible: sidebar + topbar with profile menu.
    await expect(page.getByRole("link", { name: "Arcadence" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();

    // Empty state for workspaces (honest, no fake data).
    await expect(page.getByText("No workspaces yet")).toBeVisible();

    // Create a workspace via the empty-state action.
    await page.getByRole("button", { name: "New workspace" }).first().click();
    await page.getByLabel("Workspace name").fill("E2E Workspace");
    await page.getByRole("button", { name: "Create workspace" }).click();
    await expect(page.getByText("E2E Workspace")).toBeVisible();
    await expect(page.getByText("owner", { exact: true })).toBeVisible();

    // Command palette via keyboard shortcut.
    await page.keyboard.press("ControlOrMeta+k");
    await expect(page.getByText("Create workspace", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");

    // Theme toggle flips the dark class.
    await page
      .getByRole("button", { name: /Switch to/ })
      .first()
      .click();
    const isDark = await page.evaluate(() => document.documentElement.classList.contains("dark"));
    const toggleLabel = isDark ? "Switch to light theme" : "Switch to dark theme";
    expect(await page.getByRole("button", { name: toggleLabel }).first().isVisible()).toBeTruthy();
  });
});
