import Link from "next/link";
import type { ReactNode } from "react";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-neutral-50 p-6 dark:bg-neutral-950">
      <Link href="/" className="mb-8 text-center">
        <span className="text-2xl font-semibold tracking-tight text-neutral-900 dark:text-neutral-50">
          Arcadence
        </span>
        <span className="sr-only">— home</span>
      </Link>
      <div className="w-full max-w-md">{children}</div>
      <p className="mt-8 text-center text-xs text-neutral-400 dark:text-neutral-500">
        Arcadence · AI-powered outreach & sales engagement
      </p>
    </main>
  );
}
