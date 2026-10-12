import { describe, expect, it } from "vitest";

import {
  isOtpType,
  validateDisplayName,
  validateEmail,
  validatePassword,
} from "@/lib/auth/validation";

describe("validateEmail", () => {
  it("accepts realistic addresses", () => {
    expect(validateEmail("you@company.com")).toBeNull();
  });
  it("rejects empty and malformed input", () => {
    expect(validateEmail("")).not.toBeNull();
    expect(validateEmail("not-an-email")).not.toBeNull();
    expect(validateEmail("a@b")).not.toBeNull();
  });
});

describe("validatePassword", () => {
  it("requires at least 8 characters with a letter and a number", () => {
    expect(validatePassword("abcdefg1")).toBeNull();
  });
  it("rejects short, empty, or letter-only passwords", () => {
    expect(validatePassword("a1")).not.toBeNull();
    expect(validatePassword("")).not.toBeNull();
    expect(validatePassword("abcdefgh")).not.toBeNull();
    expect(validatePassword("12345678")).not.toBeNull();
  });
});

describe("validateDisplayName", () => {
  it("accepts normal names and rejects empty or oversized ones", () => {
    expect(validateDisplayName("Jane Doe")).toBeNull();
    expect(validateDisplayName("   ")).not.toBeNull();
    expect(validateDisplayName("x".repeat(81))).not.toBeNull();
  });
});

describe("isOtpType", () => {
  it("narrows supported OTP types only", () => {
    expect(isOtpType("signup")).toBe(true);
    expect(isOtpType("recovery")).toBe(true);
    expect(isOtpType("admin_delete_user")).toBe(false);
    expect(isOtpType(null)).toBe(false);
  });
});
