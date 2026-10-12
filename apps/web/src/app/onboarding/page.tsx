import { redirect } from "next/navigation";
import { Suspense } from "react";

import { OnboardingForm } from "@/components/auth/onboarding-form";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Welcome" };

export default function OnboardingPage() {
  return (
    <Suspense
      fallback={
        <main className="min-h-screen bg-neutral-50 p-6 dark:bg-neutral-950" aria-busy="true" />
      }
    >
      <OnboardingContent />
    </Suspense>
  );
}

async function OnboardingContent() {
  const supabase = await createClient();

  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/onboarding");

  // Defense in depth: middleware already guards this route; re-check here too.
  const { data: profile } = await supabase
    .from("profile")
    .select("display_name")
    .eq("id", user.id)
    .maybeSingle();

  return (
    <main className="flex min-h-screen items-center justify-center bg-neutral-50 p-6 dark:bg-neutral-950">
      <div className="w-full max-w-md">
        <OnboardingForm initialName={profile?.display_name ?? ""} />
      </div>
    </main>
  );
}
