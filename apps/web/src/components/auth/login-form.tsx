"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { sanitizeRedirect } from "@/lib/auth/redirects";
import { validateEmail, validatePassword } from "@/lib/auth/validation";
import { createClient } from "@/lib/supabase/client";

const ERROR_MESSAGES: Record<string, string> = {
  invalid_credentials: "Invalid email or password.",
  email_not_confirmed: "Please confirm your email first — check your inbox for the link.",
  over_request_rate_limit: "Too many attempts. Wait a moment and try again.",
};

function friendlyAuthError(code: string | undefined, fallback: string): string {
  return (code && ERROR_MESSAGES[code]) || fallback;
}

export function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = sanitizeRedirect(searchParams.get("next"));
  const signedOut = searchParams.get("signedOut") === "1";
  const resetDone = searchParams.get("reset") === "success";

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<{
    email?: string | null;
    password?: string | null;
  }>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(
    signedOut
      ? "You have been signed out."
      : resetDone
        ? "Your password has been updated. Sign in with your new password."
        : null,
  );
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setNotice(null);
    setFormError(null);
    const errors = {
      email: validateEmail(email),
      password: validatePassword(password) && "Password is required.",
    };
    setFieldErrors(errors);
    if (errors.email || errors.password) return;

    setLoading(true);
    const supabase = createClient();
    const { error } = await supabase.auth.signInWithPassword({
      email: email.trim(),
      password,
    });
    setLoading(false);

    if (error) {
      setFormError(friendlyAuthError(error.code, "Sign-in failed. Please try again."));
      return;
    }
    router.push(next);
    router.refresh();
  }

  const googleEnabled = process.env.NEXT_PUBLIC_GOOGLE_OAUTH_ENABLED === "true";

  async function signInWithGoogle() {
    setLoading(true);
    const supabase = createClient();
    const { error } = await supabase.auth.signInWithOAuth({
      provider: "google",
      options: { redirectTo: `${window.location.origin}${next}` },
    });
    if (error) {
      setLoading(false);
      setFormError("Google sign-in is not available right now.");
    }
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Arcadence"
        title="Sign in"
        subtitle="Welcome back — enter your details to continue."
      />

      {notice && (
        <div className="mb-4">
          <Alert variant="success">{notice}</Alert>
        </div>
      )}
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
          error={fieldErrors.email}
          required
        />
        <Field
          id="password"
          label="Password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          error={fieldErrors.password}
          required
        />
        <div className="flex justify-end">
          <Link
            href="/forgot-password"
            className="text-xs font-medium text-indigo-600 hover:text-indigo-500 dark:text-indigo-400"
          >
            Forgot your password?
          </Link>
        </div>
        <Button type="submit" loading={loading} className="w-full">
          Sign in
        </Button>
      </form>

      {googleEnabled && (
        <>
          <div className="my-5 flex items-center gap-3" aria-hidden="true">
            <span className="h-px flex-1 bg-neutral-200 dark:bg-neutral-700" />
            <span className="text-xs text-neutral-400">or</span>
            <span className="h-px flex-1 bg-neutral-200 dark:bg-neutral-700" />
          </div>
          <Button
            variant="secondary"
            className="w-full"
            onClick={signInWithGoogle}
            disabled={loading}
          >
            Continue with Google
          </Button>
          <p className="mt-2 text-center text-xs text-neutral-400">
            Application sign-in only — Gmail mailbox access is requested separately, only when you
            connect an email account.
          </p>
        </>
      )}

      <p className="mt-6 text-center text-sm text-neutral-500 dark:text-neutral-400">
        Don&apos;t have an account?{" "}
        <Link
          href={`/signup${next !== "/app" ? `?next=${encodeURIComponent(next)}` : ""}`}
          className="font-medium text-indigo-600 hover:text-indigo-500 dark:text-indigo-400"
        >
          Create one
        </Link>
      </p>
    </Card>
  );
}
