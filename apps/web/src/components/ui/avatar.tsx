const AVATAR_SIZES = { sm: "h-7 w-7 text-xs", md: "h-9 w-9 text-sm" } as const;

export function Avatar({ name, size = "md" }: { name: string; size?: keyof typeof AVATAR_SIZES }) {
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]!.toUpperCase())
    .join("");
  return (
    <span
      aria-hidden="true"
      className={`bg-primary-soft text-primary ring-primary/20 inline-flex shrink-0 items-center justify-center rounded-full font-medium ring-1 ring-inset ${AVATAR_SIZES[size]}`}
    >
      {initials || "?"}
    </span>
  );
}
