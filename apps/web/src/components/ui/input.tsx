import { forwardRef, type InputHTMLAttributes } from "react";

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { invalid = false, className = "", ...rest },
  ref,
) {
  const base =
    "block w-full rounded-lg border-0 bg-surface px-3 py-2 text-sm text-foreground shadow-sm ring-1 ring-inset ring-border placeholder:text-muted-foreground/70 focus:outline-none focus:ring-2 disabled:cursor-not-allowed disabled:bg-surface-sunken";
  const ring = invalid ? "ring-danger focus:ring-danger" : "focus:ring-primary";
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={`${base} ${ring} ${className}`}
      {...rest}
    />
  );
});
