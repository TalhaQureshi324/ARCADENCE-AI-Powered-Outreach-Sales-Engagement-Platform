import { expect, test, type Page } from "@playwright/test";

import { STRONG_PASSWORD, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

async function signUpAndOnboard(page: Page, name: string): Promise<string> {
  const email = testEmail(name.toLowerCase().replace(/\s+/g, "-"));
  await page.goto("/signup");
  await page.getByLabel("Your name").fill(name);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
  await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
  await page.goto(link);
  await page.getByLabel("Display name").fill(name);
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByRole("heading", { name: `Welcome, ${name}` })).toBeVisible();
  return email;
}

async function createWorkspace(page: Page, name: string) {
  await page.getByRole("button", { name: "New workspace" }).first().click();
  await page.getByLabel("Workspace name").fill(name);
  await page.getByRole("button", { name: "Create workspace" }).click();
  await expect(page.getByRole("heading", { name, level: 3 })).toBeVisible();
}

async function openSettings(page: Page) {
  await page.getByRole("link", { name: "Settings" }).click();
  await expect(page.getByText("Your role:")).toBeVisible();
}

/** Two separate organizations run side by side: neither user can see or touch
 * the other's workspace through the UI. Then the full admin lifecycle runs
 * inside one workspace: invite → accept → permission boundary → removal.
 */
test.describe("multi-organization administration", () => {
  test("two organizations operate simultaneously with no unauthorized cross-access", async ({
    browser,
  }) => {
    // Long multi-user lifecycle: two signups, invitation email, admin actions.
    test.setTimeout(240_000);
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    for (const pg of [pageA, pageB]) {
      pg.on("response", (res) => {
        if (!res.ok && res.url().includes(":3000")) {
          console.log("NAV FAIL:", res.status(), res.url());
        }
      });
    }
    await signUpAndOnboard(pageA, "Org A Owner");
    const emailB = await signUpAndOnboard(pageB, "Org B Owner");
    await createWorkspace(pageA, "Org A Workspace");
    await createWorkspace(pageB, "Org B Workspace");

    // A's list contains ONLY A's workspace; B's only B's.
    await expect(pageA.getByRole("heading", { name: "Org A Workspace", level: 3 })).toBeVisible();
    await expect(pageA.getByRole("heading", { name: "Org B Workspace", level: 3 })).toHaveCount(0);
    await expect(pageB.getByRole("heading", { name: "Org B Workspace", level: 3 })).toBeVisible();
    await expect(pageB.getByRole("heading", { name: "Org A Workspace", level: 3 })).toHaveCount(0);

    // A invites B's email into Org A's workspace via the settings UI and
    // reads the invite link from the invitations table.
    await openSettings(pageA);
    await pageA.getByRole("tab", { name: "Invitations" }).click();
    await pageA.getByLabel("Invite by email").fill(emailB);
    await pageA.getByLabel("Role", { exact: true }).selectOption("member");
    await pageA.getByRole("button", { name: "Invite" }).click();
    const inviteLink = await pageA.locator('a[href*="/invitations/"]').first().getAttribute("href");
    expect(inviteLink).toBeTruthy();

    // B accepts the invitation in their own session.
    await pageB.goto(inviteLink!);
    await expect(pageB.getByText("Join Org A Workspace")).toBeVisible();
    await pageB.getByRole("button", { name: "Accept invitation" }).click();
    await expect(pageB.getByText("You joined Org A Workspace")).toBeVisible();

    // B reloads: Org A Workspace now appears in B's list as a member.
    await pageB.goto("/app");
    await expect(pageB.getByRole("heading", { name: "Org A Workspace", level: 3 })).toBeVisible();

    // B opens A's workspace settings: management UI must be absent (member role).
    await pageB.getByRole("button", { name: "Workspace selector" }).click();
    await pageB.getByRole("menuitem", { name: /Org A Workspace/ }).click();
    await openSettings(pageB);
    await expect(pageB.getByText("Your role: member.")).toBeVisible();

    // Inactive Radix tab panels are unmounted — activate each to assert its
    // member-gate message.
    await pageB.getByRole("tab", { name: "Invitations" }).click();
    await expect(pageB.getByText("Only owners and admins can manage invitations.")).toBeVisible();
    await pageB.getByRole("tab", { name: "Profile" }).click();
    await expect(
      pageB.getByText("Only owners and admins can change workspace profile settings."),
    ).toBeVisible();
    await pageB.getByRole("tab", { name: "Members" }).click();
    await expect(pageB.getByRole("button", { name: "Invite" })).toHaveCount(0);

    // A removes B from the workspace. A's page reloads first: the members
    // list was fetched before B accepted.
    await pageA.reload();
    await openSettings(pageA);
    await pageA.getByRole("button", { name: "Remove" }).click();
    await expect(pageA.getByText("Member removed.")).toBeVisible();

    // B immediately loses access: fresh navigation no longer lists the workspace.
    await pageB.goto("/app");
    await pageB.waitForLoadState("networkidle");
    await expect(pageB.getByRole("heading", { name: "Org A Workspace", level: 3 })).toHaveCount(0);

    await contextA.close();
    await contextB.close();
  });

  async function openSettings(page: Page) {
    await page.getByRole("link", { name: "Settings" }).click();
    await expect(page.getByText("Your role:")).toBeVisible();
  }
});
