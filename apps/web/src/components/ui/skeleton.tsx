export function Skeleton({ className = "" }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={`bg-surface-sunken animate-pulse rounded-xl motion-reduce:animate-none ${className}`}
    />
  );
}
