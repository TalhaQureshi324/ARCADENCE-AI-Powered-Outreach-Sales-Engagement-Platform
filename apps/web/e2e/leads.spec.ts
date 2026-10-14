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
});
