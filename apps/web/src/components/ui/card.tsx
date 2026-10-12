import type { ReactNode } from "react";

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div
      className={`rounded-2xl border border-neutral-200 bg-white p-6 shadow-sm dark:border-neutral-800 dark:bg-neutral-900 ${className}`}
    >
      {children}
    </div>
  );
}

export function CardHeader({
  title,
  subtitle,
  eyebrow,
}: {
  title: string;
  subtitle?: string;
  eyebrow?: string;
}) {
  return (
    <div className="mb-6">
      {eyebrow && (
        <p className="text-xs font-medium tracking-wider text-neutral-500 uppercase dark:text-neutral-400">
          {eyebrow}
        </p>
      )}
      <h1 className="mt-1 text-xl font-semibold text-neutral-900 dark:text-neutral-50">{title}</h1>
      {subtitle && (
        <p className="mt-1 text-sm text-neutral-500 dark:text-neutral-400">{subtitle}</p>
      )}
    </div>
  );
}
