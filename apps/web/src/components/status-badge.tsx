export type StatusTone = "ok" | "down" | "loading";

const badgeClasses: Record<StatusTone, string> = {
  ok: "bg-emerald-50 text-emerald-700 ring-emerald-600/20 dark:bg-emerald-950/40 dark:text-emerald-300",
  down: "bg-red-50 text-red-700 ring-red-600/20 dark:bg-red-950/40 dark:text-red-300",
  loading:
    "bg-neutral-100 text-neutral-600 ring-neutral-500/20 dark:bg-neutral-800 dark:text-neutral-300",
};

const dotClasses: Record<StatusTone, string> = {
  ok: "bg-emerald-500",
  down: "bg-red-500",
  loading: "bg-neutral-400 dark:bg-neutral-500",
};

export function StatusBadge({ tone, label }: { tone: StatusTone; label: string }) {
  return (
    <span
      className={`inline-flex items-center gap-2 rounded-full px-3 py-1 text-xs font-medium ring-1 ring-inset ${badgeClasses[tone]}`}
    >
      <span aria-hidden="true" className={`h-1.5 w-1.5 rounded-full ${dotClasses[tone]}`} />
      {label}
    </span>
  );
}
