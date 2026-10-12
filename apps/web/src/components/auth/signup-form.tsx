"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import {
  PASSWORD_MIN,
  validateDisplayName,
  validateEmail,
  validatePassword,
} from "@/lib/auth/validation";
import { createClient } from "@/lib/supabase/client";

type Stage = "form" | "confirm-inbox" | "error";

export function SignupForm() {
  const [stage, setStage] = useState<Stage>("form");
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string | null>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const errors = {
      name: validateDisplayName(name),
      email: validateEmail(email),
      password: validatePassword(password),
      confirmPassword: confirmPassword === password ? null : "Passwords do not match.",
    };
    setFieldErrors(errors);
    if (Object.values(errors).some(Boolean)) return;

    setLoading(true);
    const supabase = createClient();
    const { error } = await supabase.auth.signUp({
      email: email.trim(),
      password,
      options: {
        emailRedirectTo: `${window.location.origin}/auth/confirm?next=/onboarding`,
        data: { name: name.trim() },
      },
    });
    setLoading(false);

    if (error) {
      setFormError(
        error.code === "user_already_exists"
          ? "An account with this email already exists — try signing in."
          : "Sign-up failed. Please try again.",
      );
      return;
    }
    setStage("confirm-inbox");
  }

  if (stage === "confirm-inbox") {
    return (
      <Card>
        <CardHeader
          eyebrow="Arcadence"
          title="Confirm your email"
          subtitle="We sent a confirmation link to your inbox."
        />
        <Alert variant="info">
          Open the email we sent to <strong>{email.trim()}</strong> and follow the confirmation link
          to activate your account. The link expires after a while — if it has expired, you can sign
          up again to receive a fresh one.
        </Alert>
        <p className="mt-5 text-center text-sm text-neutral-500 dark:text-neutral-400">
          Already confirmed?{" "}
          <Link href="/login" className="font-medium text-indigo-600 dark:text-indigo-400">
            Sign in
          </Link>
        </p>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Arcadence"
        title="Create your account"
        subtitle="Start sending better outreach in minutes."
      />
      {formError && (
        <div className="mb-4">
          <Alert variant="error">{formError}</Alert>
        </div>
      )}
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field
          id="name"
          label="Your name"
          autoComplete="name"
          placeholder="Jane Doe"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={fieldErrors.name}
          required
        />
        <Field
          id="email"
          label="Work email"
          type="email"
          autoComplete="email"
          placeholder="you@company.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={fieldErrors.email}
          required
        />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password}
          hint={`At least ${PASSWORD_MIN} characters, with a letter and a number.`}
          required
        />
        <Field
          id="confirm-password"
          label="Confirm password"
          type="password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          error={fieldErrors.confirmPassword}
          required
        />
        <Button type="submit" loading={loading} className="w-full">
          Create account
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-neutral-500 dark:text-neutral-400">
        Already have an account?{" "}
        <Link
          href="/login"
          className="font-medium text-indigo-600 hover:text-indigo-500 dark:text-indigo-400"
        >
          Sign in
        </Link>
      </p>
    </Card>
  );
}
