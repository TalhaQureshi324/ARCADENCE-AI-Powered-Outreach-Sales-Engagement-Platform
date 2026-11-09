import Link from "next/link";
import { Sparkles } from "lucide-react";

import { HealthDashboard } from "@/components/health-dashboard";

export default function Home() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-neutral-50 p-6 dark:bg-neutral-950">
      <div className="border-primary/30 from-primary-soft to-surface mb-8 w-full max-w-2xl rounded-2xl border bg-gradient-to-br p-8 text-center">
        <div className="text-primary mb-2 flex items-center justify-center gap-2">
          <Sparkles size={16} />
          <span className="text-xs font-semibold tracking-widest uppercase">
            Introducing the Founding 10,000
          </span>
        </div>
        <h1 className="text-foreground text-2xl font-semibold tracking-tight">
          Build better conversations. We&apos;ll cover your first year.
        </h1>
        <p className="text-muted-foreground mx-auto mt-3 max-w-xl text-sm leading-relaxed">
          Join the first 10,000 verified Arcadence users to claim 12 months of complimentary
          Founding Access. No payment details. No automatic charges.
        </p>
        <div className="mt-5 flex items-center justify-center gap-3">
          <Link
            href="/signup"
            className="bg-primary text-primary-foreground hover:bg-primary-hover rounded-lg px-5 py-2.5 text-sm font-medium"
          >
            Claim Founding Access
          </Link>
          <a
            href="/api/v1/founding-program"
            className="text-muted-foreground hover:text-foreground text-sm underline"
          >
            Founding Access terms
          </a>
        </div>
        <p className="text-muted-foreground mt-3 text-xs">
          Free-launch users keep full access to the current feature set. Paid plans are not offered
          during the free launch period.
        </p>
      </div>
      <HealthDashboard />
    </main>
  );
}
