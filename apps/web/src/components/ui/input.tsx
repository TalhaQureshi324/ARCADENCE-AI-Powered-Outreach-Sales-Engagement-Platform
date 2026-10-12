import { forwardRef, type InputHTMLAttributes } from "react";

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { invalid = false, className = "", ...rest },
  ref,
) {
  const base =
    "block w-full rounded-lg border-0 px-3 py-2 text-sm text-neutral-900 shadow-sm ring-1 ring-inset placeholder:text-neutral-400 focus:outline-none focus:ring-2 disabled:cursor-not-allowed disabled:bg-neutral-50 dark:text-neutral-100 dark:disabled:bg-neutral-800";
  const ring = invalid
    ? "bg-white ring-red-300 focus:ring-red-500 dark:bg-neutral-900"
    : "bg-white ring-neutral-300 focus:ring-indigo-600 dark:bg-neutral-900 dark:ring-neutral-700 dark:focus:ring-indigo-400";
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={`${base} ${ring} ${className}`}
      {...rest}
    />
  );
});
