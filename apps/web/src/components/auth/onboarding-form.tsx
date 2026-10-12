"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";

import { Alert } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardHeader } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { validateDisplayName } from "@/lib/auth/validation";
import { createClient } from "@/lib/supabase/client";

export function OnboardingForm({ initialName }: { initialName: string }) {
  const router = useRouter();
  const [name, setName] = useState(initialName);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setFormError(null);
    const error = validateDisplayName(name);
    setFieldError(error);
    if (error) return;

    setLoading(true);
    const supabase = createClient();
    const {
      data: { user },
    } = await supabase.auth.getUser();
    if (!user) {
      setLoading(false);
      setFormError("Your session has expired. Please sign in again.");
      return;
    }
    // RLS: users update only their own profile row (profile_update_own policy).
    const { error: updateError } = await supabase
      .from("profile")
      .update({ display_name: name.trim() })
      .eq("id", user.id);
    setLoading(false);
    if (updateError) {
      setFormError("Could not save your profile. Please try again.");
      return;
    }
    router.push("/app");
    router.refresh();
  }

  return (
    <Card>
      <CardHeader
        eyebrow="Welcome to Arcadence"
        title="Let's set up your profile"
        subtitle="One quick step before you start — what should we call you?"
      />
      {formError && (
        <div className="mb-4">
          <Alert variant="error">{formError}</Alert>
        </div>
      )}
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field
          id="display-name"
          label="Display name"
          autoComplete="name"
          placeholder="Jane Doe"
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={fieldError}
          required
        />
        <Button type="submit" loading={loading} className="w-full">
          Continue
        </Button>
      </form>
      <p className="mt-5 text-center text-xs text-neutral-400 dark:text-neutral-500">
        Next up: create your workspace and connect email accounts (rolling out in the next release).
      </p>
    </Card>
  );
}
