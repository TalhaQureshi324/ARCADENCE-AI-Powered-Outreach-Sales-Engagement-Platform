import type { ReactNode } from "react";

export function Table({ children }: { children: ReactNode }) {
  return (
    <div className="border-border bg-surface overflow-x-auto rounded-xl border">
      <table className="w-full text-sm">{children}</table>
    </div>
  );
}

export function THead({ children }: { children: ReactNode }) {
  return <thead className="bg-surface-sunken text-left">{children}</thead>;
}

export function TH({ children, className = "" }: { children: ReactNode; className?: string }) {
  return (
    <th
      className={`text-muted-foreground px-4 py-2.5 text-xs font-medium tracking-wider uppercase ${className}`}
    >
      {children}
    </th>
  );
}

export function TBody({ children }: { children: ReactNode }) {
  return <tbody className="divide-border divide-y">{children}</tbody>;
}

export function TR({ children }: { children: ReactNode }) {
  return <tr className="hover:bg-surface-sunken/50">{children}</tr>;
}

export function TD({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <td className={`text-foreground px-4 py-3 ${className}`}>{children}</td>;
}
