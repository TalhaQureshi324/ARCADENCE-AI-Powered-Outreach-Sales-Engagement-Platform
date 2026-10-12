import type { ReactNode } from "react";

import { Input, type InputProps } from "@/components/ui/input";

interface FieldProps extends InputProps {
  label: string;
  error?: string | null;
  hint?: string;
  id: string;
  children?: ReactNode;
}

/** Labeled input with validation message; error text is announced to screen readers. */
export function Field({ label, error, hint, id, children, ...inputProps }: FieldProps) {
  const errorId = `${id}-error`;
  return (
    <div className="space-y-1.5">
      <label
        htmlFor={id}
        className="block text-sm font-medium text-neutral-700 dark:text-neutral-300"
      >
        {label}
      </label>
      {children ?? (
        <Input
          id={id}
          aria-describedby={error ? errorId : undefined}
          invalid={Boolean(error)}
          {...inputProps}
        />
      )}
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-red-600 dark:text-red-400">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{hint}</p>
      ) : null}
    </div>
  );
}
