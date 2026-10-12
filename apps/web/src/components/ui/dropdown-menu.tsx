"use client";

import * as DropdownPrimitive from "@radix-ui/react-dropdown-menu";
import type { ReactNode } from "react";

export const DropdownMenu = DropdownPrimitive.Root;
export const DropdownMenuTrigger = DropdownPrimitive.Trigger;

export function DropdownMenuContent({
  children,
  align = "end",
  className = "",
}: {
  children: ReactNode;
  align?: "start" | "center" | "end";
  className?: string;
}) {
  return (
    <DropdownPrimitive.Portal>
      <DropdownPrimitive.Content
        align={align}
        sideOffset={6}
        className={`border-border bg-surface-raised shadow-raised z-50 min-w-44 rounded-xl border p-1.5 focus:outline-none ${className}`}
      >
        {children}
      </DropdownPrimitive.Content>
    </DropdownPrimitive.Portal>
  );
}

export function DropdownMenuItem({
  children,
  onSelect,
  danger = false,
}: {
  children: ReactNode;
  onSelect?: () => void;
  danger?: boolean;
}) {
  return (
    <DropdownPrimitive.Item
      onSelect={onSelect}
      className={`data-[highlighted]:bg-surface-sunken flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none ${
        danger ? "text-danger" : "text-foreground"
      }`}
    >
      {children}
    </DropdownPrimitive.Item>
  );
}

export function DropdownMenuSeparator() {
  return <DropdownPrimitive.Separator className="bg-border my-1.5 h-px" />;
}

export function DropdownMenuLabel({ children }: { children: ReactNode }) {
  return (
    <DropdownPrimitive.Label className="text-muted-foreground px-3 py-1.5 text-xs font-medium tracking-wider uppercase">
      {children}
    </DropdownPrimitive.Label>
  );
}
