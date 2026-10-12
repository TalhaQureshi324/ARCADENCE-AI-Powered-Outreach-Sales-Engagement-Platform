"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";

export interface PageState {
  page: number;
  pageSize: number;
}

export function paginate<T>(items: T[], page: number, pageSize: number): T[] {
  return items.slice((page - 1) * pageSize, page * pageSize);
}

export function pageCount(total: number, pageSize: number): number {
  return Math.max(1, Math.ceil(total / pageSize));
}

export function Pagination({
  page,
  total,
  pageSize,
  onChange,
  itemLabel = "items",
}: {
  page: number;
  total: number;
  pageSize: number;
  onChange: (page: number) => void;
  itemLabel?: string;
}) {
  const pages = pageCount(total, pageSize);
  return (
    <nav
      aria-label="Pagination"
      className="text-muted-foreground flex items-center justify-between px-1 py-3 text-sm"
    >
      <span>
        {total} {itemLabel}
      </span>
      <div className="flex items-center gap-1">
        <button
          type="button"
          aria-label="Previous page"
          disabled={page <= 1}
          onClick={() => onChange(page - 1)}
          className="hover:bg-surface-sunken rounded-lg p-1.5 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <ChevronLeft size={16} />
        </button>
        <span className="px-2 tabular-nums">
          Page {page} of {pages}
        </span>
        <button
          type="button"
          aria-label="Next page"
          disabled={page >= pages}
          onClick={() => onChange(page + 1)}
          className="hover:bg-surface-sunken rounded-lg p-1.5 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <ChevronRight size={16} />
        </button>
      </div>
    </nav>
  );
}
