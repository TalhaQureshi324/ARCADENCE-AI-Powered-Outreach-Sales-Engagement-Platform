import type { ReactNode } from "react";

export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="border-border bg-surface flex flex-col items-center justify-center rounded-xl border border-dashed px-6 py-12 text-center">
      {icon && <div className="text-muted-foreground mb-3">{icon}</div>}
      <h3 className="text-foreground text-sm font-semibold">{title}</h3>
      <p className="text-muted-foreground mt-1 max-w-sm text-sm">{description}</p>
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}
