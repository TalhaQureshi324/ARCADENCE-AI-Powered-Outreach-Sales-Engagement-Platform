import Link from "next/link";
import { Suspense } from "react";
import { redirect } from "next/navigation";

import { Card, CardHeader } from "@/components/ui/card";
import { createClient } from "@/lib/supabase/server";

export const metadata = { title: "Dashboard" };

export default function AppDashboardPage() {
  return (
    <Suspense fallback={<PageFallback />}>
      <DashboardContent />
    </Suspense>
  );
}

async function DashboardContent() {
  // Middleware guards this route; re-check here (defense in depth).
  const supabase = await createClient();
  const {
    data: { user },
  } = await supabase.auth.getUser();
  if (!user) redirect("/login?next=/app");

  const { data: profile } = await supabase
    .from("profile")
    .select("display_name")
    .eq("id", user.id)
    .maybeSingle();

  const displayName = profile?.display_name?.trim();
  const needsOnboarding = !displayName;
  const createdAt = user.created_at ? new Date(user.created_at).toLocaleDateString() : "—";

  return (
    <main className="min-h-screen bg-neutral-50 dark:bg-neutral-950">
      <header className="border-b border-neutral-200 bg-white dark:border-neutral-800 dark:bg-neutral-900">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
          <span className="text-lg font-semibold tracking-tight text-neutral-900 dark:text-neutral-50">
            Arcadence
          </span>
          <div className="flex items-center gap-4">
            <span className="hidden text-sm text-neutral-500 sm:inline dark:text-neutral-400">
              {displayName ?? user.email}
            </span>
            <form action="/auth/sign-out" method="post">
              <button
                type="submit"
                className="rounded-lg px-3 py-1.5 text-sm font-medium text-neutral-600 hover:bg-neutral-100 dark:text-neutral-300 dark:hover:bg-neutral-800"
              >
                Sign out
              </button>
            </form>
          </div>
        </div>
      </header>

      <div className="mx-auto max-w-5xl space-y-6 p-6">
        {needsOnboarding && (
          <div className="rounded-xl bg-indigo-50 p-4 text-sm text-indigo-800 dark:bg-indigo-950/40 dark:text-indigo-200">
            <Link href="/onboarding" className="font-medium underline underline-offset-2">
              Finish setting up your profile
            </Link>{" "}
            — it takes one step.
          </div>
        )}

        <Card>
          <CardHeader
            eyebrow="Dashboard"
            title={`Hello, ${displayName ?? "there"}`}
            subtitle={`Signed in as ${user.email} · account created ${createdAt}`}
          />
          <p className="text-sm leading-6 text-neutral-500 dark:text-neutral-400">
            Your account is active and your session is secured by Supabase Auth with rotating
            refresh tokens. The features below are rolling out subphase by subphase.
          </p>
        </Card>

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <EmptyFeature
            title="Workspace"
            description="Create or join a workspace for your team or client."
            phase="Subphase 1.2"
          />
          <EmptyFeature
            title="Email accounts"
            description="Connect Gmail, Microsoft or SMTP/IMAP senders."
            phase="Phase 2"
          />
          <EmptyFeature
            title="Campaigns"
            description="Build sequences and schedule outreach."
            phase="Phase 4"
          />
        </div>
      </div>
    </main>
  );
}

function PageFallback() {
  return (
    <main className="min-h-screen bg-neutral-50 dark:bg-neutral-950" aria-busy="true">
      <div className="mx-auto max-w-5xl space-y-6 p-6">
        <div className="h-20 animate-pulse rounded-2xl bg-neutral-200 motion-reduce:animate-none dark:bg-neutral-800" />
        <div className="h-48 animate-pulse rounded-2xl bg-neutral-200 motion-reduce:animate-none dark:bg-neutral-800" />
      </div>
    </main>
  );
}

function EmptyFeature({
  title,
  description,
  phase,
}: {
  title: string;
  description: string;
  phase: string;
}) {
  return (
    <Card className="flex h-full flex-col justify-between">
      <div>
        <h2 className="text-sm font-semibold text-neutral-900 dark:text-neutral-100">{title}</h2>
        <p className="mt-1 text-sm text-neutral-500 dark:text-neutral-400">{description}</p>
      </div>
      <p className="mt-4 inline-flex w-fit rounded-full bg-neutral-100 px-2.5 py-0.5 text-xs font-medium text-neutral-500 dark:bg-neutral-800 dark:text-neutral-400">
        Arrives in {phase}
      </p>
    </Card>
  );
}
