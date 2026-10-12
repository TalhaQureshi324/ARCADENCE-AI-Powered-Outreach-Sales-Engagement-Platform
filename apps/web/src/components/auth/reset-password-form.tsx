"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { validatePassword } from "@/lib/auth/validation";
import { createClient } from "@/lib/supabase/client";

export function ResetPasswordForm() {
  const router = useRouter();
  const [checkingSession, setCheckingSession] = useState(true);
  const [hasSession, setHasSession] = useState(false);
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string | null>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const supabase = createClient();
    supabase.auth
      .getUser()
      .then(({ data: { user } }) => {
        setHasSession(Boolean(user));
        setCheckingSession(false);
      })
      .catch(() => {
        setHasSession(false);
        setCheckingSession(false);
      });
  }, []);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const errors = {
      password: validatePassword(password),
      confirmPassword: confirmPassword === password ? null : "Passwords do not match.",
    };
    setFieldErrors(errors);
    if (Object.values(errors).some(Boolean)) return;

    setLoading(true);
    const supabase = createClient();
    const { error } = await supabase.auth.updateUser({ password });
    setLoading(false);
    if (error) {
      setFormError(
        error.message.includes("recently")
          ? "For security you must wait a moment before changing your password again. Try shortly."
          : "Could not update the password. The reset link may have expired — request a new one.",
      );
      return;
    }
    // Recovery flow leaves a valid session; take the user straight to the app.
    router.push("/app");
    router.refresh();
  }

  if (checkingSession) {
    return (
      <Card>
        <CardHeader eyebrow="Password recovery" title="Checking your reset link…" />
        <div
          aria-busy="true"
          className="h-2 w-full animate-pulse rounded-full bg-neutral-200 motion-reduce:animate-none dark:bg-neutral-700"
        />
      </Card>
    );
  }

  if (!hasSession) {
    return (
      <Card>
        <CardHeader
          eyebrow="Password recovery"
          title="This reset link is no longer valid"
          subtitle="Reset links expire once used or after a period of time."
        />
        <Alert variant="warning">
          Request a fresh reset email and open the newest link — older links stop working after a
          new one is issued.
        </Alert>
        <div className="mt-5 flex justify-center gap-4 text-sm">
          <a href="/forgot-password" className="font-medium text-indigo-600 dark:text-indigo-400">
            Request a new link
          </a>
          <a
            href="/login"
            className="text-neutral-500 hover:text-neutral-700 dark:text-neutral-400"
          >
            Back to sign in
          </a>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Password recovery"
        title="Choose a new password"
        subtitle="Pick something strong you don't use anywhere else."
      />
      {formError && (
        <div className="mb-4">
          <Alert variant="error">{formError}</Alert>
        </div>
      )}
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field
          id="new-password"
          label="New password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password}
          required
        />
        <Field
          id="confirm-new-password"
          label="Confirm new password"
          type="password"
          autoComplete="new-password"
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          error={fieldErrors.confirmPassword}
          required
        />
        <Button type="submit" loading={loading} className="w-full">
          Update password
        </Button>
      </form>
    </Card>
  );
}
