import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { Badge } from "@/components/ui/badge";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { EmptyState } from "@/components/ui/empty-state";
import { paginate, pageCount } from "@/components/ui/pagination";

describe("Badge", () => {
  it("renders its content", () => {
    render(<Badge tone="success">owner</Badge>);
    expect(screen.getByText("owner")).toBeTruthy();
  });
});

describe("Breadcrumbs", () => {
  it("renders links and marks the last item as current page", () => {
    render(<Breadcrumbs items={[{ label: "Home", href: "/app" }, { label: "Dashboard" }]} />);
    const current = screen.getByText("Dashboard");
    expect(current.getAttribute("aria-current")).toBe("page");
    expect(screen.getByText("Home").getAttribute("href")).toBe("/app");
  });
});

describe("EmptyState", () => {
  it("shows title, description and action", () => {
    render(
      <EmptyState
        title="No workspaces yet"
        description="Create your first one."
        action={<button type="button">Create</button>}
      />,
    );
    expect(screen.getByText("No workspaces yet")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create" })).toBeTruthy();
  });
});

describe("pagination helpers", () => {
  it("slices pages and computes page count", () => {
    const items = Array.from({ length: 25 }, (_, i) => i);
    expect(paginate(items, 2, 10)).toEqual([10, 11, 12, 13, 14, 15, 16, 17, 18, 19]);
    expect(pageCount(25, 10)).toBe(3);
    expect(pageCount(0, 10)).toBe(1);
  });
});
