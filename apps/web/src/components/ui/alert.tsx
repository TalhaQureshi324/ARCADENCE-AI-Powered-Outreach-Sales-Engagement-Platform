import type { ReactNode } from "react";

export type AlertVariant = "error" | "success" | "info" | "warning";

const styles: Record<AlertVariant, string> = {
  error: "bg-danger-soft text-danger",
  success: "bg-success-soft text-success",
  info: "bg-primary-soft text-primary",
  warning: "bg-warning-soft text-warning",
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
