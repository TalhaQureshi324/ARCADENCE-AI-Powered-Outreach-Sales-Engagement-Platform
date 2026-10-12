export const PASSWORD_MIN = 8;

export function validateEmail(email: string): string | null {
  const value = email.trim();
  if (!value) return "Email is required.";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return "Enter a valid email address.";
  return null;
}

export function validatePassword(password: string): string | null {
  if (!password) return "Password is required.";
  if (password.length < PASSWORD_MIN)
    return `Password must be at least ${PASSWORD_MIN} characters.`;
  if (!/[a-zA-Z]/.test(password) || !/[0-9]/.test(password))
    return "Password must contain at least one letter and one number.";
  return null;
}

export function validateDisplayName(name: string): string | null {
  const value = name.trim();
  if (!value) return "Your name is required.";
  if (value.length > 80) return "Name must be 80 characters or fewer.";
  return null;
}

const OTP_TYPES = ["signup", "recovery", "invite", "email_change", "email", "magiclink"] as const;

export type OtpType = (typeof OTP_TYPES)[number];

/** Narrows an untrusted `type` query parameter to a supported OTP type. */
export function isOtpType(value: string | null): value is OtpType {
  return value !== null && (OTP_TYPES as readonly string[]).includes(value);
}
