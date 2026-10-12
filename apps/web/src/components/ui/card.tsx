import type { ReactNode } from "react";

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <div className={`border-border bg-surface shadow-soft rounded-xl border ${className}`}>
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
        <p className="text-muted-foreground text-xs font-medium tracking-wider uppercase">
          {eyebrow}
        </p>
      )}
      <h1 className="text-foreground mt-1 text-xl font-semibold">{title}</h1>
      {subtitle && <p className="text-muted-foreground mt-1 text-sm">{subtitle}</p>}
    </div>
  );
}
