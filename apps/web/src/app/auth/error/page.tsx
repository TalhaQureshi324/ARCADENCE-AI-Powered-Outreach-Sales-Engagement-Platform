import Link from "next/link";
import { Suspense } from "react";

import { Alert } from "@/components/ui/alert";
import { Card, CardHeader } from "@/components/ui/card";

export const metadata = { title: "Link problem" };

const REASONS: Record<string, { title: string; detail: string }> = {
  expired_link: {
    title: "This link has expired",
    detail:
      "Email links only work once and expire after a period of time. Request a fresh one and open the newest email.",
  },
  invalid_link: {
    title: "This link is not valid",
    detail:
      "The link appears incomplete or was already used. Request a new email and open the latest link.",
  },
};

export default function AuthErrorPage({
  searchParams,
}: {
  searchParams: Promise<{ reason?: string }>;
}) {
  // searchParams is request-time data: read it inside a Suspense boundary so
  // the surrounding shell can still prerender (cacheComponents mode).
  return (
    <Suspense
      fallback={
        <Card className="min-h-40" aria-busy="true">
          <div className="h-2 w-2/3 animate-pulse rounded-full bg-neutral-200 motion-reduce:animate-none dark:bg-neutral-700" />
        </Card>
      }
    >
      <AuthErrorContent searchParams={searchParams} />
    </Suspense>
  );
}

async function AuthErrorContent({ searchParams }: { searchParams: Promise<{ reason?: string }> }) {
  const { reason } = await searchParams;
  const content = REASONS[reason ?? ""] ?? REASONS.invalid_link;

  return (
    <Card>
      <CardHeader eyebrow="Arcadence" title={content.title} />
      <Alert variant="warning">{content.detail}</Alert>
      <div className="mt-6 flex justify-center gap-5 text-sm">
        <Link
          href="/login"
          className="font-medium text-indigo-600 hover:text-indigo-500 dark:text-indigo-400"
        >
          Sign in
        </Link>
        <Link
          href="/forgot-password"
          className="text-neutral-500 hover:text-neutral-700 dark:text-neutral-400"
        >
          Reset password
        </Link>
      </div>
    </Card>
  );
}
