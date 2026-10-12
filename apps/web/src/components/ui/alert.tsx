import type { ReactNode } from "react";

export type AlertVariant = "error" | "success" | "info" | "warning";

const styles: Record<AlertVariant, string> = {
  error: "bg-red-50 text-red-700 dark:bg-red-950/40 dark:text-red-300",
  success: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300",
  info: "bg-indigo-50 text-indigo-700 dark:bg-indigo-950/40 dark:text-indigo-300",
  warning: "bg-amber-50 text-amber-800 dark:bg-amber-950/40 dark:text-amber-300",
};

export function Alert({
  variant = "info",
  children,
}: {
  variant?: AlertVariant;
  children: ReactNode;
}) {
  return (
    <div role="alert" className={`rounded-lg p-3 text-sm ${styles[variant]}`}>
      {children}
    </div>
  );
}
