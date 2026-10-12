"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { validateEmail } from "@/lib/auth/validation";
import { createClient } from "@/lib/supabase/client";

export function ForgotPasswordForm() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const error = validateEmail(email);
    setFieldError(error);
    if (error) return;

    setLoading(true);
    const supabase = createClient();
    const { error: resetError } = await supabase.auth.resetPasswordForEmail(email.trim(), {
      redirectTo: `${window.location.origin}/auth/confirm?type=recovery`,
    });
    setLoading(false);
    if (resetError) {
      setFormError("Could not send the reset email. Please try again.");
      return;
    }
    setSent(true);
  }

  if (sent) {
    return (
      <Card>
        <CardHeader
          eyebrow="Password recovery"
          title="Check your inbox"
          subtitle="If an account exists for that address, a reset link is on its way."
        />
        <Alert variant="info">
          Open the email and follow the link to choose a new password. The link expires after a
          while — request a new one if it has expired.
        </Alert>
        <p className="mt-5 text-center text-sm text-neutral-500 dark:text-neutral-400">
          <Link href="/login" className="font-medium text-indigo-600 dark:text-indigo-400">
            Back to sign in
          </Link>
        </p>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Password recovery"
        title="Reset your password"
        subtitle="Enter your account email and we'll send you a reset link."
      />
      {formError && (
        <div className="mb-4">
          <Alert variant="error">{formError}</Alert>
        </div>
      )}
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="you@company.com"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          error={fieldError}
          required
        />
        <Button type="submit" loading={loading} className="w-full">
          Send reset link
        </Button>
      </form>
      <p className="mt-6 text-center text-sm text-neutral-500 dark:text-neutral-400">
        Remembered it?{" "}
        <Link href="/login" className="font-medium text-indigo-600 dark:text-indigo-400">
          Sign in
        </Link>
      </p>
    </Card>
  );
}
