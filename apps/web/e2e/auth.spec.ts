import { expect, test, type Page } from "@playwright/test";

import { STRONG_PASSWORD, expectVisibleText, latestEmailLink, testEmail } from "./helpers/inbucket";

const VERIFY_LINK = /https?:\/\/127\.0\.0\.1:54321\/auth\/v1\/verify\?[^\s"'<>]+/;

async function signUp(page: Page, name: string, email: string) {
  await page.goto("/signup");
  await page.getByLabel("Your name").fill(name);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(STRONG_PASSWORD);
  await page.getByLabel("Confirm password").fill(STRONG_PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expectVisibleText(page, "Confirm your email");
}

async function signOut(page: Page) {
  await page.getByRole("button", { name: "Account menu" }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

async function signIn(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
}

test.describe("authentication lifecycle", () => {
  test("signup → email confirmation → onboarding → dashboard", async ({ page }) => {
    const email = testEmail("signup");
    await signUp(page, "E2E User", email);

    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);

    await expect(page.getByText("Let's set up your profile")).toBeVisible();
    await page.getByLabel("Display name").fill("E2E User");
    await page.getByRole("button", { name: "Continue" }).click();

    await expect(page.getByRole("heading", { name: "Welcome, E2E User" })).toBeVisible();
    await expect(page.getByText(`Signed in as ${email}`)).toBeVisible();
  });

  test("invalid credentials show a clear error", async ({ page }) => {
    await signIn(page, "does-not-exist@arcadence.test", "WrongPass123");
    await expect(page.getByText("Invalid email or password.")).toBeVisible();
  });

  test("unauthenticated users are redirected from protected routes with a safe next", async ({
    page,
  }) => {
    await page.goto("/app");
    await expect(page).toHaveURL(/\/login\?next=%2Fapp/);
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  });

  test("successful login, session refresh across requests, and logout", async ({ page }) => {
    const email = testEmail("session");
    await signUp(page, "Session User", email);
    const link = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(link);
    await page.getByLabel("Display name").fill("Session User");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByRole("heading", { name: "Welcome, Session User" })).toBeVisible();

    // Session refresh: middleware revalidates + rotates cookies on every request.
    await page.goto("/app");
    await expect(page.getByText(`Signed in as ${email}`)).toBeVisible();
    await page.waitForTimeout(1_000);
    await page.goto("/app");
    await expect(page.getByText(`Signed in as ${email}`)).toBeVisible();

    // Logout via POST form → login page with confirmation notice.
    await signOut(page);
    await expect(page).toHaveURL(/\/login\?signedOut=1/);
    await expectVisibleText(page, "You have been signed out.");

    // Protected route is locked again after logout.
    await page.goto("/app");
    await expect(page).toHaveURL(/\/login/);
  });

  test("password recovery: request → email link → set new password → sign in with it", async ({
    page,
  }) => {
    const email = testEmail("recovery");
    const oldPassword = STRONG_PASSWORD;
    const newPassword = "FreshPassw0rd-42";

    await signUp(page, "Recovery User", email);
    const confirmLink = await latestEmailLink(email, VERIFY_LINK, /confirm/i);
    await page.goto(confirmLink);
    // Complete onboarding to reach a clean signed-in state, then sign out.
    await page.getByLabel("Display name").fill("Recovery User");
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByRole("heading", { name: "Welcome, Recovery User" })).toBeVisible();
    await signOut(page);
    await expect(page).toHaveURL(/\/login/);

    // Request a reset link.
    await page.goto("/forgot-password");
    await page.getByLabel("Email").fill(email);
    await page.getByRole("button", { name: "Send reset link" }).click();
    await expectVisibleText(page, "Check your inbox");

    const resetLink = await latestEmailLink(email, VERIFY_LINK, /reset/i);
    await page.goto(resetLink);

    // Recovery link lands on the new-password form.
    await expect(page.getByText("Choose a new password")).toBeVisible();
    await page.getByLabel("New password", { exact: true }).fill(newPassword);
    await page.getByLabel("Confirm new password").fill(newPassword);
    await page.getByRole("button", { name: "Update password" }).click();
    await expect(page.getByText(`Signed in as ${email}`)).toBeVisible();

    // Sign out; old password must fail, new password must succeed.
    await signOut(page);
    await expect(page).toHaveURL(/\/login/);

    await signIn(page, email, oldPassword);
    await expect(page.getByText("Invalid email or password.")).toBeVisible();

    await signIn(page, email, newPassword);
    await expect(page.getByText(`Signed in as ${email}`)).toBeVisible();
  });

  test("expired/invalid confirmation link shows an honest error state", async ({ page }) => {
    await page.goto("/auth/confirm?type=signup&token_hash=bogus-token");
    await expect(page).toHaveURL(/\/auth\/error/);
    await expect(
      page.getByRole("heading", { name: /link (has expired|is not valid)/ }),
    ).toBeVisible();
  });
});
